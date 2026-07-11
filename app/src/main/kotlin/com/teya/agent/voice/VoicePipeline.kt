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
import com.teya.agent.voice.aec.WebViewAecHost
import com.teya.agent.voice.vad.SileroVad
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        // Must match SileroVad.FRAME_SIZE (512 samples @ 16kHz = 32ms) — recordWithVad feeds these
        // frames straight to SileroVad now instead of thresholding raw energy.
        private const val FRAME_SAMPLES = 512
        private const val FRAME_MS = FRAME_SAMPLES * 1000 / SAMPLE_RATE // 32ms
        // Fallback only, if SileroVad fails to construct in recordWithVad — see its doc comment.
        // Real recordings were hitting MAX_RECORDING_MS instead of stopping on trailing silence
        // with this threshold, since real room noise doesn't reliably stay under it.
        private const val SILENCE_RMS_THRESHOLD = 700.0
        private const val TRAILING_SILENCE_MS = 800                       // silence that ends a command
        private const val DEFAULT_INITIAL_SILENCE_MS = 4000               // give up if nobody speaks (default)
        private const val BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES = SAMPLE_RATE * 3 // ~3s cap — see bargeInAudioBuffer's doc comment
        private const val MAX_RECORDING_MS = 10000                        // hard cap on a single command
        private const val TTS_SAMPLE_RATE = 24000                         // Voxtral PCM output rate
        private const val BARGE_IN_GAIN = 6.0f  // matches WakeWordEngine.INPUT_GAIN — no hardware AGC

        // Kill-switch for AEC3-based continuous mid-sentence barge-in: true = forwardArmedChunk's
        // self-echo gate is exempted for the streamToSpeaker path (once past the convergence
        // lead-in below) and HarnessService.respond() skips its inter-sentence gap for sentences
        // that streamed. false = both revert to the old gap-gated behavior (gate + gap intact),
        // regardless of whether aec3 is constructed/fed.
        //
        // Currently false: on real-device testing, NativeAec3's actual echo suppression is
        // inconsistent — sometimes negligible, occasionally even negative (cleaned signal louder
        // than raw) — causing Teya to self-interrupt on her own voice. Root cause is believed to be
        // AEC3's render/capture frame pairing not producing reliable cancellation on real audio
        // (see docs/roadmap.md for the diagnostic evidence and candidate next steps) rather than a
        // wiring bug — two real wiring bugs (platform AcousticEchoCanceler left enabled during
        // playback, and WakeWordEngine re-enabling it on every mic restart) were found and fixed
        // independently of this switch and are kept regardless of its value.
        const val AEC3_BARGE_IN_ENABLED = false

        // How long after the session's first render frame (firstRenderFrameAtMs) before barge-in
        // detection is trusted. AEC3 does not converge instantly — fed real double-talk from frame
        // zero, its suppression is negligible; given a genuine render-only lead-in first, it
        // converges quickly. This duration adds a safety margin on top of the roughly one-second
        // convergence time measured in isolated testing, since that number was against a clean
        // synthetic signal, not this device's real speaker->mic acoustic path and real voice.
        private const val AEC3_LEAD_IN_MS = 2000L

        // Poll cadence for pacing analyzeRender to actual AudioTrack playback position (see
        // streamToSpeaker's feedPlayedRenderFrames) — matches NativeAec3.FRAME_SIZE's own 10ms
        // granularity at 16kHz, so frames become available to feed about as often as they're sized.
        private const val AEC3_RENDER_POLL_MS = 10L

        // Kill-switch for the WebView/Chromium AEC replacement plan's Phase 1 scaffolding (see
        // thoughts/shared/plans/2026-07-11-webview-chromium-aec-barge-in.md and
        // voice/aec/WebViewAecHost.kt). true = a session-scoped overlay WebView is constructed
        // alongside NativeAec3 (not replacing it yet) and a bridge round-trip latency is measured
        // and logged once per session. No real render/capture audio flows through this WebView yet
        // (Phase 2/3) — this only proves the bidirectional bridge scaffolding. Default false: this
        // must not change any shipped behavior until later phases are validated.
        const val WEBVIEW_AEC_HOST_ENABLED = false

        // Phase 2 kill-switch: routes streamToSpeaker's real TTS playback through the WebView's Web
        // Audio scheduler instead of the local AudioTrack (see streamToSpeakerViaWebView). Only takes
        // effect when WEBVIEW_AEC_HOST_ENABLED is also true (the host must exist to push chunks
        // into) — independent flag so Phase 1's bridge-only test isn't entangled with actually
        // rerouting audible playback. Default false, same reasoning as WEBVIEW_AEC_HOST_ENABLED.
        const val WEBVIEW_RENDER_ENABLED = false

        // Phase 3 kill-switch: routes barge-in speech detection's capture-side audio through the
        // WebView's getUserMedia (Chromium's own echo cancellation — the ~36-41dB confirmed by the
        // original tone spike) instead of NativeAec3's cleanCaptureChunk, feeding the same
        // downstream SileroVad pipeline (see forwardWebViewCapturedChunk). Requires
        // WEBVIEW_AEC_HOST_ENABLED. Still gap-gated like today (no AEC3-style lead-in exemption —
        // that's Phase 4). Default false, same reasoning as the other WebView flags.
        const val WEBVIEW_CAPTURE_ENABLED = false

        // Phase 4 kill-switch: removes forwardWebViewCapturedChunk's self-echo gate during active
        // WebView-rendered playback (webViewRenderActive) and HarnessService.respond()'s
        // BARGE_IN_GAP_MS delay for sentences that streamed via the WebView path — enabling genuine
        // continuous mid-sentence listening, the same thing AEC3_BARGE_IN_ENABLED was meant to do
        // for NativeAec3. Only meaningful when WEBVIEW_AEC_HOST_ENABLED, WEBVIEW_RENDER_ENABLED, and
        // WEBVIEW_CAPTURE_ENABLED are all also true — this flag alone does nothing. currentTrack
        // (AudioTrack render active instead of WebView) and currentMediaPlayer (mp3 fallback) are
        // NOT exempted by this flag: neither has a getUserMedia self-echo reference to cancel
        // against, so they stay gap-gated regardless. Default false, same reasoning as every other
        // WebView flag — this is the phase where a real self-echo false-positive would first show up
        // (everything up through Phase 3 was still gap-gated, so it was never actually exercised).
        const val WEBVIEW_CONTINUOUS_BARGE_IN_ENABLED = false
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
    // Rolling buffer of cleaned (post-AEC3, pre-gain) mic audio captured continuously during the
    // armed window — NOT gated on bargeInFired, unlike the VAD path below, so it keeps covering
    // right up through the moment of interrupt. Exists because forwardArmedChunk previously threw
    // away everything the user said during "speaking" mode (Silero only ever produced a yes/no
    // signal, never kept the audio) — meaning whatever triggered/accompanied the interrupt was
    // lost, and the fresh AudioRecord listenForCommand starts afterward only picks up speech from
    // ~750-800ms later (WakeWordEngine's mic must fully stop first — Android can't run two
    // AudioRecords at once), producing STT fragments like "works." or "Now," instead of the whole
    // sentence. Capped at BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES so a long Teya response before any
    // interrupt doesn't grow this unboundedly. See [consumeBargeInAudio].
    private var bargeInAudioBuffer = ShortArray(0)
    // Guards sileroVad's create/use/close: forwardArmedChunk runs on WakeWordEngine's capture
    // thread while setBargeInArmed(false) runs on the harness's coroutine thread — without this,
    // a disarm's close() can race a concurrent isSpeech() call and crash natively (use-after-free
    // on the ONNX session), which is exactly what happened live (silent process restart, no JVM
    // exception — the signature of a native crash, not a Kotlin one).
    private val vadLock = Any()
    private var vadChunkCounter = 0    // diagnostic
    private var vadPeakConfidence = 0f // diagnostic
    private var vadPeakRawAmplitude = 0 // diagnostic — pre-gain, to check the mic itself has signal
    // Diagnostic: peak of AEC3's *cleaned* output, pre-gain, alongside vadPeakRawAmplitude above —
    // comparing the two tells us whether NativeAec3 is suppressing Teya's own echo at all in the
    // current acoustic environment (as opposed to a downstream gain/threshold issue), without
    // needing a second, state-corrupting Silero pass.
    private var vadPeakCleanedAmplitude = 0

    // AEC3 capture-side leftover buffer: reassembles forwardArmedChunk's raw 1280-sample mic
    // chunks into NativeAec3.FRAME_SIZE (160-sample) pieces, same pattern as vadFrameBuffer above.
    // Only ever touched from forwardArmedChunk, which always runs on WakeWordEngine's single
    // dedicated capture thread (never concurrently with itself), so the buffer itself needs no
    // lock of its own — access to the aec3 field alongside it is still guarded by aecLock inside
    // cleanCaptureChunk(), for the same reason streamToSpeaker's feedPlayedRenderFrames guards its analyzeRender calls:
    // aec3 is written from the harness's coroutine thread (startAecSession()/endAecSession()), so
    // a session-end close() could otherwise race a concurrent processCapture() call on this thread.
    private var aecCaptureBuffer = ShortArray(0)

    // AEC3 render feed: unlike sileroVad above, aec3's lifecycle is deliberately NOT per-turn — it
    // spans the whole conversation session (constructed/closed once by
    // HarnessService.runConversation() via startAecSession()/endAecSession()), so its adaptive
    // filter's convergence cost (fed real double-talk from frame zero, suppression is negligible;
    // it converges quickly given a genuine render-only lead-in first) is paid once per session, not
    // once per turn. A dedicated aecLock (not vadLock) guards this independently-scoped resource:
    // analyzeRender runs on streamToSpeaker's IO-dispatcher thread (and its render-pacing poller
    // coroutine — see feedPlayedRenderFrames) while endAecSession() runs on the harness's coroutine
    // thread, so a session-end close() could otherwise race a concurrent analyzeRender call,
    // mirroring the exact race vadLock already guards against for sileroVad.
    //
    // renderResampler is session-scoped (not per-utterance) purely for its own resampling
    // continuity across sentence boundaries; the actual buffering of not-yet-played resampled
    // samples is per-utterance local state inside streamToSpeaker, since it's keyed off
    // AudioTrack.playbackHeadPosition, which resets to 0 with each fresh AudioTrack.
    @Volatile private var aec3: NativeAec3? = null
    private val aecLock = Any()

    // Phase 1/2 scaffolding for the WebView/Chromium AEC replacement (see WEBVIEW_AEC_HOST_ENABLED's
    // doc comment) — session-scoped like aec3 above. Only ever constructed/torn down from the
    // harness's coroutine thread (startAecSession/endAecSession), and Phase 2's real render calls
    // (streamToSpeakerViaWebView) also only run from that same conversation's coroutine chain (never
    // concurrently with a session start/end), so unlike aec3 this doesn't need its own lock yet —
    // that changes once Phase 3 wires real capture through it from WakeWordEngine's thread.
    private var webViewAecHost: WebViewAecHost? = null
    private var renderResampler = Resampler()
    // Set once — only if still 0L — the first time analyzeRender is actually fed a frame this
    // session. Written from the render/TTS thread here; read from the capture thread by the
    // convergence-lead-in gate below, hence @Volatile for cross-thread visibility.
    @Volatile private var firstRenderFrameAtMs: Long = 0L
    private var renderFrameCount = 0 // diagnostic: render frames fed to AEC3 this session

    // Barge-in support: the currently-playing sink (whichever path is active) so [interrupt] can
    // cut it off immediately, plus a flag the harness checks to know a turn was cut short.
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentMediaPlayer: MediaPlayer? = null
    @Volatile private var playbackContinuation: CancellableContinuation<Unit>? = null
    @Volatile private var interruptRequested = false
    // Phase 2's WebView render path has no AudioTrack of its own for forwardArmedChunk's self-echo
    // gate (currentTrack/currentMediaPlayer above) to key off — this fills that role instead, read
    // on WakeWordEngine's capture thread and written from streamToSpeakerViaWebView's IO thread,
    // hence @Volatile. Phase 3 hasn't wired real capture-side echo cancellation for this path yet,
    // so it gates unconditionally (like currentMediaPlayer), not AEC3-exempted (like currentTrack).
    @Volatile private var webViewRenderActive = false

    fun setMistralClient(client: MistralClient) {
        this.mistralClient = client
    }

    /**
     * Synthesizes and plays a short pure-tone cue (fire-and-forget — runs its own short-lived
     * thread so callers, including [interrupt] on the capture thread, never block). Wall-mounted,
     * screen-not-always-visible device: the orb's listening/thinking/speaking state changes aren't
     * visible unless you're looking at it, so key transitions get an audible cue too. Uses
     * USAGE_MEDIA (same as [streamToSpeaker]'s TTS output, already confirmed clearly audible at a
     * distance) rather than USAGE_ASSISTANCE_SONIFICATION — the latter maps to a separate
     * system-sound volume stream that was too quiet to hear reliably even at 15cm.
     */
    private fun playTone(freqHz: Double, durationMs: Int, volume: Float = 0.9f) {
        Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 16000
                val numSamples = sampleRate * durationMs / 1000
                val fadeSamples = (numSamples / 10).coerceAtLeast(1) // 10% fade in/out — avoids a click
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val envelope = when {
                        i < fadeSamples -> i.toDouble() / fadeSamples
                        i > numSamples - fadeSamples -> (numSamples - i).toDouble() / fadeSamples
                        else -> 1.0
                    }
                    val angle = 2.0 * Math.PI * i * freqHz / sampleRate
                    buffer[i] = (Math.sin(angle) * envelope * volume * Short.MAX_VALUE).toInt().toShort()
                }
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buffer, 0, buffer.size)
                track.play()
                Thread.sleep((durationMs + 30).toLong()) // let it finish before releasing
            } catch (e: Exception) {
                Log.e("VoicePipeline", "Failed to play tone cue", e)
            } finally {
                try { track?.release() } catch (_: Exception) {}
            }
        }.start()
    }

    /** Cue: Teya has started listening for your command — see [playTone]'s doc comment. */
    fun playListeningChime() = playTone(freqHz = 880.0, durationMs = 100)

    /** Cue: a barge-in interrupt just registered and stopped her — see [playTone]'s doc comment. */
    fun playInterruptChime() = playTone(freqHz = 440.0, durationMs = 80)

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
        // Phase 2's WebView render path has no local AudioTrack/MediaPlayer to pause/stop above —
        // stopPlayback() suspends (hops to the main thread), so it can't run inline on whatever
        // thread interrupt() itself is called from (WakeWordEngine's capture thread). Fire-and-forget
        // on its own thread, same pattern as playTone().
        if (webViewRenderActive) {
            val host = webViewAecHost
            if (host != null) {
                Thread { runBlocking { host.stopPlayback() } }.start()
            }
        }
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
                bargeInAudioBuffer = ShortArray(0)
                vadChunkCounter = 0
                vadPeakConfidence = 0f
                vadPeakRawAmplitude = 0
                vadPeakCleanedAmplitude = 0
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
     * Returns and clears [bargeInAudioBuffer] — whatever cleaned mic audio was captured during the
     * armed window, up to ~3s of it. Call right after a barge-in interrupt, before the next
     * [listenForCommand], and pass the result as [listenForCommand]'s `prefixAudio` so what the
     * user was already saying when they interrupted carries through into that recording instead of
     * being silently discarded (see [bargeInAudioBuffer]'s doc comment for why that was happening).
     */
    fun consumeBargeInAudio(): ShortArray {
        synchronized(vadLock) {
            val audio = bargeInAudioBuffer
            bargeInAudioBuffer = ShortArray(0)
            return audio
        }
    }

    /**
     * Starts the whole-session [NativeAec3] instance. Call once near the top of
     * [com.teya.agent.harness.HarnessService.runConversation] — deliberately independent of
     * [setBargeInArmed]'s per-turn arm/disarm (see [aec3]'s doc comment above): mirroring
     * [SileroVad]'s per-turn construct/close would force every turn, not just the one right after
     * an interruption, to re-pay AEC3's convergence window.
     */
    suspend fun startAecSession() {
        synchronized(aecLock) {
            if (aec3 == null) {
                renderResampler = Resampler()
                firstRenderFrameAtMs = 0L
                renderFrameCount = 0
                try {
                    aec3 = NativeAec3()
                    // The platform AcousticEchoCanceler previously never mattered during playback
                    // (forwardArmedChunk always returned early then) but now runs concurrently with
                    // continuous mid-sentence listening, where it was found to over-suppress the
                    // captured signal to near-silence before our own AEC3/Silero ever see it (this
                    // device's platform AEC is documented as unreliable — see
                    // WakeWordEngine.enableAudioEffects). Disable it for the duration of our own
                    // session-scoped AEC3 instance, which is the intended replacement.
                    wakeWordEngine.setPlatformAecEnabled(false)
                    Log.d("VoicePipeline", "AEC3: session started (platform AEC disabled)")
                } catch (e: Exception) {
                    Log.e("VoicePipeline", "AEC3: failed to init NativeAec3, staying without echo cancellation", e)
                    aec3 = null
                }
            }
        }

        // Phase 1 scaffolding, alongside (not replacing) NativeAec3 above — see
        // WEBVIEW_AEC_HOST_ENABLED's doc comment. Deliberately outside aecLock: this doesn't touch
        // any state aecLock guards, and start()/measureRoundTripLatency() suspend (hop to the main
        // thread), which synchronized{} can't do.
        if (WEBVIEW_AEC_HOST_ENABLED && webViewAecHost == null) {
            // Phase 3's capture callback fires on whatever thread the WebView's JS bridge invokes
            // it from (see forwardWebViewCapturedChunk's doc comment) — passed unconditionally so
            // the host always CAN deliver chunks; whether it actually does is gated separately by
            // WEBVIEW_CAPTURE_ENABLED below (startCapture() is only called when that's on).
            val host = WebViewAecHost(context, onCaptureChunk = { chunk -> forwardWebViewCapturedChunk(chunk) })
            webViewAecHost = host
            host.start()
            val latencyMs = host.measureRoundTripLatency()
            Log.d(
                "VoicePipeline",
                "WebViewAecHost: bridge round-trip latency = " +
                    (latencyMs?.let { "${it}ms" } ?: "no response (overlay permission missing, or host failed to start)")
            )
            if (WEBVIEW_CAPTURE_ENABLED) {
                host.startCapture(SAMPLE_RATE)
            }
        }
    }

    /** Ends the whole-session [NativeAec3] instance. Call once in [runConversation]'s `finally`. */
    suspend fun endAecSession() {
        synchronized(aecLock) {
            aec3?.close()
            aec3 = null
            wakeWordEngine.setPlatformAecEnabled(true)
        }
        webViewAecHost?.let { host ->
            if (WEBVIEW_CAPTURE_ENABLED) host.stopCapture()
            host.stop()
        }
        webViewAecHost = null
    }

    /**
     * Second frame-reassembly stage: splits [forwardArmedChunk]'s raw 1280-sample mic chunk into
     * [NativeAec3.FRAME_SIZE]-sized (160-sample) pieces, runs each through
     * [NativeAec3.processCapture] to strip Teya's own echo out of the mic signal *before* the
     * existing gain + [SileroVad] reassembly ever sees it, and re-concatenates the cleaned pieces
     * (buffering any leftover remainder in [aecCaptureBuffer] — defensive: WakeWordEngine's chunk
     * size divides evenly by [NativeAec3.FRAME_SIZE] today, but this doesn't assume that stays
     * true, mirroring [vadFrameBuffer]'s reassembly pattern).
     *
     * Falls back to the raw [chunk] unchanged when [aec3] is null (no active conversation
     * session, or somehow not constructed) — must not regress behavior when AEC3 isn't active for
     * any reason. Wrapped in [aecLock] for the same reason streamToSpeaker's render pacing is: reads the
     * [aec3] field, which [endAecSession] can null out from a different thread.
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
     * Convergence lead-in gate: has enough render-only time elapsed since the session's first
     * render frame ([firstRenderFrameAtMs], set once by streamToSpeaker's render pacing and never reset per
     * turn) to trust barge-in detection? Returns `false` (don't trust yet) if AEC3 hasn't rendered
     * a single frame this session at all. Read here from [WakeWordEngine]'s capture thread;
     * [firstRenderFrameAtMs] is written from the render/TTS thread, hence `@Volatile` there for
     * cross-thread visibility.
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
     * device's over-suppressing `AcousticEchoCanceler` (see WakeWordEngine). That gate is narrowed
     * when AEC3-based barge-in is active: [currentMediaPlayer] (the `playMp3` fallback, no AEC3
     * coverage) still always gates, but [currentTrack] (the `streamToSpeaker` path) is exempted
     * whenever [aec3] is actively cleaning the signal and [AEC3_BARGE_IN_ENABLED] is on, which is
     * what enables mid-sentence interruption. That exemption is itself gated by [leadInElapsed] —
     * AEC3 keeps converging via [cleanCaptureChunk] regardless, but the result isn't trusted
     * (passed to Silero / allowed to fire [bargeInFired]) until the session-wide lead-in has
     * elapsed. Kill-switch: when [AEC3_BARGE_IN_ENABLED] is `false`, this function behaves exactly
     * as it did before AEC3-based barge-in existed (gap-gated only). [webViewRenderActive] (Phase 2's
     * WebView render path) gates the same unconditional way [currentMediaPlayer] does — no
     * capture-side echo cancellation is wired for it yet (that's Phase 3).
     *
     * When [WEBVIEW_CAPTURE_ENABLED] is on, this function does nothing at all — [WakeWordEngine]'s
     * raw chunks are a completely different audio stream than the WebView's `getUserMedia` capture,
     * and interleaving both into one `SileroVad` session (which carries RNN state across calls)
     * would corrupt it. [forwardWebViewCapturedChunk] is the sole source of barge-in detection in
     * that mode instead.
     */
    private fun forwardArmedChunk(chunk: ShortArray) {
        if (WEBVIEW_CAPTURE_ENABLED) return

        // Both the self-echo-gate exemption and the lead-in gate below only apply when the
        // feature is on AND a session-scoped AEC3 instance is actually active — otherwise this
        // falls straight through to the old gap-gated behavior (see AEC3_BARGE_IN_ENABLED's
        // kill-switch doc comment).
        val aecActive = AEC3_BARGE_IN_ENABLED && aec3 != null

        if (currentMediaPlayer != null) return // mp3 fallback: no AEC3 coverage, always gap-gated
        if (webViewRenderActive) return // Phase 2 WebView render path: no capture-side echo cancellation wired yet (Phase 3), always gap-gated
        if (currentTrack != null && !aecActive) return // streaming path: gated unless AEC3 is actively cleaning it

        // Diagnostic peak is measured on the true raw mic chunk (pre-AEC3, pre-gain) — its purpose
        // is "does the mic itself have signal at all," which is orthogonal to echo cancellation.
        var rawPeak = 0
        for (s in chunk) {
            val abs = kotlin.math.abs(s.toInt())
            if (abs > rawPeak) rawPeak = abs
        }
        if (rawPeak > vadPeakRawAmplitude) vadPeakRawAmplitude = rawPeak

        // Run the raw chunk through AEC3's capture side first, so Silero below sees echo-cancelled
        // audio instead of the raw gained signal. cleanCaptureChunk() falls back to the unmodified
        // chunk when aec3 is null (no active session). Always runs — independent of the
        // kill-switch — so AEC3 keeps converging even while its result isn't yet trusted below.
        val cleanedChunk = cleanCaptureChunk(chunk)

        // Rolling pre-interrupt audio buffer — see bargeInAudioBuffer's doc comment. Runs before
        // the bargeInFired check below (a few more chunks may still arrive while onBargeIn's
        // cancellation propagates — worth keeping), capped so it never grows past ~3s regardless.
        // Guarded by vadLock, same as every other mutation of this field (setBargeInArmed's reset,
        // consumeBargeInAudio's read+clear) — forwardArmedChunk runs on WakeWordEngine's capture
        // thread while those run from the harness's coroutine thread.
        synchronized(vadLock) {
            bargeInAudioBuffer += cleanedChunk
            if (bargeInAudioBuffer.size > BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES) {
                bargeInAudioBuffer = bargeInAudioBuffer.copyOfRange(
                    bargeInAudioBuffer.size - BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES, bargeInAudioBuffer.size
                )
            }
        }

        if (bargeInFired) return // already interrupted this window; wait for disarm

        // Diagnostic: peak of this chunk's *cleaned* output, pre-gain — comparing this to rawPeak
        // above (same chunk) is the fastest way to tell whether AEC3 is suppressing Teya's own
        // echo at all in the current acoustic environment.
        var cleanedPeak = -1
        if (aecActive) {
            cleanedPeak = 0
            for (s in cleanedChunk) {
                val abs = kotlin.math.abs(s.toInt())
                if (abs > cleanedPeak) cleanedPeak = abs
            }
            if (cleanedPeak > vadPeakCleanedAmplitude) vadPeakCleanedAmplitude = cleanedPeak
        }

        // Convergence lead-in gate: don't pass the cleaned signal to Silero / allow bargeInFired
        // to be set until the session-wide lead-in has elapsed since the very first render frame
        // (not reset per turn — see leadInElapsed). AEC3 above still keeps converging during the
        // lead-in either way; this only withholds trust in the *result*.
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
                    Log.d(
                        "VoicePipeline",
                        "Barge-in: speech detected (confidence=${vad.lastConfidence}, " +
                            "rawPeak=$rawPeak, cleanedPeak(pre-gain)=$cleanedPeak)"
                    )
                    onBargeIn()
                    break
                }
            }
            vadFrameBuffer = if (offset > 0) vadFrameBuffer.copyOfRange(offset, vadFrameBuffer.size) else vadFrameBuffer
        }

        // Diagnostic: peak VAD confidence every ~25 mic chunks (~2s), so we can see whether the
        // model is responding to real speech at all, independent of whether it crosses threshold.
        // cleanedAmplitude alongside rawAmplitude shows whether AEC3 is suppressing anything in
        // this window at all — if the two numbers are close, AEC3 isn't helping here.
        if (++vadChunkCounter % 25 == 0) {
            Log.d(
                "VoicePipeline",
                "Barge-in: peak VAD confidence (last ~2s) = $vadPeakConfidence, " +
                    "peak RAW (pre-gain) amplitude = $vadPeakRawAmplitude / 32767, " +
                    "peak CLEANED (pre-gain) amplitude = ${if (aecActive) vadPeakCleanedAmplitude.toString() else "n/a"} / 32767"
            )
            vadPeakConfidence = 0f
            vadPeakRawAmplitude = 0
            vadPeakCleanedAmplitude = 0
            logAec3Metrics()
        }
    }

    /**
     * Phase 3 capture path: the [WEBVIEW_CAPTURE_ENABLED] counterpart to [forwardArmedChunk]. Runs
     * on whatever thread [WebViewAecHost]'s JS bridge invokes its capture callback from — not
     * [WakeWordEngine]'s capture thread, so this and [forwardArmedChunk] must never run
     * concurrently against the same `SileroVad`/[vadFrameBuffer]/[bargeInAudioBuffer] state (see
     * [forwardArmedChunk]'s doc comment on why — enforced by [forwardArmedChunk] itself no-opping
     * whenever this path is enabled).
     *
     * [cleanedChunk] arrives already echo-cancelled by Chromium's `getUserMedia`, so unlike
     * [forwardArmedChunk] there's no [cleanCaptureChunk] step here. [currentMediaPlayer] (mp3
     * fallback) and [currentTrack] (AudioTrack render active instead of WebView) always gate —
     * neither has a `getUserMedia` self-echo reference for Chromium to cancel against.
     * [webViewRenderActive] gates unconditionally *unless* [WEBVIEW_CONTINUOUS_BARGE_IN_ENABLED] is
     * on, in which case it doesn't — this is Phase 4's whole point: continuous mid-sentence
     * listening, trusting Chromium's own echo cancellation to suppress Teya's WebView-rendered
     * voice well enough that real user speech is still detectable during it. No AEC3-style
     * convergence lead-in here (yet) — Chromium's AEC is a mature, independently-shipped feature,
     * not a from-scratch adaptive filter paying its own convergence cost; test live whether one
     * turns out to be needed anyway before assuming it isn't.
     *
     * Unlike [forwardArmedChunk] (only ever invoked while armed — [WakeWordEngine] itself gates
     * that at the call site), this callback fires for the WebView capture's entire session
     * lifetime, armed or not — [startCapture]/[stopCapture] are tied to the AEC session, not the
     * per-turn arm/disarm. The `sileroVad == null` check below reproduces that same "only while
     * armed" gate here instead, so this doesn't pollute [bargeInAudioBuffer] during command capture.
     */
    private fun forwardWebViewCapturedChunk(cleanedChunk: ShortArray) {
        if (sileroVad == null) return // not armed — mirrors WakeWordEngine's own bargeInArmed gate
        if (currentMediaPlayer != null) return // mp3 fallback: no WebView coverage, always gap-gated
        if (currentTrack != null) return // AudioTrack render active instead of WebView: no self-echo reference to cancel against
        if (webViewRenderActive && !WEBVIEW_CONTINUOUS_BARGE_IN_ENABLED) return

        // Rolling pre-interrupt audio buffer — see bargeInAudioBuffer's doc comment. Same
        // reasoning as forwardArmedChunk's identical block: runs before the bargeInFired check
        // below, capped so it never grows past ~3s regardless.
        synchronized(vadLock) {
            bargeInAudioBuffer += cleanedChunk
            if (bargeInAudioBuffer.size > BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES) {
                bargeInAudioBuffer = bargeInAudioBuffer.copyOfRange(
                    bargeInAudioBuffer.size - BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES, bargeInAudioBuffer.size
                )
            }
        }

        if (bargeInFired) return // already interrupted this window; wait for disarm

        // Diagnostic: peak of the getUserMedia-cleaned signal, pre-gain — the WebView-capture
        // equivalent of forwardArmedChunk's cleanedPeak (no rawPeak counterpart here: Chromium's
        // pre-AEC signal isn't observable from this side, only what it hands back already cleaned).
        var cleanedPeak = 0
        for (s in cleanedChunk) {
            val abs = kotlin.math.abs(s.toInt())
            if (abs > cleanedPeak) cleanedPeak = abs
        }
        if (cleanedPeak > vadPeakCleanedAmplitude) vadPeakCleanedAmplitude = cleanedPeak

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
                    Log.d(
                        "VoicePipeline",
                        "Barge-in (WebView capture): speech detected (confidence=${vad.lastConfidence}, " +
                            "cleanedPeak(pre-gain)=$cleanedPeak)"
                    )
                    onBargeIn()
                    break
                }
            }
            vadFrameBuffer = if (offset > 0) vadFrameBuffer.copyOfRange(offset, vadFrameBuffer.size) else vadFrameBuffer
        }

        if (++vadChunkCounter % 25 == 0) {
            Log.d(
                "VoicePipeline",
                "Barge-in (WebView capture): peak VAD confidence (last ~2s) = $vadPeakConfidence, " +
                    "peak CLEANED (pre-gain) amplitude = $vadPeakCleanedAmplitude / 32767"
            )
            vadPeakConfidence = 0f
            vadPeakCleanedAmplitude = 0
        }
    }

    /**
     * Diagnostic only: logs AEC3's own [NativeAec3.getMetrics] snapshot — its self-reported
     * render/capture delay estimate and cancellation strength (ERL/ERLE). Whether [delayMs] is
     * stable and small, or unstable/implausibly large, is the direct way to confirm or rule out
     * that delay estimation (as opposed to AEC3's suppression math) is the reason real-device
     * suppression has been inconsistent — see the 2026-07-10 barge-in handoff doc. No behavior
     * change; [NativeAec3.setAudioBufferDelay] is not called yet pending what this shows.
     */
    private fun logAec3Metrics() {
        synchronized(aecLock) {
            val aec = aec3 ?: return
            try {
                val m = aec.getMetrics()
                Log.d(
                    "VoicePipeline",
                    "AEC3 metrics: delayMs=${m.delayMs}, " +
                        "echoReturnLossDb=${"%.1f".format(m.echoReturnLossDb)}, " +
                        "echoReturnLossEnhancementDb=${"%.1f".format(m.echoReturnLossEnhancementDb)}"
                )
            } catch (e: Exception) {
                Log.e("VoicePipeline", "AEC3: failed to read metrics", e)
            }
        }
    }

    /**
     * Records a spoken command using simple energy-based VAD (stops after a short trailing
     * silence, or gives up after [maxInitialSilenceMs] if no speech starts), then transcribes it
     * via Voxtral. The caller must pause the wake-word recorder first ([pauseWakeWord]) to avoid
     * microphone contention. Returns "" on any failure or silence.
     *
     * [prefixAudio] — pass [consumeBargeInAudio]'s result here right after a barge-in interrupt —
     * is prepended to whatever this call captures live, so speech already underway when the user
     * interrupted (which the WakeWordEngine handoff gap would otherwise cut off — see
     * [bargeInAudioBuffer]'s doc comment) carries through into the transcription.
     */
    suspend fun listenForCommand(
        maxInitialSilenceMs: Int = DEFAULT_INITIAL_SILENCE_MS,
        contextBias: List<String> = emptyList(),
        prefixAudio: ShortArray = ShortArray(0),
    ): String =
        withContext(Dispatchers.IO) {
            val client = mistralClient ?: run {
                Log.e("VoicePipeline", "MistralClient not set, cannot transcribe")
                return@withContext ""
            }
            try {
                val wavFile = recordWithVad(maxInitialSilenceMs, prefixAudio) ?: run {
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
    private fun recordWithVad(maxInitialSilenceMs: Int, prefixAudio: ShortArray = ShortArray(0)): File? {
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

        // Real recordings were hitting MAX_RECORDING_MS instead of stopping on trailing silence —
        // SILENCE_RMS_THRESHOLD doesn't reliably stay under real ambient room noise. Use the same
        // SileroVad already proven reliable for barge-in in this exact acoustic environment
        // instead; falls back to the old RMS approach only if SileroVad fails to construct, so a
        // model/asset problem degrades gracefully rather than making every command capture silent.
        val vad = try {
            SileroVad(context)
        } catch (e: Exception) {
            Log.e("VoicePipeline", "Failed to init SileroVad for command recording, falling back to RMS energy detection", e)
            null
        }

        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(FRAME_SAMPLES)
        // If we already have pre-interrupt audio (see prefixAudio's doc comment), seed the buffer
        // with it and treat speech as already started — the leading-silence trim below only
        // applies to what this call captures live, not to audio we already know contains speech.
        if (prefixAudio.isNotEmpty()) {
            val bytes = ByteArray(prefixAudio.size * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(prefixAudio)
            pcm.write(bytes)
            Log.d("VoicePipeline", "Prepending ${prefixAudio.size} samples of pre-interrupt audio")
        }
        var speechStarted = prefixAudio.isNotEmpty()
        var trailingSilenceMs = 0
        var elapsedMs = 0

        try {
            recorder.startRecording()
            Log.d("VoicePipeline", "Recording command…")
            while (elapsedMs < MAX_RECORDING_MS) {
                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) continue
                elapsedMs += FRAME_MS

                val isSpeech = if (vad != null && read == FRAME_SAMPLES) {
                    vad.isSpeech(frame)
                } else {
                    var sumSquares = 0.0
                    for (i in 0 until read) {
                        val s = frame[i].toDouble()
                        sumSquares += s * s
                    }
                    sqrt(sumSquares / read) > SILENCE_RMS_THRESHOLD
                }

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
            vad?.close()
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
     * AEC3-covered path), `false` if it fell back to `playMp3` (no AEC3 coverage) —
     * `HarnessService.respond()` uses this per-sentence result to decide whether its inter-sentence
     * gap is still needed after this sentence (streaming vs. mp3 fallback is chosen independently
     * per call, not once per turn). When nothing was actually spoken (blank text, or no client
     * configured), returns `true`: no audio played, so there's nothing for a gap to protect against.
     *
     * Does NOT reset [interruptRequested] — callers speaking multiple sentences per turn (see
     * `HarnessService.respond()`'s speaker loop) call this once per sentence, on a different
     * thread than [onBargeIn]/[interrupt] fires from. Resetting the flag here raced against a
     * genuine interrupt landing right as the loop moved to the next queued sentence — the reset
     * could clobber a fresh `true` before the loop's `isInterrupted()` check ever saw it, letting
     * that next sentence play through uninterrupted despite the UI already having flipped to
     * "listening". Callers starting a genuinely fresh speaking phase must call [consumeInterrupted]
     * themselves first (see `HarnessService.runConversation`'s two call sites).
     */
    suspend fun textToSpeech(text: String): Boolean {
        if (text.isBlank()) return true
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
     * Dispatches to Phase 2's WebView render path (see [WEBVIEW_RENDER_ENABLED]'s doc comment) when
     * both its flag and [WEBVIEW_AEC_HOST_ENABLED] are on and a host actually exists this session,
     * falling back to the always-on [streamToSpeakerViaAudioTrack] otherwise — same
     * graceful-degrade pattern as every other kill-switch in this class.
     */
    private suspend fun streamToSpeaker(client: MistralClient, text: String): Boolean {
        val host = webViewAecHost
        return if (WEBVIEW_AEC_HOST_ENABLED && WEBVIEW_RENDER_ENABLED && host != null) {
            streamToSpeakerViaWebView(client, text, host)
        } else {
            streamToSpeakerViaAudioTrack(client, text)
        }
    }

    /**
     * Phase 2 render path: streams the same Voxtral PCM chunks [streamToSpeakerViaAudioTrack] would
     * write to an AudioTrack into [WebViewAecHost.pushRenderChunk] instead — no int16 conversion
     * needed here (Web Audio wants float32 directly), and no AEC3 render-feed bookkeeping (Chromium's
     * own `getUserMedia({echoCancellation:true})` doesn't need an explicit render reference fed to
     * it the way our own NativeAec3 does — see the plan's architecture overview). [webViewRenderActive]
     * substitutes for [currentTrack] as [forwardArmedChunk]'s self-echo gate signal, since this path
     * has no AudioTrack of its own.
     */
    private suspend fun streamToSpeakerViaWebView(
        client: MistralClient,
        text: String,
        host: WebViewAecHost,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            webViewRenderActive = true
            var totalSamples = 0
            val got = client.streamSpeechPcm(text) { floats ->
                if (!interruptRequested) {
                    host.pushRenderChunk(floats, TTS_SAMPLE_RATE)
                    totalSamples += floats.size
                }
            }
            if (interruptRequested) return@withContext true // stopped on purpose, not a failure
            if (!got || totalSamples == 0) return@withContext false

            val finished = host.awaitRenderPlaybackDone()
            if (!finished) {
                Log.w("VoicePipeline", "WebView render: playback did not finish within timeout")
            }
            Log.d("VoicePipeline", "WebView render: playback finished ($totalSamples samples)")
            true
        } catch (e: Exception) {
            Log.e("VoicePipeline", "WebView render streaming error", e)
            false
        } finally {
            webViewRenderActive = false
        }
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
    private suspend fun streamToSpeakerViaAudioTrack(client: MistralClient, text: String): Boolean =
        withContext(Dispatchers.IO) {
            var track: AudioTrack? = null
            var renderPollerJob: Job? = null
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
                    // Was maxOf(minBuf, TTS_SAMPLE_RATE) (~0.5s), padded for smooth streaming over
                    // network jitter — shrunk to Android's true minimum since a large buffer widens
                    // the gap between "write() returns" and "actually audible." That gap is now
                    // irrelevant to AEC3 correctness (see feedPlayedRenderFrames below, which paces
                    // analyzeRender off real playback position instead of assuming write-time ==
                    // play-time), but a smaller buffer still keeps genuine playback latency low.
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = audioTrack
                currentTrack = audioTrack
                audioTrack.play()

                // Render pacing state for this utterance only — AudioTrack.playbackHeadPosition
                // resets to 0 with each fresh AudioTrack, so this can't live on aec3's
                // session-scoped fields. Chunks are resampled to 16kHz and buffered as they arrive
                // from the network, but only fed to NativeAec3.analyzeRender once playbackHeadPosition
                // confirms they've actually played, polled on a steady cadence independent of network
                // arrival timing. This is the fix for the delay-estimator failure diagnosed in
                // thoughts/shared/research/2026-07-10-aec3-delay-estimator-diagnostic.md: feeding
                // analyzeRender at network-chunk-arrival time (the old approach) left the true
                // render/capture offset drifting within a single utterance, which AEC3's matched
                // filter can't correlate against — hence delayMs stuck near 0 and ERLE near 0dB on
                // real-device testing. Guarded by aecLock, same as every other AEC3-adjacent access
                // in this class (see aec3's field doc comment).
                var pendingRender16k = ShortArray(0)
                var fedRender16k = 0L

                fun feedPlayedRenderFrames() {
                    synchronized(aecLock) {
                        val aec = aec3 ?: return
                        val played16k = SAMPLE_RATE.toLong() * audioTrack.playbackHeadPosition.toLong() / TTS_SAMPLE_RATE
                        val available = minOf(played16k - fedRender16k, pendingRender16k.size.toLong())
                            .coerceAtLeast(0L).toInt()
                        var offset = 0
                        while (available - offset >= NativeAec3.FRAME_SIZE) {
                            val frame = pendingRender16k.copyOfRange(offset, offset + NativeAec3.FRAME_SIZE)
                            offset += NativeAec3.FRAME_SIZE
                            aec.analyzeRender(frame)
                            fedRender16k += NativeAec3.FRAME_SIZE
                            if (firstRenderFrameAtMs == 0L) firstRenderFrameAtMs = System.currentTimeMillis()
                            renderFrameCount++
                        }
                        if (offset > 0) {
                            pendingRender16k = pendingRender16k.copyOfRange(offset, pendingRender16k.size)
                            // Diagnostic marker for manual logcat verification (~1s of render audio
                            // @16kHz, since FRAME_SIZE=160 samples=10ms): confirms real TTS audio is
                            // reaching analyzeRender while Teya speaks.
                            if (renderFrameCount % 100 == 0) {
                                Log.d("VoicePipeline", "AEC3: render frames fed to analyzeRender this session = $renderFrameCount")
                            }
                        }
                    }
                }

                renderPollerJob = launch {
                    while (isActive) {
                        try {
                            feedPlayedRenderFrames()
                        } catch (e: Exception) {
                            Log.e("VoicePipeline", "AEC3: render pacing poll failed", e)
                        }
                        delay(AEC3_RENDER_POLL_MS)
                    }
                }

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
                        // Resample and buffer for feedPlayedRenderFrames (above) to feed to AEC3
                        // once actually played — deliberately NOT fed to analyzeRender here at
                        // network-arrival time (see this function's doc comment on why that broke
                        // AEC3's delay estimation).
                        synchronized(aecLock) {
                            if (aec3 != null) {
                                pendingRender16k += renderResampler.resample(shorts)
                            }
                        }
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
                // Last pass to catch anything the poller's final tick missed before it's cancelled.
                feedPlayedRenderFrames()
                Log.d("VoicePipeline", "Playback finished (streamed $totalFrames frames)")
                true
            } catch (e: Exception) {
                Log.e("VoicePipeline", "AudioTrack streaming error", e)
                false
            } finally {
                renderPollerJob?.cancel()
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
