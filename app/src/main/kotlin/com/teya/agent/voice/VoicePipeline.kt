package com.teya.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import com.teya.agent.brain.MistralClient
import com.teya.agent.voice.aec.NativeAec3
import com.teya.agent.voice.aec.Resampler
import com.teya.agent.voice.vad.SileroVad
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.sqrt

class VoicePipeline(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES = SAMPLE_RATE / (1000 / FRAME_MS) // 320 samples @ 20ms
        private const val SILENCE_RMS_THRESHOLD = 700.0                   // energy floor for "speech"
        private const val TRAILING_SILENCE_MS = 800                       // silence that ends a command
        private const val DEFAULT_INITIAL_SILENCE_MS = 4000               // give up if nobody speaks (default)
        private const val MAX_RECORDING_MS = 10000                        // hard cap on a single command
        private const val TTS_SAMPLE_RATE = 24000                         // Voxtral PCM output rate
        private const val BARGE_IN_GAIN = 6.0f  // matches WakeWordEngine.INPUT_GAIN — no hardware AGC

        // Phase 4 kill-switch (Plan B): true = the new continuous mid-sentence barge-in behavior —
        // forwardArmedChunk's self-echo gate is exempted for the streamToSpeaker path (once past
        // the convergence lead-in below) and HarnessService.respond() skips its inter-sentence gap
        // for sentences that streamed. false = both revert to their exact pre-Plan-B behavior (gate
        // + gap intact), regardless of whether aec3 is constructed/fed. Flip this one line for an
        // instant rollback if Phase 5's real-device testing goes worse than the old gap-gated
        // workaround it replaces (see the plan's Failure Mode / Rollback section).
        const val AEC3_BARGE_IN_ENABLED = true

        // Convergence lead-in (Phase 4): how long after the session's first render frame
        // (firstRenderFrameAtMs) before barge-in detection is trusted, per Plan A's
        // double-talk-from-frame-zero finding (~0dB suppression from frame zero vs. ~72dB once
        // converged). Derived from Plan A's measured ~1s synthetic-tone convergence time, doubled
        // as a safety margin since that number was measured against a clean synthetic signal, not
        // this device's real speaker->mic acoustic path and real voice — Phase 5 re-checks and
        // tunes this against actual on-device behavior rather than assuming the synthetic number
        // transfers exactly.
        private const val AEC3_LEAD_IN_MS = 2000L
    }

    private val wakeWordEngine = WakeWordEngine(
        context,
        onDetected = { onWakeWord() },
        onArmedAudioChunk = { chunk -> forwardArmedChunk(chunk) }
    )

    private var wakeWordCallback: (() -> Unit)? = null
    private var bargeInCallback: (() -> Unit)? = null
    private var wakeWordActive = false
    private var mistralClient: MistralClient? = null

    // Barge-in speech detection (see voice/vad/SileroVad.kt, an original implementation of
    // Silero VAD's own streaming algorithm run directly via ONNX Runtime): while armed, raw
    // wake-word-engine chunks are reassembled into VAD-sized frames and checked synchronously,
    // right on the mic capture thread — no network round-trip, so no channel/coroutine hand-off is
    // needed (unlike the earlier Mistral Voxtral Realtime attempt this replaced). One instance per
    // armed window since Silero carries RNN hidden state across calls.
    @Volatile private var sileroVad: SileroVad? = null
    private var vadFrameBuffer = ShortArray(0)
    @Volatile private var bargeInFired = false
    // Guards sileroVad's create/use/close: forwardArmedChunk runs on WakeWordEngine's capture
    // thread while setBargeInArmed(false) runs on the harness's coroutine thread — without this,
    // a disarm's close() can race a concurrent isSpeech() call and crash natively (use-after-free
    // on the ONNX session), which is exactly what happened live (silent process restart, no JVM
    // exception — the signature of a native crash, not a Kotlin one).
    private val vadLock = Any()
    private var vadChunkCounter = 0    // diagnostic
    private var vadPeakConfidence = 0f // diagnostic
    private var vadPeakRawAmplitude = 0 // diagnostic — pre-gain, to check the mic itself has signal

    // AEC3 capture-side leftover buffer (Plan B, Phase 3): reassembles forwardArmedChunk's raw
    // 1280-sample mic chunks into NativeAec3.FRAME_SIZE (160-sample) pieces, same pattern as
    // vadFrameBuffer above. Only ever touched from forwardArmedChunk, which always runs on
    // WakeWordEngine's single dedicated capture thread (never concurrently with itself), so the
    // buffer itself needs no lock of its own — access to the aec3 field alongside it is still
    // guarded by aecLock inside cleanCaptureChunk(), for the same reason feedRenderToAec3() guards
    // its analyzeRender calls: aec3 is written from the harness's coroutine thread
    // (startAecSession()/endAecSession()), so a session-end close() could otherwise race a
    // concurrent processCapture() call on this thread.
    private var aecCaptureBuffer = ShortArray(0)

    // AEC3 render feed (Plan B, Phase 2): unlike sileroVad above, aec3's lifecycle is deliberately
    // NOT per-turn — it spans the whole conversation session (constructed/closed once by
    // HarnessService.runConversation() via startAecSession()/endAecSession()), so its adaptive
    // filter's convergence cost (Plan A's double-talk-from-frame-zero finding: ~0dB suppression
    // from frame zero vs. ~72dB once converged) is paid once per session, not once per turn. A
    // dedicated aecLock (not vadLock) guards this independently-scoped resource: analyzeRender
    // runs on streamToSpeaker's IO-dispatcher thread while endAecSession() runs on the harness's
    // coroutine thread, so a session-end close() could otherwise race a concurrent analyzeRender
    // call, mirroring the exact race vadLock already guards against for sileroVad.
    @Volatile private var aec3: NativeAec3? = null
    private val aecLock = Any()
    private var renderResampler = Resampler()
    private var renderFrameBuffer = ShortArray(0)
    // Set once — only if still 0L — the first time analyzeRender is actually fed a frame this
    // session. Written from the render/TTS thread here; read from the capture thread by a later
    // phase's convergence-lead-in gate, hence @Volatile for cross-thread visibility.
    @Volatile private var firstRenderFrameAtMs: Long = 0L
    private var renderFrameCount = 0 // diagnostic: render frames fed to AEC3 this session

    // Barge-in support: the currently-playing sink (whichever path is active) so [interrupt] can
    // cut it off immediately, plus a flag the harness checks to know a turn was cut short.
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentMediaPlayer: MediaPlayer? = null
    @Volatile private var playbackContinuation: CancellableContinuation<Unit>? = null
    @Volatile private var interruptRequested = false

    fun setMistralClient(client: MistralClient) {
        this.mistralClient = client
    }

    /**
     * Barge-in: stop whatever Teya is saying right now. Called from the barge-in VAD callback
     * (runs on [WakeWordEngine]'s own background thread) when sustained user speech is detected
     * while she's thinking/speaking — the caller also cancels the in-flight turn's job.
     */
    fun interrupt() {
        interruptRequested = true
        try { currentTrack?.pause() } catch (_: Exception) {}
        try { currentTrack?.flush() } catch (_: Exception) {}
        try {
            currentMediaPlayer?.let { if (it.isPlaying) it.stop() }
        } catch (_: Exception) {}
        currentMediaPlayer = null
        playbackContinuation?.let { if (it.isActive) it.resume(Unit) }
        playbackContinuation = null
    }

    /** Non-destructive peek — used inside a speaking loop to stop queuing more sentences. */
    fun isInterrupted(): Boolean = interruptRequested

    /** True if speech was cut short by [interrupt] since the last call; clears the flag. */
    fun consumeInterrupted(): Boolean {
        val was = interruptRequested
        interruptRequested = false
        return was
    }

    fun startListening(onWakeWord: () -> Unit, onBargeIn: () -> Unit) {
        Log.d("VoicePipeline", "Wake word detection started")
        this.wakeWordCallback = onWakeWord
        this.bargeInCallback = onBargeIn
        wakeWordActive = true
        wakeWordEngine.start()
    }

    private fun onWakeWord() {
        Log.d("VoicePipeline", "Wake word detected!")
        wakeWordCallback?.invoke()
    }

    private fun onBargeIn() {
        Log.d("VoicePipeline", "Barge-in speech detected!")
        bargeInCallback?.invoke()
    }

    /**
     * Arm/disarm real barge-in detection. Call with `true` right before Teya starts
     * thinking/speaking so recognized user speech can interrupt her, and `false` once she's done
     * (or while capturing an actual command, where the wake-word engine is paused anyway).
     *
     * Arming creates a fresh [SileroVad] instance (its RNN hidden state must start clean per
     * window) and starts feeding it [WakeWordEngine]'s raw chunks; disarming closes it.
     */
    fun setBargeInArmed(armed: Boolean) {
        if (armed) {
            synchronized(vadLock) {
                if (sileroVad != null) return // already armed
                bargeInFired = false
                vadFrameBuffer = ShortArray(0)
                vadChunkCounter = 0
                vadPeakConfidence = 0f
                vadPeakRawAmplitude = 0
                try {
                    // Self-echo is handled structurally (forwardArmedChunk's currentTrack/
                    // currentMediaPlayer gate), not by threshold, so this sits close to Silero's
                    // own recommended 0.5 default — nudged up since the remaining risk is ambient
                    // room noise during the gaps, not Teya's own voice. speechDurationMs is kept
                    // short since the listening window itself is brief (HarnessService.BARGE_IN_GAP_MS).
                    sileroVad = SileroVad(context, threshold = 0.7f, speechDurationMs = 50, silenceDurationMs = 300)
                    wakeWordEngine.bargeInArmed = true
                    Log.d("VoicePipeline", "Barge-in: armed (local Silero VAD)")
                } catch (e: Exception) {
                    Log.e("VoicePipeline", "Barge-in: failed to init SileroVad, staying disarmed", e)
                    sileroVad = null
                }
            }
        } else {
            wakeWordEngine.bargeInArmed = false
            synchronized(vadLock) {
                sileroVad?.close()
                sileroVad = null
            }
        }
    }

    /**
     * Starts the whole-session [NativeAec3] instance. Call once near the top of
     * [com.teya.agent.harness.HarnessService.runConversation] — deliberately independent of
     * [setBargeInArmed]'s per-turn arm/disarm (see [aec3]'s doc comment above): mirroring
     * [SileroVad]'s per-turn construct/close would force every turn, not just the one right after
     * an interruption, to re-pay AEC3's convergence window.
     */
    fun startAecSession() {
        synchronized(aecLock) {
            if (aec3 != null) return // already started
            renderResampler = Resampler()
            renderFrameBuffer = ShortArray(0)
            firstRenderFrameAtMs = 0L
            renderFrameCount = 0
            try {
                aec3 = NativeAec3()
                Log.d("VoicePipeline", "AEC3: session started")
            } catch (e: Exception) {
                Log.e("VoicePipeline", "AEC3: failed to init NativeAec3, staying without echo cancellation", e)
                aec3 = null
            }
        }
    }

    /** Ends the whole-session [NativeAec3] instance. Call once in [runConversation]'s `finally`. */
    fun endAecSession() {
        synchronized(aecLock) {
            aec3?.close()
            aec3 = null
        }
    }

    /**
     * Resamples an arriving 24kHz TTS chunk to 16kHz and feeds [NativeAec3.FRAME_SIZE]-sized
     * pieces to [NativeAec3.analyzeRender], buffering any leftover remainder (chunk lengths don't
     * divide evenly) — the same leftover-buffer reassembly pattern [forwardArmedChunk] uses for
     * Silero's frames. Session-scoped like [aec3] itself: runs for every sentence played via
     * [streamToSpeaker], independent of [setBargeInArmed]'s per-turn state.
     */
    private fun feedRenderToAec3(chunk24k: ShortArray) {
        synchronized(aecLock) {
            val aec = aec3 ?: return
            val resampled = renderResampler.resample(chunk24k)
            if (resampled.isEmpty()) return

            renderFrameBuffer += resampled
            var offset = 0
            while (renderFrameBuffer.size - offset >= NativeAec3.FRAME_SIZE) {
                val frame = renderFrameBuffer.copyOfRange(offset, offset + NativeAec3.FRAME_SIZE)
                offset += NativeAec3.FRAME_SIZE
                aec.analyzeRender(frame)
                if (firstRenderFrameAtMs == 0L) firstRenderFrameAtMs = System.currentTimeMillis()
                renderFrameCount++
            }
            renderFrameBuffer = if (offset > 0) renderFrameBuffer.copyOfRange(offset, renderFrameBuffer.size) else renderFrameBuffer

            // Diagnostic marker for manual logcat verification (~1s of render audio @16kHz, since
            // FRAME_SIZE=160 samples=10ms): confirms real TTS audio is reaching analyzeRender
            // while Teya speaks, without a device-instrumented test seam (see Phase 2 notes on why
            // MistralClient isn't fakeable here).
            if (renderFrameCount > 0 && renderFrameCount % 100 == 0) {
                Log.d("VoicePipeline", "AEC3: render frames fed to analyzeRender this session = $renderFrameCount")
            }
        }
    }

    /**
     * Second frame-reassembly stage (Plan B, Phase 3): splits [forwardArmedChunk]'s raw
     * 1280-sample mic chunk into [NativeAec3.FRAME_SIZE]-sized (160-sample) pieces, runs each
     * through [NativeAec3.processCapture] to strip Teya's own echo out of the mic signal *before*
     * the existing gain + [SileroVad] reassembly ever sees it, and re-concatenates the cleaned
     * pieces (buffering any leftover remainder in [aecCaptureBuffer] — defensive: WakeWordEngine's
     * chunk size divides evenly by [NativeAec3.FRAME_SIZE] today, but this doesn't assume that
     * stays true, mirroring [vadFrameBuffer]'s reassembly pattern).
     *
     * Falls back to the raw [chunk] unchanged when [aec3] is null (no active conversation
     * session, or somehow not constructed) — this phase must not regress behavior when AEC3 isn't
     * active for any reason. Wrapped in [aecLock] for the same reason [feedRenderToAec3] is: reads
     * the [aec3] field, which [endAecSession] can null out from a different thread.
     */
    private fun cleanCaptureChunk(chunk: ShortArray): ShortArray {
        synchronized(aecLock) {
            val aec = aec3 ?: return chunk

            aecCaptureBuffer += chunk
            var cleaned = ShortArray(0)
            var offset = 0
            while (aecCaptureBuffer.size - offset >= NativeAec3.FRAME_SIZE) {
                val frame = aecCaptureBuffer.copyOfRange(offset, offset + NativeAec3.FRAME_SIZE)
                offset += NativeAec3.FRAME_SIZE
                cleaned += aec.processCapture(frame)
            }
            aecCaptureBuffer = if (offset > 0) aecCaptureBuffer.copyOfRange(offset, aecCaptureBuffer.size) else aecCaptureBuffer
            return cleaned
        }
    }

    /**
     * Phase 4 convergence lead-in gate: has enough render-only time elapsed since the session's
     * first render frame ([firstRenderFrameAtMs], set once by [feedRenderToAec3] and never reset
     * per turn) to trust barge-in detection? Returns `false` (don't trust yet) if AEC3 hasn't
     * rendered a single frame this session at all. Read here from [WakeWordEngine]'s capture
     * thread; [firstRenderFrameAtMs] is written from the render/TTS thread, hence `@Volatile`
     * there for cross-thread visibility.
     */
    private fun leadInElapsed(): Boolean {
        val startedAt = firstRenderFrameAtMs
        if (startedAt == 0L) return false
        return System.currentTimeMillis() - startedAt >= AEC3_LEAD_IN_MS
    }

    /**
     * Runs on [WakeWordEngine]'s capture thread. Reassembles the 1280-sample mic chunks into
     * [SileroVad.FRAME_SIZE]-sample frames (they don't divide evenly) and checks each
     * synchronously — cheap enough to do inline, no async hand-off needed. Applies the same
     * software gain [WakeWordEngine] applies before its own classifier: this device has no
     * hardware AGC, so the raw signal is otherwise too quiet for reliable detection.
     *
     * Historically skipped processing entirely while [currentTrack]/[currentMediaPlayer] was
     * non-null (our own TTS actively coming out of the speaker) — the self-echo fix for this
     * device's over-suppressing `AcousticEchoCanceler` (see WakeWordEngine). Phase 4 (Plan B)
     * narrows that: [currentMediaPlayer] (the `playMp3` fallback, no AEC3 coverage — see "What
     * We're NOT Doing") still always gates, but [currentTrack] (the `streamToSpeaker` path) is
     * exempted whenever [aec3] is actively cleaning the signal and [AEC3_BARGE_IN_ENABLED] is on,
     * which is what actually enables mid-sentence interruption. That exemption is itself gated by
     * [leadInElapsed] — AEC3 keeps converging via [cleanCaptureChunk] regardless, but the result
     * isn't trusted (passed to Silero / allowed to fire [bargeInFired]) until the session-wide
     * lead-in has elapsed, per Plan A's double-talk-from-frame-zero finding. Kill-switch: when
     * [AEC3_BARGE_IN_ENABLED] is `false`, this function behaves exactly as it did before Phase 4.
     */
    private fun forwardArmedChunk(chunk: ShortArray) {
        if (bargeInFired) return // already interrupted this window; wait for disarm

        // Both the self-echo-gate exemption and the lead-in gate below only apply when the
        // feature is on AND a session-scoped AEC3 instance is actually active — otherwise this
        // falls straight through to the exact pre-Plan-B gap-gated behavior (see
        // AEC3_BARGE_IN_ENABLED's kill-switch doc comment).
        val aecActive = AEC3_BARGE_IN_ENABLED && aec3 != null

        if (currentMediaPlayer != null) return // mp3 fallback: no AEC3 coverage, always gap-gated
        if (currentTrack != null && !aecActive) return // streaming path: gated unless AEC3 is actively cleaning it

        // Diagnostic peak is measured on the true raw mic chunk (pre-AEC3, pre-gain) — its purpose
        // is "does the mic itself have signal at all," which is orthogonal to echo cancellation.
        var rawPeak = 0
        for (s in chunk) {
            val abs = kotlin.math.abs(s.toInt())
            if (abs > rawPeak) rawPeak = abs
        }
        if (rawPeak > vadPeakRawAmplitude) vadPeakRawAmplitude = rawPeak

        // Phase 3: run the raw chunk through AEC3's capture side first, so Silero below sees
        // echo-cancelled audio instead of the raw gained signal. cleanCaptureChunk() falls back to
        // the unmodified chunk when aec3 is null (no active session). Always runs — independent of
        // the kill-switch — so AEC3 keeps converging even while its result isn't yet trusted below.
        val cleanedChunk = cleanCaptureChunk(chunk)

        // Phase 4 convergence lead-in gate: don't pass the cleaned signal to Silero / allow
        // bargeInFired to be set until the session-wide lead-in has elapsed since the very first
        // render frame (not reset per turn — see leadInElapsed). AEC3 above still keeps converging
        // during the lead-in either way; this only withholds trust in the *result*.
        if (aecActive && !leadInElapsed()) return

        val gained = ShortArray(cleanedChunk.size) { i ->
            (cleanedChunk[i] * BARGE_IN_GAIN).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }

        synchronized(vadLock) {
            val vad = sileroVad ?: return
            vadFrameBuffer += gained

            var offset = 0
            while (vadFrameBuffer.size - offset >= SileroVad.FRAME_SIZE) {
                val frame = vadFrameBuffer.copyOfRange(offset, offset + SileroVad.FRAME_SIZE)
                offset += SileroVad.FRAME_SIZE
                val isSpeech = vad.isSpeech(frame)
                if (vad.lastConfidence > vadPeakConfidence) vadPeakConfidence = vad.lastConfidence
                if (isSpeech) {
                    bargeInFired = true
                    Log.d("VoicePipeline", "Barge-in: speech detected (confidence=${vad.lastConfidence})")
                    onBargeIn()
                    break
                }
            }
            vadFrameBuffer = if (offset > 0) vadFrameBuffer.copyOfRange(offset, vadFrameBuffer.size) else vadFrameBuffer
        }

        // Diagnostic: peak VAD confidence every ~25 mic chunks (~2s), so we can see whether the
        // model is responding to real speech at all, independent of whether it crosses threshold.
        if (++vadChunkCounter % 25 == 0) {
            Log.d(
                "VoicePipeline",
                "Barge-in: peak VAD confidence (last ~2s) = $vadPeakConfidence, " +
                    "peak RAW (pre-gain) amplitude = $vadPeakRawAmplitude / 32767"
            )
            vadPeakConfidence = 0f
            vadPeakRawAmplitude = 0
        }
    }

    /**
     * Records a spoken command using simple energy-based VAD (stops after a short trailing
     * silence, or gives up after [maxInitialSilenceMs] if no speech starts), then transcribes it
     * via Voxtral. The caller must pause the wake-word recorder first ([pauseWakeWord]) to avoid
     * microphone contention. Returns "" on any failure or silence.
     */
    suspend fun listenForCommand(maxInitialSilenceMs: Int = DEFAULT_INITIAL_SILENCE_MS, contextBias: List<String> = emptyList()): String =
        withContext(Dispatchers.IO) {
            val client = mistralClient ?: run {
                Log.e("VoicePipeline", "MistralClient not set, cannot transcribe")
                return@withContext ""
            }
            try {
                val wavFile = recordWithVad(maxInitialSilenceMs) ?: run {
                    Log.d("VoicePipeline", "No command audio captured")
                    return@withContext ""
                }
                client.transcribe(wavFile, contextBias)
            } catch (e: Exception) {
                Log.e("VoicePipeline", "Error during STT", e)
                ""
            }
        }

    /** Pause the always-on wake-word recorder to free the mic for command recording. */
    fun pauseWakeWord() {
        if (wakeWordActive) wakeWordEngine.stop()
    }

    /** Resume wake-word listening (e.g. after a conversation ends). */
    fun resumeWakeWord() {
        if (wakeWordActive) wakeWordEngine.start()
    }

    @SuppressLint("MissingPermission")
    private fun recordWithVad(maxInitialSilenceMs: Int): File? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e("VoicePipeline", "Invalid AudioRecord buffer size: $minBuffer")
            return null
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VoicePipeline", "AudioRecord failed to initialize")
            recorder.release()
            return null
        }

        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(FRAME_SAMPLES)
        var speechStarted = false
        var trailingSilenceMs = 0
        var elapsedMs = 0

        try {
            recorder.startRecording()
            Log.d("VoicePipeline", "Recording command…")
            while (elapsedMs < MAX_RECORDING_MS) {
                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) continue
                elapsedMs += FRAME_MS

                var sumSquares = 0.0
                for (i in 0 until read) {
                    val s = frame[i].toDouble()
                    sumSquares += s * s
                }
                val rms = sqrt(sumSquares / read)
                val isSpeech = rms > SILENCE_RMS_THRESHOLD

                if (isSpeech) {
                    speechStarted = true
                    trailingSilenceMs = 0
                } else if (speechStarted) {
                    trailingSilenceMs += FRAME_MS
                }

                // Only keep audio once speech has begun (trims leading silence).
                if (speechStarted) {
                    val bytes = ByteArray(read * 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().put(frame, 0, read)
                    pcm.write(bytes)
                }

                if (speechStarted && trailingSilenceMs >= TRAILING_SILENCE_MS) break
                if (!speechStarted && elapsedMs >= maxInitialSilenceMs) break
            }
        } catch (e: Exception) {
            Log.e("VoicePipeline", "Recording error", e)
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }

        val pcmBytes = pcm.toByteArray()
        if (pcmBytes.isEmpty()) return null

        val wavFile = File(context.cacheDir, "command.wav")
        writeWav(wavFile, pcmBytes)
        Log.d("VoicePipeline", "Captured ${pcmBytes.size} bytes of PCM")
        return wavFile
    }

    /** Writes 16 kHz / mono / 16-bit PCM to a canonical 44-byte-header WAV file. */
    private fun writeWav(file: File, pcm: ByteArray) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataLen = pcm.size
        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataLen)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)                          // PCM fmt chunk size
            header.putShort(1.toShort())               // PCM format
            header.putShort(channels.toShort())
            header.putInt(SAMPLE_RATE)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataLen)
            out.write(header.array())
            out.write(pcm)
        }
    }

    /**
     * Speaks [text], preferring the low-latency streaming PCM path and falling back to whole-clip
     * mp3 if streaming yields nothing. Returns `true` if this specific call streamed (the
     * AEC3-covered path), `false` if it fell back to `playMp3` (no AEC3 coverage) — Phase 4's
     * per-sentence signal for whether `HarnessService.respond()`'s inter-sentence gap is still
     * needed after this sentence (streaming vs. mp3 fallback is chosen independently per call, not
     * once per turn — see the plan's Current State Analysis). When nothing was actually spoken
     * (blank text, or no client configured), returns `true`: no audio played, so there's nothing
     * for a gap to protect against.
     */
    suspend fun textToSpeech(text: String): Boolean {
        if (text.isBlank()) return true
        interruptRequested = false // fresh attempt — any earlier interrupt has already been handled
        val client = mistralClient ?: run {
            Log.e("VoicePipeline", "MistralClient not set, cannot speak")
            return true
        }
        // Prefer low-latency streaming PCM; fall back to whole-clip mp3 if streaming yields nothing.
        if (!streamToSpeaker(client, text)) {
            Log.w("VoicePipeline", "Streaming TTS failed; falling back to mp3")
            playMp3(client, text)
            return false
        }
        return true
    }

    /**
     * Streams Voxtral PCM (float32/24kHz/mono) into an AudioTrack as it arrives. Converts each
     * chunk to int16 (universally supported; float32 output isn't) and routes as USAGE_MEDIA.
     * Calls stop() before draining so short clips (e.g. "Yes?") play out.
     *
     * Tried USAGE_VOICE_COMMUNICATION + AudioManager.MODE_IN_COMMUNICATION + forced speakerphone
     * here as a barge-in/AEC experiment — reverted twice now. First
     * try (USAGE_VOICE_COMMUNICATION alone) silently rerouted playback to the earpiece. Second
     * try added AudioManager.mode/isSpeakerphoneOn management, but `audioManager.mode` read back
     * as MODE_NORMAL (0) immediately after being set to MODE_IN_COMMUNICATION — a silent no-op,
     * most likely because this app never declared android.permission.MODIFY_AUDIO_SETTINGS and/or
     * doesn't hold audio focus, both of which setMode()/isSpeakerphoneOn require. Audio was worse
     * than the first attempt (mic captured pure digital silence, not just quiet). This whole
     * avenue needs the missing permission plus real audio-focus handling before it's worth
     * retrying — a bigger change than a quick fix, and it broke real (audible) TTS twice, which
     * matters more than barge-in.
     */
    private suspend fun streamToSpeaker(client: MistralClient, text: String): Boolean =
        withContext(Dispatchers.IO) {
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    TTS_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(TTS_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, TTS_SAMPLE_RATE)) // ~0.5s (2 bytes/frame)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = audioTrack
                currentTrack = audioTrack
                audioTrack.play()

                var totalFrames = 0
                val got = client.streamSpeechPcm(text) { floats ->
                    if (!interruptRequested) {
                        val shorts = ShortArray(floats.size)
                        for (i in floats.indices) {
                            val v = floats[i] * 32767f
                            shorts[i] = when {
                                v >= 32767f -> Short.MAX_VALUE
                                v <= -32768f -> Short.MIN_VALUE
                                else -> v.toInt().toShort()
                            }
                        }
                        // Feed AEC3's farend reference before writing to the speaker, so render
                        // analysis for this chunk never depends on whether the write below
                        // succeeds (Phase 2: the two are deliberately decoupled).
                        feedRenderToAec3(shorts)
                        audioTrack.write(shorts, 0, shorts.size, AudioTrack.WRITE_BLOCKING)
                        totalFrames += shorts.size
                    }
                }
                if (interruptRequested) return@withContext true // stopped on purpose, not a failure
                if (!got || totalFrames == 0) return@withContext false

                // stop() drains the buffered tail in MODE_STREAM; poll until it plays out (with a
                // no-progress bail so we never hang if the head stalls short of the end).
                try { audioTrack.stop() } catch (_: Exception) {}
                var guard = 0; var last = -1; var stable = 0
                while (guard++ < 1000 && !interruptRequested) {
                    val pos = audioTrack.playbackHeadPosition
                    if (pos >= totalFrames) break
                    if (pos == last) { if (++stable > 25) break } else { stable = 0; last = pos }
                    delay(20)
                }
                Log.d("VoicePipeline", "Playback finished (streamed $totalFrames frames)")
                true
            } catch (e: Exception) {
                Log.e("VoicePipeline", "AudioTrack streaming error", e)
                false
            } finally {
                try { track?.release() } catch (_: Exception) {}
                currentTrack = null
            }
        }

    /** Whole-clip fallback: decode base64 mp3 and play from memory (no disk). */
    private suspend fun playMp3(client: MistralClient, text: String) {
        val audio = withContext(Dispatchers.IO) { client.synthesizeSpeech(text) }
        if (audio == null || audio.isEmpty()) {
            Log.e("VoicePipeline", "TTS produced no audio, skipping playback")
            return
        }
        if (interruptRequested) return
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val mediaPlayer = MediaPlayer()
                currentMediaPlayer = mediaPlayer
                playbackContinuation = continuation
                try {
                    mediaPlayer.setDataSource(ByteArrayMediaDataSource(audio))
                    mediaPlayer.setOnCompletionListener {
                        Log.d("VoicePipeline", "Playback finished")
                        it.release()
                        currentMediaPlayer = null
                        playbackContinuation = null
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    mediaPlayer.setOnErrorListener { mp, what, extra ->
                        Log.e("VoicePipeline", "MediaPlayer Error: $what, $extra")
                        mp.release()
                        currentMediaPlayer = null
                        playbackContinuation = null
                        if (continuation.isActive) continuation.resume(Unit)
                        true
                    }
                    mediaPlayer.prepare()
                    mediaPlayer.start()
                    continuation.invokeOnCancellation {
                        try {
                            mediaPlayer.stop()
                            mediaPlayer.release()
                        } catch (e: Exception) {
                            // Ignore
                        }
                        currentMediaPlayer = null
                        playbackContinuation = null
                    }
                } catch (e: Exception) {
                    Log.e("VoicePipeline", "Error playing TTS", e)
                    try { mediaPlayer.release() } catch (_: Exception) {}
                    currentMediaPlayer = null
                    playbackContinuation = null
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    fun stop() {
        wakeWordActive = false
        wakeWordEngine.stop()
        sileroVad?.close()
        sileroVad = null
    }

    /** Lets MediaPlayer read mp3 bytes straight from memory — no temp file on disk. */
    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }
}
