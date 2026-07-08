package com.teya.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * openWakeWord "hey_jarvis" detector — and, piggybacking on the same always-open mic stream, the
 * raw audio feed for real barge-in speech detection.
 *
 * openWakeWord is a 3-model chain, run in series on a rolling buffer of 16 kHz mono audio:
 *
 *   [1,1280] raw audio ──▶ melspectrogram.tflite ──▶ mel frames (N × 32)
 *                          embedding_model.tflite  ◀── window of 76 mel frames [1,76,32,1]
 *                                                  ──▶ 96-dim embedding
 *                          hey_jarvis_v0.1.tflite  ◀── last 16 embeddings [1,1536]
 *                                                  ──▶ P(wake word) in [0,1]
 *
 * Audio is processed one 80 ms (1280-sample) chunk at a time. Each chunk appends mel frames,
 * produces exactly one embedding (from the newest 76 mel frames), and — once 16 embeddings
 * exist — yields one probability. We fire on a short streak above threshold, then cool down.
 *
 * NOTE ON LICENSING: melspectrogram + embedding models are Apache-2.0 (commercial OK). The
 * pre-trained hey_jarvis classifier is CC BY-NC-SA 4.0 (NON-commercial). For a shipping product
 * this classifier must be replaced with a self-trained one — same [1,1536]→[1,1] tensor slot,
 * no code change. See THIRD_PARTY_MODELS.md.
 *
 * Barge-in: interrupting Teya mid-reply needs to react to the user just *talking* (any words,
 * "stop", whatever), the way real voice agents do it — not require repeating a wake phrase, and
 * not a crude loudness guess either (that was tried, tuned blind, and never fired in testing).
 * Detection runs a local Silero VAD (see VoicePipeline.forwardArmedChunk, voice/vad/SileroVad.kt)
 * on these same raw chunks — an earlier attempt streamed them to Mistral's Voxtral Realtime STT
 * instead, but that never produced a single transcription event live (see
 * thoughts/shared/research/2026-07-08-barge-in-vad-options.md). Android can't reliably open a
 * second concurrent `AudioRecord` on top of this one, so instead of a separate capture we tap the
 * same raw chunk stream here via [onArmedAudioChunk], gated by [bargeInArmed] so chunks only flow
 * out while the harness has actually armed it (mid-conversation, while Teya is thinking/speaking —
 * never while idle, and never during her own command capture).
 */
class WakeWordEngine(
    private val context: Context,
    private val onDetected: () -> Unit,
    private val onArmedAudioChunk: (ShortArray) -> Unit
) {
    companion object {
        private const val TAG = "WakeWordEngine"

        private const val MODEL_MELSPEC = "melspectrogram.tflite"
        private const val MODEL_EMBED = "embedding_model.tflite"
        private const val MODEL_WAKE = "hey_jarvis_v0.1.tflite"

        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1280           // 80 ms @ 16 kHz — the canonical streaming step
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76         // mel frames per embedding window
        private const val EMB_DIM = 96
        private const val WAKE_EMBEDDINGS = 16    // embeddings the classifier consumes
        private const val WAKE_INPUT = WAKE_EMBEDDINGS * EMB_DIM // 1536

        private const val THRESHOLD = 0.2f        // pre-trained hey_jarvis scores this setup weakly
                                                  // (~0.1-0.43, inconsistent); ambient peaks ~0.01-0.1.
                                                  // A custom "Hey Teya" model is the durable fix.

        private const val PATIENCE = 1            // frames over threshold before firing; 1 is safe here
                                                  // (ambient peaks ~0.03, threshold 0.2 — huge margin)
        private const val INPUT_GAIN = 6.0f       // software boost before the mel model — AGC isn't
                                                  // available on this device, so we lift the quiet
                                                  // far-field signal here (NoiseSuppressor cleans first)
        private const val COOLDOWN_CHUNKS = 25    // ~2 s suppression after a detection

        private const val MEL_BUFFER_MAX = 970    // ~10 s of mel frames
        private const val EMB_BUFFER_MAX = 120    // ~10 s of embeddings
    }

    private var melspec: Interpreter? = null
    private var embedding: Interpreter? = null
    private var wakeword: Interpreter? = null

    // Reused I/O buffers (native-order float32).
    private var melIn: ByteBuffer? = null
    private var melOut: ByteBuffer? = null
    private var melOutFrames = 0
    private var embIn: ByteBuffer? = null
    private var embOut: ByteBuffer? = null
    private var wakeIn: ByteBuffer? = null
    private var wakeOut: ByteBuffer? = null

    // Rolling feature buffers (class state — the pipeline is stateful).
    private val melBuffer = ArrayDeque<FloatArray>() // each entry: FloatArray(32)
    private val embBuffer = ArrayDeque<FloatArray>() // each entry: FloatArray(96)
    private var positiveStreak = 0
    private var cooldown = 0
    private var peakScore = 0f       // diagnostic
    private var scoreLogCounter = 0  // diagnostic

    // Barge-in audio forwarding. [bargeInArmed] is toggled by the harness (via VoicePipeline) —
    // true only while a conversation turn is thinking/speaking, so idle ambient talk near the
    // device is never even sent anywhere.
    @Volatile var bargeInArmed = false

    private var audioRecord: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var isRunning = false

    init {
        loadModels()
    }

    private fun loadModels() {
        melspec = loadInterpreter(MODEL_MELSPEC, intArrayOf(1, CHUNK))
        embedding = loadInterpreter(MODEL_EMBED, intArrayOf(1, EMB_WINDOW, MEL_BINS, 1))
        wakeword = loadInterpreter(MODEL_WAKE, intArrayOf(1, WAKE_INPUT))

        val mel = melspec ?: return
        // Melspec output is [1,1,N,32] → N frames per chunk (fixed once input is [1,1280]).
        val melOutElems = mel.getOutputTensor(0).shape().fold(1) { a, b -> a * b }
        melOutFrames = melOutElems / MEL_BINS

        melIn = directFloatBuffer(CHUNK)
        melOut = directFloatBuffer(melOutElems)
        embIn = directFloatBuffer(EMB_WINDOW * MEL_BINS)
        embOut = directFloatBuffer(EMB_DIM)
        wakeIn = directFloatBuffer(WAKE_INPUT)
        wakeOut = directFloatBuffer(1)
        Log.d(TAG, "Models ready. melOutFrames=$melOutFrames per 80ms chunk")
    }

    private fun loadInterpreter(assetName: String, inputDims: IntArray): Interpreter? {
        return try {
            val afd = context.assets.openFd(assetName)
            val channel = FileInputStream(afd.fileDescriptor).channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            val interp = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
            // Android's Interpreter needs an explicit resize+allocate for these dynamic-input
            // models, otherwise CONV_2D fails to prepare (openWakeWord issue #223).
            interp.resizeInput(0, inputDims)
            interp.allocateTensors()
            Log.d(
                TAG,
                "Loaded $assetName in=${interp.getInputTensor(0).shape().joinToString()} " +
                    "out=${interp.getOutputTensor(0).shape().joinToString()}"
            )
            interp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $assetName", e)
            null
        }
    }

    private fun directFloatBuffer(floats: Int): ByteBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

    /**
     * Attach AGC + noise suppression to the capture session (AEC is created but deliberately kept
     * disabled — see the AcousticEchoCanceler branch below for why). This matters because this
     * engine now keeps listening while Teya is talking (barge-in — see HarnessService), so the mic
     * is picking up her own TTS bleeding out of the speaker.
     */
    private fun enableAudioEffects(sessionId: Int) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.also { it.setEnabled(true) }
                Log.d(TAG, "AGC available, enabled=${agc?.enabled}")
            } else {
                Log.d(TAG, "AGC NOT available on this device")
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.setEnabled(true) }
                Log.d(TAG, "NoiseSuppressor available, enabled=${noiseSuppressor?.enabled}")
            } else {
                Log.d(TAG, "NoiseSuppressor NOT available on this device")
            }
            if (AcousticEchoCanceler.isAvailable()) {
                // Left ON as a normal echo/noise cleanup effect. It's not barge-in's self-echo
                // defense (see VoicePipeline.forwardArmedChunk, which never runs the VAD while our
                // own audio is playing) — this device's AEC implementation isn't reliable enough
                // for that; see thoughts/shared/research/2026-07-08-barge-in-vad-options.md.
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.also { it.setEnabled(true) }
                Log.d(TAG, "AEC available, enabled=${echoCanceler?.enabled}")
            } else {
                Log.d(TAG, "AEC NOT available on this device — barge-in relies on the playback-state gate in VoicePipeline instead")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable audio effects", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        if (melspec == null || embedding == null || wakeword == null) {
            Log.e(TAG, "Models not loaded; wake word disabled")
            return
        }
        resetBuffers()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,  // speech-tuned front-end (vs raw MIC)
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK * 4)
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }
        enableAudioEffects(audioRecord!!.audioSessionId)

        isRunning = true
        try {
            audioRecord?.startRecording()
            Log.d(TAG, "Microphone recording started (wake word)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRunning = false
            return
        }

        Thread {
            // Guard the loop: stop()/start() cycles (pausing for STT) can release the
            // AudioRecord while a blocking read() is in flight — swallow that instead
            // of crashing the app on a background thread.
            try {
                val chunk = ShortArray(CHUNK)
                while (isRunning) {
                    var filled = 0
                    while (isRunning && filled < CHUNK) {
                        val n = audioRecord?.read(chunk, filled, CHUNK - filled) ?: 0
                        if (n > 0) filled += n
                        else if (n < 0) { Log.e(TAG, "AudioRecord read error: $n"); break }
                    }
                    if (filled == CHUNK) {
                        // Copy: forwarding hands off to an async consumer (a channel → coroutine →
                        // WebSocket send), while `chunk` itself gets overwritten next iteration.
                        if (bargeInArmed) onArmedAudioChunk(chunk.copyOf())
                        processChunk(chunk)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop error", e)
            }
        }.start()
    }

    private fun processChunk(chunk: ShortArray) {
        val melspec = this.melspec ?: return
        val embedding = this.embedding ?: return
        val wakeword = this.wakeword ?: return
        val melIn = this.melIn ?: return
        val melOut = this.melOut ?: return

        // 1) Melspectrogram. Feed raw int16 sample values as float32 (NOT normalized to ±1).
        melIn.rewind()
        for (i in 0 until CHUNK) {
            melIn.putFloat((chunk[i] * INPUT_GAIN).coerceIn(-32768f, 32767f))
        }
        melIn.rewind()
        melOut.rewind()
        melspec.run(melIn, melOut)
        melOut.rewind()
        for (f in 0 until melOutFrames) {
            val frame = FloatArray(MEL_BINS)
            for (b in 0 until MEL_BINS) {
                frame[b] = melOut.getFloat() / 10f + 2f   // openWakeWord mel normalization
            }
            melBuffer.addLast(frame)
        }
        while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeFirst()

        // 2) One embedding from the newest 76 mel frames.
        if (melBuffer.size >= EMB_WINDOW) {
            val embIn = this.embIn ?: return
            val embOut = this.embOut ?: return
            embIn.rewind()
            val start = melBuffer.size - EMB_WINDOW
            for (f in 0 until EMB_WINDOW) {
                val frame = melBuffer[start + f]
                for (b in 0 until MEL_BINS) embIn.putFloat(frame[b])
            }
            embIn.rewind()
            embOut.rewind()
            embedding.run(embIn, embOut)
            embOut.rewind()
            val emb = FloatArray(EMB_DIM) { embOut.getFloat() }
            embBuffer.addLast(emb)
            while (embBuffer.size > EMB_BUFFER_MAX) embBuffer.removeFirst()
        }

        // 3) Classify from the newest 16 embeddings.
        if (embBuffer.size >= WAKE_EMBEDDINGS) {
            val wakeIn = this.wakeIn ?: return
            val wakeOut = this.wakeOut ?: return
            wakeIn.rewind()
            val start = embBuffer.size - WAKE_EMBEDDINGS
            for (e in 0 until WAKE_EMBEDDINGS) {
                val emb = embBuffer[start + e]
                for (d in 0 until EMB_DIM) wakeIn.putFloat(emb[d])
            }
            wakeIn.rewind()
            wakeOut.rewind()
            wakeword.run(wakeIn, wakeOut)
            wakeOut.rewind()
            handleScore(wakeOut.getFloat())
        }
    }

    private fun handleScore(prob: Float) {
        // Diagnostic: log the peak score every ~4s so we can see whether the model responds to speech.
        if (prob > peakScore) peakScore = prob
        if (++scoreLogCounter >= 50) {
            if (peakScore > 0.05f) Log.d(TAG, "peak wake score (last ~4s) = $peakScore")
            peakScore = 0f
            scoreLogCounter = 0
        }
        if (cooldown > 0) cooldown--

        positiveStreak = if (prob >= THRESHOLD) positiveStreak + 1 else 0
        if (positiveStreak >= PATIENCE && cooldown == 0) {
            Log.d(TAG, "Wake word detected! score=$prob")
            positiveStreak = 0
            cooldown = COOLDOWN_CHUNKS
            onDetected()
        }
    }

    private fun resetBuffers() {
        melBuffer.clear()
        embBuffer.clear()
        positiveStreak = 0
        cooldown = 0
    }

    fun stop() {
        isRunning = false
        try { agc?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { echoCanceler?.release() } catch (_: Exception) {}
        agc = null
        noiseSuppressor = null
        echoCanceler = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        }
        audioRecord = null
    }
}
