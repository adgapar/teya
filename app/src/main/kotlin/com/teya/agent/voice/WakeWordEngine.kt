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
 * microWakeWord "hey_teya" detector — and, piggybacking on the same always-open mic stream, the
 * raw audio feed for real barge-in speech detection.
 *
 * Pipeline: raw 16 kHz mono audio → [MicroFrontend] (native TFLite Micro "microfrontend" —
 * fixed-point mel-filterbank feature extraction, vendored under `app/src/main/cpp/microfrontend/`,
 * not expressible as a portable TFLite graph) → one 40-dim feature frame per 10ms step → buffered
 * two frames at a time, non-overlapping (`hey_teya.tflite`'s input is `[1,2,40]` — exactly
 * `input_feature_frames: 2` new frames per call) → int8-quantized using the tensor's *actual*
 * scale/zero-point read from the model at load time (training-run-specific, not hardcoded) →
 * classifier → dequantized uint8 probability.
 *
 * `hey_teya.tflite` is a genuinely *stateful* streaming model — confirmed by inspecting its actual
 * tensors directly (not assumed from training docs): its internal conv layers carry temporal
 * history across calls via TFLite's resource-variable mechanism, cleared by
 * [Interpreter.resetVariableTensors] (see [reset]). Calls must feed strictly new, non-overlapping
 * frames — the model's own state already carries earlier context.
 *
 * Trained via the Mac microWakeWord-Trainer-AppleSilicon app on personal recordings + Piper-TTS
 * synthetic positives + standard negative datasets; unrestricted license (see
 * THIRD_PARTY_MODELS.md) — replaced openWakeWord's CC-BY-NC-SA `hey_jarvis_v0.1` classifier after
 * validating better real-world recall/far-field performance live on-device (see
 * docs/experiments.md → "Problem: wake word").
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

        private const val MODEL_WAKE = "hey_teya.tflite"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1280           // 80 ms @ 16 kHz — the canonical streaming step
        private const val WINDOW_FRAMES = 2       // hey_teya.json: tater_native.frontend.input_feature_frames

        // THRESHOLD/PATIENCE/INPUT_GAIN live in ConfigManager (Admin's "Voice tuning" section) —
        // read live via `config` so retuning doesn't need a rebuild+install cycle. Defaults match
        // hey_teya.json's calibration: threshold (cutoff) 0.53f, patience (sliding_window_size) 3,
        // input gain 6.0f (software boost before the frontend — AGC isn't available on this
        // device, NoiseSuppressor cleans first).
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

    private val frontend = MicroFrontend()
    private var interpreter: Interpreter? = null
    private var inputScale = 1f
    private var inputZeroPoint = 0
    private var outputScale = 1f
    private var outputZeroPoint = 0
    private var wakeIn: ByteBuffer? = null
    private var wakeOut: ByteBuffer? = null
    private val pendingFrames = ArrayDeque<FloatArray>()

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
    // independent of wake-word scoring, to compare AudioRecord signal quality directly.
    private var wwChunkCounter = 0
    private var wwPeakRawAmplitude = 0

    // Desired platform-AEC state, applied fresh by enableAudioEffects() on every mic restart
    // (start() re-creates the audio session — and its effects — each time listenForCommand pauses
    // and resumes wake-word listening, which happens repeatedly within a single conversation).
    // Without this, a restart mid-session would silently re-enable(true) the effect and undo
    // setPlatformAecEnabled(false), since enableAudioEffects previously hardcoded true.
    @Volatile private var platformAecDesiredEnabled = true

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
        wakeIn = ByteBuffer.allocateDirect(WINDOW_FRAMES * MicroFrontend.FEATURE_SIZE).order(ByteOrder.nativeOrder())
        wakeOut = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
    }

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

    /** Whether the capture loop is currently running (see [start]/[stop]). */
    val isCapturing: Boolean get() = isRunning

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        if (interpreter == null || !frontend.isInitialized) {
            Log.e(TAG, "Model not loaded; wake word disabled")
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
            Log.d(TAG, "Microphone recording started (wake word), threshold=${config.wakeWordThreshold} " +
                "patience=${config.wakeWordPatience} gain=${config.wakeWordInputGain}")
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
        val interp = interpreter ?: return
        val wakeIn = this.wakeIn ?: return
        val wakeOut = this.wakeOut ?: return
        if (!frontend.isInitialized) return

        val inputGain = config.wakeWordInputGain
        val gained = ShortArray(chunk.size) { i ->
            (chunk[i].toInt() * inputGain).coerceIn(-32768f, 32767f).toInt().toShort()
        }
        val frames = frontend.processSamples(gained)
        if (frames.isEmpty()) return
        pendingFrames.addAll(frames)

        while (pendingFrames.size >= WINDOW_FRAMES) {
            wakeIn.rewind()
            repeat(WINDOW_FRAMES) {
                val frame = pendingFrames.removeFirst()
                for (value in frame) {
                    val quantized = Math.round(value / inputScale) + inputZeroPoint
                    wakeIn.put(quantized.coerceIn(-128, 127).toByte())
                }
            }
            wakeIn.rewind()
            wakeOut.rewind()
            interp.run(wakeIn, wakeOut)
            wakeOut.rewind()
            val rawOutput = wakeOut.get().toInt() and 0xFF // uint8
            handleScore((rawOutput - outputZeroPoint) * outputScale)
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

        positiveStreak = if (prob >= config.wakeWordThreshold) positiveStreak + 1 else 0
        if (positiveStreak >= config.wakeWordPatience && cooldown == 0) {
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
        frontend.reset()
        interpreter?.resetVariableTensors()
        pendingFrames.clear()
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
