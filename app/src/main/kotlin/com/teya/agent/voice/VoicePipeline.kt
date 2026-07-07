package com.teya.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import com.teya.agent.brain.MistralClient
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
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
        private const val SILENCE_RMS_THRESHOLD = 500.0                   // energy floor for "speech"
        private const val TRAILING_SILENCE_MS = 800                       // silence that ends a command
        private const val MAX_INITIAL_SILENCE_MS = 4000                   // give up if nobody speaks
        private const val MAX_RECORDING_MS = 12000                        // hard cap on a single command
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
     * Records a spoken command using simple energy-based VAD (stops after a short
     * trailing silence), then transcribes it via Voxtral. The always-on wake-word
     * recorder is paused for the duration to avoid microphone contention.
     * Returns "" on any failure (no client, no speech, transcription error).
     */
    suspend fun listenForCommand(): String = withContext(Dispatchers.IO) {
        val client = mistralClient ?: run {
            Log.e("VoicePipeline", "MistralClient not set, cannot transcribe")
            return@withContext ""
        }

        // Free the mic from the always-on wake-word recorder.
        if (wakeWordActive) wakeWordEngine.stop()
        try {
            val wavFile = recordWithVad()
            if (wavFile == null) {
                Log.d("VoicePipeline", "No command audio captured")
                return@withContext ""
            }
            client.transcribe(wavFile)
        } catch (e: Exception) {
            Log.e("VoicePipeline", "Error during STT", e)
            ""
        } finally {
            // Resume wake-word listening.
            if (wakeWordActive) wakeWordEngine.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordWithVad(): File? {
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
                if (!speechStarted && elapsedMs >= MAX_INITIAL_SILENCE_MS) break
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

        // Fetch + decode off the main thread; the API returns base64 audio in JSON (see MistralClient).
        val audio = withContext(Dispatchers.IO) { client.synthesizeSpeech(text) }
        if (audio == null || audio.isEmpty()) {
            Log.e("VoicePipeline", "TTS produced no audio, skipping playback")
            return
        }

        // Play from memory — no temp file on disk.
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
