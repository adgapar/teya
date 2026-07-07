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
import io.ktor.utils.io.jvm.javaio.*
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
    }

    private val wakeWordEngine = WakeWordEngine(context) {
        onDetected()
    }

    private var wakeWordCallback: (() -> Unit)? = null
    private var wakeWordActive = false
    private var mistralClient: MistralClient? = null

    fun setMistralClient(client: MistralClient) {
        this.mistralClient = client
    }

    fun startListening(onWakeWord: () -> Unit) {
        Log.d("VoicePipeline", "Wake word detection started")
        this.wakeWordCallback = onWakeWord
        wakeWordActive = true
        wakeWordEngine.start()
    }

    private fun onDetected() {
        Log.d("VoicePipeline", "Wake word detected!")
        wakeWordCallback?.invoke()
    }

    /**
     * Records a spoken command using simple energy-based VAD (stops after a short trailing
     * silence, or gives up after [maxInitialSilenceMs] if no speech starts), then transcribes it
     * via Voxtral. The caller must pause the wake-word recorder first ([pauseWakeWord]) to avoid
     * microphone contention. Returns "" on any failure or silence.
     */
    suspend fun listenForCommand(maxInitialSilenceMs: Int = DEFAULT_INITIAL_SILENCE_MS): String =
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
                client.transcribe(wavFile)
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
     * chunk to int16 (universally supported; float32 output isn't) and routes as USAGE_MEDIA to
     * match the mp3 path. Calls stop() before draining so short clips (e.g. "Yes?") play out.
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
                audioTrack.play()

                var totalFrames = 0
                val got = client.streamSpeechPcm(text) { floats ->
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
                if (!got || totalFrames == 0) return@withContext false

                // stop() drains the buffered tail in MODE_STREAM; poll until it plays out (with a
                // no-progress bail so we never hang if the head stalls short of the end).
                try { audioTrack.stop() } catch (_: Exception) {}
                var guard = 0; var last = -1; var stable = 0
                while (guard++ < 1000) {
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
            }
        }

    /** Whole-clip fallback: decode base64 mp3 and play from memory (no disk). */
    private suspend fun playMp3(client: MistralClient, text: String) {
        val audio = withContext(Dispatchers.IO) { client.synthesizeSpeech(text) }
        if (audio == null || audio.isEmpty()) {
            Log.e("VoicePipeline", "TTS produced no audio, skipping playback")
            return
        }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val mediaPlayer = MediaPlayer()
                try {
                    mediaPlayer.setDataSource(ByteArrayMediaDataSource(audio))
                    mediaPlayer.setOnCompletionListener {
                        Log.d("VoicePipeline", "Playback finished")
                        it.release()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    mediaPlayer.setOnErrorListener { mp, what, extra ->
                        Log.e("VoicePipeline", "MediaPlayer Error: $what, $extra")
                        mp.release()
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
                    }
                } catch (e: Exception) {
                    Log.e("VoicePipeline", "Error playing TTS", e)
                    try { mediaPlayer.release() } catch (_: Exception) {}
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    fun stop() {
        wakeWordActive = false
        wakeWordEngine.stop()
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
