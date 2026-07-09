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
     * Runs on [WakeWordEngine]'s capture thread. Reassembles the 1280-sample mic chunks into
     * [SileroVad.FRAME_SIZE]-sample frames (they don't divide evenly) and checks each
     * synchronously — cheap enough to do inline, no async hand-off needed. Applies the same
     * software gain [WakeWordEngine] applies before its own classifier: this device has no
     * hardware AGC, so the raw signal is otherwise too quiet for reliable detection.
     *
     * Skips processing entirely while [currentTrack]/[currentMediaPlayer] is non-null, i.e. while
     * our own TTS audio is actively coming out of the speaker. This is the self-echo fix: this
     * device's `AcousticEchoCanceler` over-suppresses real speech during playback (see
     * WakeWordEngine), and with it off, Teya's own voice scored just as high as genuine speech to
     * the VAD — no confidence threshold could tell them apart. Never listening while she's
     * actually speaking sidesteps that structurally: there's no echo to confuse with real speech
     * when nothing is playing. Tradeoff: can only interrupt in the gaps between sentences (where
     * `HarnessService.respond()`'s sentence-by-sentence TTS queue already pauses waiting for the
     * next sentence), not mid-sentence.
     */
    private fun forwardArmedChunk(chunk: ShortArray) {
        if (bargeInFired) return // already interrupted this window; wait for disarm
        if (currentTrack != null || currentMediaPlayer != null) return // our own audio is playing right now

        var rawPeak = 0
        val gained = ShortArray(chunk.size) { i ->
            val abs = kotlin.math.abs(chunk[i].toInt())
            if (abs > rawPeak) rawPeak = abs
            (chunk[i] * BARGE_IN_GAIN).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
        if (rawPeak > vadPeakRawAmplitude) vadPeakRawAmplitude = rawPeak

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

    suspend fun textToSpeech(text: String) {
        if (text.isBlank()) return
        interruptRequested = false // fresh attempt — any earlier interrupt has already been handled
        val client = mistralClient ?: run {
            Log.e("VoicePipeline", "MistralClient not set, cannot speak")
            return
        }
        // Prefer low-latency streaming PCM; fall back to whole-clip mp3 if streaming yields nothing.
        if (!streamToSpeaker(client, text)) {
            Log.w("VoicePipeline", "Streaming TTS failed; falling back to mp3")
            playMp3(client, text)
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
