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
import com.teya.agent.harness.ConfigManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Wake-word detection — and, piggybacking on the same always-open mic stream, the raw audio feed
 * for real barge-in speech detection. The actual classifier pipeline is pluggable (see
 * [WakeWordDetector]): either the default openWakeWord 3-model chain ([OpenWakeWordDetector]) or
 * our custom microWakeWord "hey_teya" model ([MicroWakeWordDetector]), selected by
 * [ConfigManager.useMicroWakeWord]. Everything else here — AudioRecord capture, audio effects,
 * barge-in arming, the streak/cooldown trigger logic, speaker-ID pre-roll capture — is shared
 * across both, since it's model-agnostic.
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
 * pre-trained hey_jarvis classifier is CC BY-NC-SA 4.0 (NON-commercial). microWakeWord's
 * "hey_teya" model (see THIRD_PARTY_MODELS.md) solves this — trained on our own data, unrestricted
 * license — but is still validating against hey_jarvis on-device before it fully replaces it.
 *
 * Barge-in: interrupting Teya mid-reply needs to react to the user just *talking* (any words,
 * "stop", whatever), the way real voice agents do it — not require repeating a wake phrase, and
 * not a crude loudness guess either (that was tried, tuned blind, and never fired in testing).
 * Detection runs a local Silero VAD (see VoicePipeline.forwardArmedChunk, voice/vad/SileroVad.kt)
 * on these same raw chunks — an earlier attempt streamed them to Mistral's Voxtral Realtime STT
 * instead, but that never produced a single transcription event live. Android can't reliably open a
 * second concurrent `AudioRecord` on top of this one, so instead of a separate capture we tap the
 * same raw chunk stream here via [onArmedAudioChunk], gated by [bargeInArmed] so chunks only flow
 * out while the harness has actually armed it (mid-conversation, while Teya is thinking/speaking —
 * never while idle, and never during her own command capture).
 */
class WakeWordEngine(
    private val context: Context,
    private val onDetected: (audio: ShortArray) -> Unit,
    private val onArmedAudioChunk: (ShortArray) -> Unit
) {
    companion object {
        private const val TAG = "WakeWordEngine"

        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1280           // 80 ms @ 16 kHz — the canonical streaming step

        private const val COOLDOWN_CHUNKS = 25    // ~2 s suppression after a detection

        // Per-speaker voice ID (see voice/speaker/) — a rolling raw-PCM window snapshotted the
        // instant the wake word fires, so CamPlusPlusSpeakerEmbedder gets audio actually spoken by
        // whoever triggered Teya, not silence/room noise from before they started talking. ~3s
        // (not 2s) per the Phase 0 spike (docs/experiments.md): same/different-speaker cosine
        // margin is meaningfully healthier at 3.5s than at 2s.
        private const val SPEAKER_CAPTURE_CHUNKS = 38 // 38 * 80ms ≈ 3.04s
    }

    // Read live (not cached) at each use site so Admin's "Voice tuning" section takes effect
    // without restarting the service.
    private val config = ConfigManager(context)

    private val detector: WakeWordDetector =
        if (config.useMicroWakeWord) MicroWakeWordDetector(context, config) else OpenWakeWordDetector(context, config)

    private var positiveStreak = 0
    private var cooldown = 0

    // Always filled (regardless of arming state) so a wake-word trigger has real pre-roll audio to
    // hand off for speaker ID — unlike bargeInArmed/onArmedAudioChunk, this isn't gated to a
    // mid-conversation window, since the wake-word moment itself is what needs the audio.
    private val speakerCaptureBuffer = ArrayDeque<ShortArray>()
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

    // Phase 3 diagnostic (WebView AEC capture-concurrency investigation, see
    // thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md): raw mic peak amplitude,
    // independent of wake-word scoring — the wake-word model itself is too weak/inconsistent on
    // this device (see THRESHOLD's doc comment) to reliably tell "AudioRecord conflict" apart from
    // "model just didn't score this utterance," so this measures the input signal directly instead.
    private var wwChunkCounter = 0
    private var wwPeakRawAmplitude = 0

    // Desired platform-AEC state, applied fresh by enableAudioEffects() on every mic restart
    // (start() re-creates the audio session — and its effects — each time listenForCommand pauses
    // and resumes wake-word listening, which happens repeatedly within a single conversation).
    // Without this, a restart mid-session would silently re-enable(true) the effect and undo
    // setPlatformAecEnabled(false), since enableAudioEffects previously hardcoded true.
    @Volatile private var platformAecDesiredEnabled = true

    /**
     * Attach AGC + noise suppression + echo cancellation to the capture session. This matters
     * because this engine now keeps listening while Teya is talking (barge-in — see
     * HarnessService), so the mic is picking up her own TTS bleeding out of the speaker; see the
     * AcousticEchoCanceler branch below for how its enabled state is managed.
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
                // Defaults ON as a normal echo/noise cleanup effect, but applies
                // platformAecDesiredEnabled (not a hardcoded true) since this runs fresh on every
                // mic restart within a conversation — see platformAecDesiredEnabled's doc comment:
                // a restart between setPlatformAecEnabled(false) and endAecSession() must not
                // silently re-enable the effect while a WebView AEC session is active.
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.also { it.setEnabled(platformAecDesiredEnabled) }
                Log.d(TAG, "AEC available, enabled=${echoCanceler?.enabled}")
            } else {
                Log.d(TAG, "AEC NOT available on this device — barge-in relies on the playback-state gate in VoicePipeline instead")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable audio effects", e)
        }
    }

    /**
     * Toggles the platform [AcousticEchoCanceler] at runtime (safe to call anytime after [start] —
     * it's an effect flag, not something tied to recreating the [AudioRecord] session). While a
     * `WebViewAecHost` barge-in session is active, this device's platform AEC — previously harmless
     * because [onArmedAudioChunk] never ran during playback at all — would otherwise run
     * concurrently with continuous mid-sentence listening and was found to over-suppress the
     * captured signal to near-silence. `VoicePipeline` disables it for the duration of that
     * session and re-enables it once the session ends, so idle wake-word listening (no playback,
     * no WebView AEC running) keeps its original noise/echo cleanup untouched.
     *
     * Persists [enabled] as [platformAecDesiredEnabled] in addition to applying it to the current
     * effect instance immediately — the mic restarts repeatedly *within* one conversation session
     * ([start] re-creates the audio session's effects on every restart), so without persisting the
     * desired state here, the very next restart's [enableAudioEffects] would silently re-enable(true)
     * the effect this call just turned off.
     */
    fun setPlatformAecEnabled(enabled: Boolean) {
        platformAecDesiredEnabled = enabled
        echoCanceler?.setEnabled(enabled)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        if (!detector.isInitialized) {
            Log.e(TAG, "Detector not loaded; wake word disabled")
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
                        speakerCaptureBuffer.addLast(chunk.copyOf())
                        while (speakerCaptureBuffer.size > SPEAKER_CAPTURE_CHUNKS) speakerCaptureBuffer.removeFirst()
                        processChunk(chunk)

                        var peak = 0
                        for (s in chunk) { val a = kotlin.math.abs(s.toInt()); if (a > peak) peak = a }
                        if (peak > wwPeakRawAmplitude) wwPeakRawAmplitude = peak
                        if (++wwChunkCounter % 25 == 0) {
                            Log.d(TAG, "peak RAW amplitude (last ~2s) = $wwPeakRawAmplitude / 32767")
                            wwPeakRawAmplitude = 0
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop error", e)
            }
        }.start()
    }

    private fun processChunk(chunk: ShortArray) {
        for (score in detector.processChunk(chunk, config.wakeWordInputGain)) {
            handleScore(score)
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

        positiveStreak = if (prob >= detector.threshold) positiveStreak + 1 else 0
        if (positiveStreak >= detector.patience && cooldown == 0) {
            Log.d(TAG, "Wake word detected! score=$prob")
            positiveStreak = 0
            cooldown = COOLDOWN_CHUNKS
            onDetected(captureWindow())
        }
    }

    /** Flattens the rolling pre-roll buffer into one contiguous window for speaker ID. */
    private fun captureWindow(): ShortArray {
        val totalSamples = speakerCaptureBuffer.sumOf { it.size }
        val out = ShortArray(totalSamples)
        var offset = 0
        for (chunk in speakerCaptureBuffer) {
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            offset += chunk.size
        }
        return out
    }

    private fun resetBuffers() {
        detector.reset()
        speakerCaptureBuffer.clear()
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

/**
 * A wake-word classifier pipeline: raw 16 kHz mono PCM16 chunks in, wake-word probabilities out.
 * [WakeWordEngine] owns everything else (mic capture, audio effects, the streak/cooldown trigger
 * logic) so implementations only need to handle feature extraction + classification.
 */
private interface WakeWordDetector {
    val isInitialized: Boolean

    /** Score threshold (0-1) a probability must clear before it counts toward [patience]. */
    val threshold: Float

    /** Consecutive over-threshold scores required before the wake word fires. */
    val patience: Int

    /**
     * Feeds one chunk of raw audio (already gain-applied by the caller) and returns any wake-word
     * probabilities computed from it, in chronological order — usually zero or one, but
     * implementations that classify more often than once per chunk may return several.
     */
    fun processChunk(chunk: ShortArray, inputGain: Float): List<Float>

    /** Clears rolling feature/state buffers (start of a fresh listening session). */
    fun reset()
}

/**
 * openWakeWord's 3-model chain (see [WakeWordEngine]'s class doc for the pipeline diagram).
 * Unchanged from before the [WakeWordDetector] extraction — same models, same buffers, same math.
 */
private class OpenWakeWordDetector(
    private val context: Context,
    private val config: ConfigManager,
) : WakeWordDetector {
    companion object {
        private const val TAG = "OpenWakeWordDetector"

        private const val MODEL_MELSPEC = "melspectrogram.tflite"
        private const val MODEL_EMBED = "embedding_model.tflite"
        private const val MODEL_WAKE = "hey_jarvis_v0.1.tflite"

        private const val CHUNK = 1280
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76         // mel frames per embedding window
        private const val EMB_DIM = 96
        private const val WAKE_EMBEDDINGS = 16    // embeddings the classifier consumes
        private const val WAKE_INPUT = WAKE_EMBEDDINGS * EMB_DIM // 1536

        // THRESHOLD/PATIENCE/INPUT_GAIN live in ConfigManager (Admin's "Voice tuning" section) —
        // read live via `config` so retuning doesn't need a rebuild+install cycle. Defaults:
        // threshold 0.2f (pre-trained hey_jarvis scores this setup weakly, ~0.1-0.43, inconsistent;
        // ambient peaks ~0.01-0.1 — a custom "Hey Teya" model is the durable fix), patience 1 (safe
        // here — ambient peaks ~0.03, threshold 0.2 — huge margin), input gain 6.0f (software boost
        // before the mel model — AGC isn't available on this device, NoiseSuppressor cleans first).

        private const val MEL_BUFFER_MAX = 970    // ~10 s of mel frames
        private const val EMB_BUFFER_MAX = 120    // ~10 s of embeddings
    }

    override val threshold: Float get() = config.wakeWordThreshold
    override val patience: Int get() = config.wakeWordPatience

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

    override val isInitialized: Boolean
        get() = melspec != null && embedding != null && wakeword != null

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

    override fun processChunk(chunk: ShortArray, inputGain: Float): List<Float> {
        val melspec = this.melspec ?: return emptyList()
        val embedding = this.embedding ?: return emptyList()
        val wakeword = this.wakeword ?: return emptyList()
        val melIn = this.melIn ?: return emptyList()
        val melOut = this.melOut ?: return emptyList()

        // 1) Melspectrogram. Feed raw int16 sample values as float32 (NOT normalized to ±1).
        melIn.rewind()
        for (i in 0 until CHUNK) {
            melIn.putFloat((chunk[i] * inputGain).coerceIn(-32768f, 32767f))
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
            val embIn = this.embIn ?: return emptyList()
            val embOut = this.embOut ?: return emptyList()
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
            val wakeIn = this.wakeIn ?: return emptyList()
            val wakeOut = this.wakeOut ?: return emptyList()
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
            return listOf(wakeOut.getFloat())
        }
        return emptyList()
    }

    override fun reset() {
        melBuffer.clear()
        embBuffer.clear()
    }
}

/**
 * Our custom microWakeWord "hey_teya" model — a genuinely *stateful* streaming TFLite model, not
 * a plain stateless classifier: its internal conv layers carry temporal history across calls via
 * TFLite's resource-variable mechanism (cleared by [Interpreter.resetVariableTensors], called from
 * [reset]). Confirmed by inspecting the model's actual tensors directly (not assumed from the
 * training pipeline's docs) — see thoughts/shared/plans/2026-07-12-microwakeword-android-integration.md.
 *
 * Pipeline: raw audio → [MicroFrontend] (native microfrontend, produces one 40-dim feature frame
 * per 10ms step) → buffered two frames at a time, non-overlapping (`hey_teya.tflite`'s input is
 * `[1,2,40]` — exactly `input_feature_frames: 2` new frames per call; the model's own internal
 * state carries earlier history, so calls must NOT re-send overlapping frames) → int8-quantized
 * using the tensor's *actual* scale/zero-point read from the model at load time (not hardcoded —
 * these are training-run-specific) → classifier → dequantized uint8 probability.
 */
private class MicroWakeWordDetector(
    private val context: Context,
    private val config: ConfigManager,
) : WakeWordDetector {
    companion object {
        private const val TAG = "MicroWakeWordDetector"
        private const val MODEL_WAKE = "hey_teya.tflite"
        private const val WINDOW_FRAMES = 2 // hey_teya.json: tater_native.frontend.input_feature_frames
    }

    override val threshold: Float get() = config.microWakeWordCutoff
    override val patience: Int get() = config.microWakeWordSlidingWindow

    private val frontend = MicroFrontend()
    private var interpreter: Interpreter? = null
    private var inputScale = 1f
    private var inputZeroPoint = 0
    private var outputScale = 1f
    private var outputZeroPoint = 0
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null

    private val pendingFrames = ArrayDeque<FloatArray>()

    override val isInitialized: Boolean
        get() = frontend.isInitialized && interpreter != null

    init {
        loadModel()
    }

    private fun loadModel() {
        interpreter = try {
            val afd = context.assets.openFd(MODEL_WAKE)
            val channel = FileInputStream(afd.fileDescriptor).channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) }).also { interp ->
                interp.allocateTensors()
                val inputTensor = interp.getInputTensor(0)
                val outputTensor = interp.getOutputTensor(0)
                val inputQuant = inputTensor.quantizationParams()
                val outputQuant = outputTensor.quantizationParams()
                inputScale = inputQuant.scale
                inputZeroPoint = inputQuant.zeroPoint
                outputScale = outputQuant.scale
                outputZeroPoint = outputQuant.zeroPoint
                Log.d(
                    TAG,
                    "Loaded $MODEL_WAKE in=${inputTensor.shape().joinToString()} " +
                        "out=${outputTensor.shape().joinToString()} " +
                        "inputScale=$inputScale inputZeroPoint=$inputZeroPoint " +
                        "outputScale=$outputScale outputZeroPoint=$outputZeroPoint"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $MODEL_WAKE", e)
            null
        }
        inputBuffer = ByteBuffer.allocateDirect(WINDOW_FRAMES * MicroFrontend.FEATURE_SIZE)
            .order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
    }

    override fun processChunk(chunk: ShortArray, inputGain: Float): List<Float> {
        val interp = interpreter ?: return emptyList()
        val inputBuffer = this.inputBuffer ?: return emptyList()
        val outputBuffer = this.outputBuffer ?: return emptyList()
        if (!frontend.isInitialized) return emptyList()

        val gained = ShortArray(chunk.size) { i ->
            (chunk[i].toInt() * inputGain).coerceIn(-32768f, 32767f).toInt().toShort()
        }
        val frames = frontend.processSamples(gained)
        if (frames.isEmpty()) return emptyList()
        pendingFrames.addAll(frames)

        val scores = mutableListOf<Float>()
        while (pendingFrames.size >= WINDOW_FRAMES) {
            inputBuffer.rewind()
            repeat(WINDOW_FRAMES) {
                val frame = pendingFrames.removeFirst()
                for (value in frame) {
                    val quantized = Math.round(value / inputScale) + inputZeroPoint
                    inputBuffer.put(quantized.coerceIn(-128, 127).toByte())
                }
            }
            inputBuffer.rewind()
            outputBuffer.rewind()
            interp.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            val rawOutput = outputBuffer.get().toInt() and 0xFF // uint8
            scores.add((rawOutput - outputZeroPoint) * outputScale)
        }
        return scores
    }

    override fun reset() {
        frontend.reset()
        interpreter?.resetVariableTensors()
        pendingFrames.clear()
    }
}
