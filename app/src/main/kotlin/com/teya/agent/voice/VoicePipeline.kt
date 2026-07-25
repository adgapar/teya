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
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import com.teya.agent.brain.MistralClient
import com.teya.agent.harness.ConfigManager
import com.teya.agent.voice.aec.WebViewAecHost
import com.teya.agent.voice.vad.SileroVad
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/**
 * Thrown by [VoicePipeline.listenForCommand] when the Voxtral STT call itself fails (network/API
 * error) — kept distinct from a plain "" return, which means genuine silence (no speech captured).
 * Without this, a dropped connection mid-conversation looked identical to the user simply not
 * saying anything, so [com.teya.agent.harness.HarnessService] would end the conversation with no
 * explanation. See [VoicePipeline.listenForCommand]'s doc comment.
 */
class SttFailedException(cause: Throwable) : Exception("STT request failed", cause)

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
    }

    // Read live (not cached) at each use site below so Admin's "Voice tuning" section takes
    // effect on the next arm/chunk without restarting the service.
    private val config = ConfigManager(context)

    private val wakeWordEngine = WakeWordEngine(
        context,
        onDetected = { audio -> onWakeWord(audio) },
        onArmedAudioChunk = { chunk -> forwardArmedChunk(chunk) }
    )

    private var wakeWordCallback: ((ShortArray) -> Unit)? = null
    private var bargeInCallback: (() -> Unit)? = null
    private var wakeWordActive = false
    private var mistralClient: MistralClient? = null

    // Barge-in speech detection (see voice/vad/SileroVad.kt, an original implementation of
    // Silero VAD's own streaming algorithm run directly via ONNX Runtime): while armed, mic
    // chunks are reassembled into VAD-sized frames and checked synchronously, right on the
    // capture thread — no network round-trip, so no channel/coroutine hand-off is needed. One
    // instance per armed window since Silero carries RNN hidden state across calls.
    @Volatile private var sileroVad: SileroVad? = null
    private var vadFrameBuffer = ShortArray(0)
    @Volatile private var bargeInFired = false
    // Rolling buffer of cleaned, pre-gain mic audio captured continuously during the armed
    // window — NOT gated on bargeInFired, unlike the VAD path below, so it keeps covering right
    // up through the moment of interrupt. Exists because the capture callbacks below only ever
    // produced a yes/no signal from Silero, never kept the audio — meaning whatever
    // triggered/accompanied an interrupt would otherwise be lost, and the next command recording
    // only starts picking up speech after the mic handoff settles, producing STT fragments like
    // "works." or "Now," instead of the whole sentence. Capped at
    // BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES so a long Teya response before any interrupt doesn't grow
    // this unboundedly. See [consumeBargeInAudio].
    private var bargeInAudioBuffer = ShortArray(0)
    // Raw PCM from the most recent listenForCommand capture — see consumeLastCommandAudio.
    @Volatile private var lastCommandAudio: ShortArray? = null
    // Guards sileroVad's create/use/close: the capture callbacks below run on their own capture
    // thread while setBargeInArmed(false) runs on the harness's coroutine thread — without this,
    // a disarm's close() can race a concurrent isSpeech() call and crash natively (use-after-free
    // on the ONNX session), which is exactly what happened live (silent process restart, no JVM
    // exception — the signature of a native crash, not a Kotlin one).
    private val vadLock = Any()
    private var vadChunkCounter = 0    // diagnostic
    private var vadPeakConfidence = 0f // diagnostic
    private var vadPeakRawAmplitude = 0 // diagnostic — the fallback (no echo cancellation) path's peak amplitude, pre-gain
    private var vadPeakCleanedAmplitude = 0 // diagnostic — the WebView (getUserMedia-cleaned) path's peak amplitude, pre-gain

    // Session-scoped WebView AEC host (construct/teardown once per conversation, not per turn —
    // see startAecSession/endAecSession). Owns both the render path (streamToSpeakerViaWebView,
    // Web Audio playback) and the capture path (forwardWebViewCapturedChunk,
    // getUserMedia({echoCancellation:true})). When active, this is the primary path for both TTS
    // playback and barge-in detection, and enables continuous mid-sentence listening (Chromium's
    // own echo cancellation suppresses Teya's rendered voice well enough that real user speech
    // stays detectable during it). If it fails to start (e.g. SYSTEM_ALERT_WINDOW not granted),
    // everything falls back automatically to plain AudioTrack playback
    // (streamToSpeakerViaAudioTrack) and gap-gated capture (forwardArmedChunk) — see
    // WebViewAecHost.isActive().
    private var webViewAecHost: WebViewAecHost? = null

    // Barge-in support: the currently-playing sink (whichever path is active) so [interrupt] can
    // cut it off immediately, plus a flag the harness checks to know a turn was cut short.
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentMediaPlayer: MediaPlayer? = null
    @Volatile private var playbackContinuation: CancellableContinuation<Unit>? = null
    @Volatile private var interruptRequested = false
    // The WebView render path has no AudioTrack/MediaPlayer of its own for the self-echo gates
    // below to key off — this fills that role instead. Read on the capture thread, written from
    // streamToSpeakerViaWebView's IO thread, hence @Volatile.
    @Volatile private var webViewRenderActive = false

    fun setMistralClient(client: MistralClient) {
        this.mistralClient = client
    }

    /**
     * Synthesizes and plays a short pure-tone cue (fire-and-forget — runs its own short-lived
     * thread so callers, including [interrupt] on the capture thread, never block). Wall-mounted,
     * screen-not-always-visible device: the orb's listening/thinking/speaking state changes aren't
     * visible unless you're looking at it, so key transitions get an audible cue too. Uses
     * USAGE_MEDIA (same as the TTS output, already confirmed clearly audible at a distance) rather
     * than USAGE_ASSISTANCE_SONIFICATION — the latter maps to a separate system-sound volume
     * stream that was too quiet to hear reliably even at 15cm.
     */
    private fun playTone(freqHz: Double, durationMs: Int, volume: Float = 0.9f) {
        if (isQuietHours()) return
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

    /**
     * True if local time currently falls within the configured quiet-hours window. Delegates to
     * [ConfigManager.inQuietHoursNow] — the single source of truth shared with the wake-word gate
     * in HarnessService — so the TTS/chime muting here and the auto-listen suppression there stay
     * in lockstep.
     */
    private fun isQuietHours(): Boolean = config.inQuietHoursNow()

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
        // The WebView render path has no local AudioTrack/MediaPlayer to pause/stop above —
        // stopPlayback() suspends (hops to the main thread), so it can't run inline on whatever
        // thread interrupt() itself is called from. Fire-and-forget on its own thread, same
        // pattern as playTone().
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

    /**
     * True if this session's barge-in detection is running continuously (the WebView AEC host is
     * active, so no inter-sentence gap is needed) rather than gap-gated. Used by
     * `HarnessService.respond()` to decide whether its inter-sentence pause is still needed after
     * a given sentence.
     */
    fun isContinuousBargeInActive(): Boolean = webViewAecHost?.isActive() == true

    fun startListening(onWakeWord: (audio: ShortArray) -> Unit, onBargeIn: () -> Unit) {
        Log.d("VoicePipeline", "Wake word detection started")
        this.wakeWordCallback = onWakeWord
        this.bargeInCallback = onBargeIn
        wakeWordActive = true
        wakeWordEngine.start()
    }

    private fun onWakeWord(audio: ShortArray) {
        Log.d("VoicePipeline", "Wake word detected!")
        wakeWordCallback?.invoke(audio)
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
                    // Threshold sits close to Silero's own recommended 0.5 default — nudged up
                    // since the remaining risk is ambient room noise, not Teya's own voice (either
                    // gated out entirely on the fallback path, or suppressed by Chromium's echo
                    // cancellation on the WebView path). speechDurationMs is kept short since the
                    // gap-gated fallback's listening window is brief.
                    sileroVad = SileroVad(
                        context,
                        threshold = config.vadThreshold,
                        speechDurationMs = config.vadSpeechDurationMs,
                        silenceDurationMs = config.vadSilenceDurationMs,
                    )
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
     * Returns and clears the raw PCM captured by the most recent [listenForCommand] call, or null
     * if none has completed yet (or it was already consumed) — per-speaker voice ID's live
     * re-check (see [com.teya.agent.household.SpeakerIdManager]) uses this, since real
     * conversational speech is usually a better sample than the wake-word pre-roll alone.
     */
    fun consumeLastCommandAudio(): ShortArray? {
        val audio = lastCommandAudio
        lastCommandAudio = null
        return audio
    }

    /**
     * Starts the whole-session WebView AEC host. Call once near the top of
     * [com.teya.agent.harness.HarnessService.runConversation] — deliberately independent of
     * [setBargeInArmed]'s per-turn arm/disarm: the WebView (and Chromium's own echo-cancellation
     * state) persists across the whole conversation, not just one turn. If the host fails to start
     * (missing overlay permission, or any other error), [webViewAecHost] stays non-null but
     * [WebViewAecHost.isActive] reports `false`, and every downstream call site checks that and
     * falls back automatically — never a hard failure.
     *
     * Also disables [WakeWordEngine]'s own platform `AcousticEchoCanceler` for the session — this
     * device's platform AEC is documented as unreliable (see `WakeWordEngine.enableAudioEffects`)
     * and leaving it enabled on the wake-word mic session while Chromium's own `getUserMedia`
     * echo cancellation runs concurrently was found to degrade the latter's suppression enough to
     * cause real self-interrupt false positives, even though the two capture sessions are
     * otherwise independent — most likely hardware/DSP-level interaction on this device's audio
     * chipset, not something either capture path controls directly. Restored in [endAecSession].
     */
    suspend fun startAecSession() {
        wakeWordEngine.setPlatformAecEnabled(false)
        if (webViewAecHost != null) return // already started
        val host = WebViewAecHost(context, onCaptureChunk = { chunk -> forwardWebViewCapturedChunk(chunk) })
        webViewAecHost = host
        host.start()
        if (host.isActive()) {
            host.startCapture(SAMPLE_RATE)
            host.setRenderGain(dbToLinearGain(config.ttsVolumeBoostDb))
            Log.d("VoicePipeline", "WebView AEC: session started")
        } else {
            Log.w("VoicePipeline", "WebView AEC: failed to start — falling back to gap-gated barge-in with no echo cancellation")
        }
    }

    /** Ends the whole-session WebView AEC host. Call once in [runConversation]'s `finally`. */
    suspend fun endAecSession() {
        wakeWordEngine.setPlatformAecEnabled(true)
        webViewAecHost?.let { host ->
            if (host.isActive()) host.stopCapture()
            host.stop()
        }
        webViewAecHost = null
    }

    /**
     * Fallback capture path, used whenever [webViewAecHost] isn't active this session. Runs on
     * [WakeWordEngine]'s capture thread. Reassembles the 1280-sample mic chunks into
     * [SileroVad.FRAME_SIZE]-sample frames (they don't divide evenly) and checks each
     * synchronously — cheap enough to do inline, no async hand-off needed. Applies the same
     * software gain [WakeWordEngine] applies before its own classifier: this device has no
     * hardware AGC, so the raw signal is otherwise too quiet for reliable detection.
     *
     * No echo cancellation on this path at all, so it skips processing entirely while
     * [currentTrack]/[currentMediaPlayer] is non-null (Teya's own TTS actively coming out of the
     * speaker) — it only ever listens between sentences, never during them.
     */
    private fun forwardArmedChunk(chunk: ShortArray) {
        if (webViewAecHost?.isActive() == true) return // WebView capture is the active path this session

        if (currentMediaPlayer != null || currentTrack != null) return

        // Diagnostic: does the mic itself have signal at all, independent of anything downstream.
        var peak = 0
        for (s in chunk) {
            val abs = kotlin.math.abs(s.toInt())
            if (abs > peak) peak = abs
        }
        if (peak > vadPeakRawAmplitude) vadPeakRawAmplitude = peak

        // Rolling pre-interrupt audio buffer — see bargeInAudioBuffer's doc comment. Runs before
        // the bargeInFired check below (a few more chunks may still arrive while onBargeIn's
        // cancellation propagates — worth keeping), capped so it never grows past ~3s regardless.
        synchronized(vadLock) {
            bargeInAudioBuffer += chunk
            if (bargeInAudioBuffer.size > BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES) {
                bargeInAudioBuffer = bargeInAudioBuffer.copyOfRange(
                    bargeInAudioBuffer.size - BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES, bargeInAudioBuffer.size
                )
            }
        }

        if (bargeInFired) return // already interrupted this window; wait for disarm

        val gain = config.bargeInGain
        val gained = ShortArray(chunk.size) { i ->
            (chunk[i] * gain).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
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
                        "Barge-in: speech detected (confidence=${vad.lastConfidence}, peak=$peak)"
                    )
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
                    "peak amplitude = $vadPeakRawAmplitude / 32767"
            )
            vadPeakConfidence = 0f
            vadPeakRawAmplitude = 0
        }
    }

    /**
     * Primary capture path when [webViewAecHost] is active this session. Runs on whatever thread
     * the WebView's JS bridge invokes its capture callback from — not [WakeWordEngine]'s capture
     * thread, so this and [forwardArmedChunk] must never run concurrently against the same
     * `SileroVad`/[vadFrameBuffer]/[bargeInAudioBuffer] state (two unrelated audio streams
     * interleaved into one Silero session, which carries RNN state across calls, would corrupt
     * it) — enforced by [forwardArmedChunk] itself no-opping whenever the WebView host is active.
     *
     * [cleanedChunk] arrives already echo-cancelled by Chromium's `getUserMedia`, so unlike
     * [forwardArmedChunk] there's no extra cleaning step here. [currentMediaPlayer] (mp3 fallback)
     * and [currentTrack] (AudioTrack render active instead of WebView) always gate — neither has a
     * `getUserMedia` self-echo reference for Chromium to cancel against. [webViewRenderActive]
     * does NOT gate — Chromium's own echo cancellation is trusted to suppress Teya's
     * WebView-rendered voice well enough that real user speech stays detectable during it,
     * enabling continuous mid-sentence barge-in.
     *
     * Unlike [forwardArmedChunk] (only ever invoked while armed — [WakeWordEngine] itself gates
     * that at the call site), this callback fires for the WebView capture's entire session
     * lifetime, armed or not — capture start/stop is tied to the AEC session, not the per-turn
     * arm/disarm. The `sileroVad == null` check below reproduces that same "only while armed" gate
     * here instead, so this doesn't pollute [bargeInAudioBuffer] during command capture.
     */
    private fun forwardWebViewCapturedChunk(cleanedChunk: ShortArray) {
        if (sileroVad == null) return // not armed — mirrors WakeWordEngine's own bargeInArmed gate
        if (currentMediaPlayer != null) return // mp3 fallback: no WebView coverage, always gap-gated
        if (currentTrack != null) return // AudioTrack render active instead of WebView: no self-echo reference to cancel against

        synchronized(vadLock) {
            bargeInAudioBuffer += cleanedChunk
            if (bargeInAudioBuffer.size > BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES) {
                bargeInAudioBuffer = bargeInAudioBuffer.copyOfRange(
                    bargeInAudioBuffer.size - BARGE_IN_AUDIO_BUFFER_MAX_SAMPLES, bargeInAudioBuffer.size
                )
            }
        }

        if (bargeInFired) return // already interrupted this window; wait for disarm

        var cleanedPeak = 0
        for (s in cleanedChunk) {
            val abs = kotlin.math.abs(s.toInt())
            if (abs > cleanedPeak) cleanedPeak = abs
        }
        if (cleanedPeak > vadPeakCleanedAmplitude) vadPeakCleanedAmplitude = cleanedPeak

        val gain = config.bargeInGain
        val gained = ShortArray(cleanedChunk.size) { i ->
            (cleanedChunk[i] * gain).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
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
     * Records a spoken command using simple energy-based VAD (stops after a short trailing
     * silence, or gives up after [maxInitialSilenceMs] if no speech starts), then transcribes it
     * via Voxtral. The caller must pause the wake-word recorder first ([pauseWakeWord]) to avoid
     * microphone contention. Returns "" on genuine silence (no speech captured); throws
     * [SttFailedException] if the STT call itself fails (network/API error) — the two used to be
     * indistinguishable, silently ending the conversation on a dropped connection with no
     * explanation to the user (see [SttFailedException]'s doc comment). Callers should catch it
     * and give the user a spoken reason instead of just going quiet.
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
            val wavFile = recordWithVad(maxInitialSilenceMs, prefixAudio) ?: run {
                Log.d("VoicePipeline", "No command audio captured")
                return@withContext ""
            }
            try {
                client.transcribe(wavFile, contextBias)
            } catch (e: Exception) {
                Log.e("VoicePipeline", "STT request failed", e)
                throw SttFailedException(e)
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

    /** Whether the wake-word recorder is actually capturing right now — lets HarnessService
     *  reconcile the desired listening mode against the real engine state (the per-turn
     *  pause/resume above changes it behind the mode gate's back). */
    fun isWakeWordRunning(): Boolean = wakeWordEngine.isCapturing

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

        // Stash for per-speaker voice ID's live re-check (see consumeLastCommandAudio) — real
        // conversational speech captured here is usually a better sample than the wake-word
        // pre-roll (longer, VAD-trimmed, more natural), so HarnessService re-runs identification
        // on it every turn rather than relying solely on the one-shot wake-word-time guess.
        val shorts = ShortArray(pcmBytes.size / 2)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        lastCommandAudio = shorts

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
     * mp3 if streaming yields nothing. Returns `true` if this specific call streamed, `false` if
     * it fell back to `playMp3` — `HarnessService.respond()` uses this per-sentence result
     * together with [isContinuousBargeInActive] to decide whether its inter-sentence gap is still
     * needed after this sentence. When nothing was actually spoken (blank text, or no client
     * configured), returns `true`: no audio played, so there's nothing for a gap to protect
     * against.
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
        // Quiet hours: still a "successful" turn (conversation/tool-calling/on-screen text continue
        // as normal via HarnessService's own broadcasts) — just no audible output. See
        // ConfigManager.quietHoursEnabled's doc comment.
        if (isQuietHours()) return true
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
     * Dispatches to the WebView AEC render path when [webViewAecHost] is active this session,
     * falling back to plain AudioTrack streaming otherwise (no host, or it failed to start).
     */
    private suspend fun streamToSpeaker(client: MistralClient, text: String): Boolean {
        val host = webViewAecHost
        return if (host != null && host.isActive()) {
            streamToSpeakerViaWebView(client, text, host)
        } else {
            streamToSpeakerViaAudioTrack(client, text)
        }
    }

    /**
     * Streams Voxtral PCM chunks into [WebViewAecHost.pushRenderChunk] for gapless playback via
     * Web Audio — no int16 conversion needed (Web Audio wants float32 directly, unlike
     * [streamToSpeakerViaAudioTrack]'s AudioTrack). [webViewRenderActive] substitutes for
     * [currentTrack] as the self-echo gate signal in [forwardArmedChunk]/
     * [forwardWebViewCapturedChunk], since this path has no AudioTrack of its own.
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
     * Fallback render path, used whenever [webViewAecHost] isn't active this session (WebView
     * failed to start — e.g. missing SYSTEM_ALERT_WINDOW). Streams Voxtral PCM (float32/24kHz/
     * mono) into an AudioTrack as it arrives, converting each chunk to int16 (universally
     * supported; float32 output isn't) and routing as USAGE_MEDIA. Calls stop() before draining so
     * short clips (e.g. "Yes?") play out. No echo cancellation on this path — barge-in stays
     * gap-gated for sentences spoken this way (see [forwardArmedChunk]).
     *
     * Tried USAGE_VOICE_COMMUNICATION + AudioManager.MODE_IN_COMMUNICATION + forced speakerphone
     * here as an echo-cancellation experiment — reverted twice. First try (USAGE_VOICE_COMMUNICATION
     * alone) silently rerouted playback to the earpiece. Second try added
     * AudioManager.mode/isSpeakerphoneOn management, but `audioManager.mode` read back as
     * MODE_NORMAL immediately after being set — a silent no-op, most likely because doing so
     * requires holding audio focus, which this path doesn't. Audio was worse than the first
     * attempt (mic captured pure digital silence). Not worth retrying without real audio-focus
     * handling — a bigger change than this fallback path warrants.
     */
    /** dB -> linear amplitude factor, shared by both boost paths (WebView's plain GainNode wants linear). */
    private fun dbToLinearGain(db: Float): Double = Math.pow(10.0, db / 20.0)

    /**
     * Extra loudness on top of whatever the device's own (manually controlled — see
     * ConfigManager.ttsVolumeBoostDb's doc comment) volume is set to, via Android's built-in
     * loudness-boost effect (limits internally, so it won't clip as harshly as the WebView path's
     * plain GainNode does at the same dB). Attached per-playback since the session ID is only
     * known once the AudioTrack/MediaPlayer exists; swallows failure since some
     * emulators/devices don't support the effect and this is a nice-to-have, not required for
     * Teya to be heard at all.
     */
    private fun attachLoudnessEnhancer(audioSessionId: Int): LoudnessEnhancer? = try {
        LoudnessEnhancer(audioSessionId).apply {
            setTargetGain((config.ttsVolumeBoostDb * 100).toInt()) // dB -> millibels
            enabled = true
        }
    } catch (e: Exception) {
        Log.w("VoicePipeline", "LoudnessEnhancer unavailable, playing at unboosted volume", e)
        null
    }

    private suspend fun streamToSpeakerViaAudioTrack(client: MistralClient, text: String): Boolean =
        withContext(Dispatchers.IO) {
            var track: AudioTrack? = null
            var loudnessEnhancer: LoudnessEnhancer? = null
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
                    // Android's true minimum, not a jitter-padded buffer — keeps genuine playback
                    // latency low.
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = audioTrack
                currentTrack = audioTrack
                loudnessEnhancer = attachLoudnessEnhancer(audioTrack.audioSessionId)
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
                try { loudnessEnhancer?.release() } catch (_: Exception) {}
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
                var loudnessEnhancer: LoudnessEnhancer? = null
                try {
                    mediaPlayer.setDataSource(ByteArrayMediaDataSource(audio))
                    mediaPlayer.setOnCompletionListener {
                        Log.d("VoicePipeline", "Playback finished")
                        try { loudnessEnhancer?.release() } catch (_: Exception) {}
                        it.release()
                        currentMediaPlayer = null
                        playbackContinuation = null
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    mediaPlayer.setOnErrorListener { mp, what, extra ->
                        Log.e("VoicePipeline", "MediaPlayer Error: $what, $extra")
                        try { loudnessEnhancer?.release() } catch (_: Exception) {}
                        mp.release()
                        currentMediaPlayer = null
                        playbackContinuation = null
                        if (continuation.isActive) continuation.resume(Unit)
                        true
                    }
                    mediaPlayer.prepare()
                    loudnessEnhancer = attachLoudnessEnhancer(mediaPlayer.audioSessionId)
                    mediaPlayer.start()
                    continuation.invokeOnCancellation {
                        try { loudnessEnhancer?.release() } catch (_: Exception) {}
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
                    try { loudnessEnhancer?.release() } catch (_: Exception) {}
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
