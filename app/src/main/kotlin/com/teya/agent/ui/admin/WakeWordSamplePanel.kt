package com.teya.agent.ui.admin

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.teya.agent.household.TeyaColors
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SAMPLE_RATE = 16000
private const val MAX_RECORD_MS = 6000

/**
 * Temporary tool for bootstrapping the WakeWord Trainer's personal_samples/ set: records a short
 * "hey teya" clip on THIS phone (matching the wall device's real mic/acoustics — useful for taking
 * several takes at different distances from the phone) and saves it as a WAV straight to this app's
 * external files dir. No network involved — once done recording, pull the whole folder off with:
 *   adb pull /sdcard/Android/data/com.teya.agent/files/wake_word_samples ~/Desktop/
 * then use the trainer's own "Manual Sample Import" file picker to select them all.
 *
 * Whole feature is scaffolding for one training pass, not a lasting product surface — delete this
 * file, its AdminSection.TRAINER entry (AdminComposables.kt), and OnboardingCategory.TRAINER
 * (OnboardingParticles.kt) once done.
 */
@Composable
fun WakeWordSamplePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val outputDir = remember {
        File(context.getExternalFilesDir(null), "wake_word_samples").apply { mkdirs() }
    }
    val recorder = remember { WakeWordSampleRecorder() }
    var recording by remember { mutableStateOf(false) }
    var savedCount by remember { mutableStateOf(outputDir.listFiles { f -> f.extension == "wav" }?.size ?: 0) }
    var status by remember { mutableStateOf("") }

    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AdminEyebrow("WAKE WORD SAMPLES")
        Spacer(Modifier.height(28.dp))

        Box(
            Modifier
                .size(84.dp)
                .background(if (recording) TeyaColors.Danger else TeyaColors.Accent, CircleShape)
                .clickable(enabled = hasPermission) {
                    if (!recording) {
                        recording = true
                        status = "Recording…"
                        recorder.start()
                    } else {
                        recording = false
                        val wav = recorder.stopAndGetWav()
                        status = if (wav == null) {
                            "No audio captured — try again"
                        } else {
                            val take = savedCount + 1
                            val file = File(outputDir, "hey_teya_%02d.wav".format(take))
                            file.writeBytes(wav)
                            savedCount = take
                            "Saved take $take"
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (recording) "STOP" else "REC",
                color = TeyaColors.Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            when {
                !hasPermission -> "Mic permission not granted"
                status.isNotBlank() -> "$status  ·  $savedCount total on this phone"
                else -> "Tap to record \"hey teya\", tap again to stop & save"
            },
            color = TeyaColors.Muted, fontSize = 11.5.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

/**
 * Fixed-format (16kHz/mono/16-bit) raw recorder — no VAD/trimming, just press-to-start/press-to-stop
 * capped at [MAX_RECORD_MS]. Runs its own AudioRecord independent of VoicePipeline's; Android mixes
 * concurrent same-app captures fine, so this coexisting with the always-on wake-word engine is safe,
 * just not worth the complexity of pausing it for a debug-only tool.
 */
private class WakeWordSampleRecorder {
    private var audioRecord: AudioRecord? = null
    @Volatile private var stopped = false
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(pcm) { pcm.reset() }
        stopped = false
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 4096),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        audioRecord = recorder
        recorder.startRecording()
        thread = Thread {
            val buf = ShortArray(1024)
            var elapsedMs = 0
            while (!stopped && elapsedMs < MAX_RECORD_MS) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    val bytes = ByteArray(read * 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, read)
                    synchronized(pcm) { pcm.write(bytes) }
                    elapsedMs += read * 1000 / SAMPLE_RATE
                }
            }
        }.also { it.start() }
    }

    /** Stops recording and returns a 16kHz/mono/16-bit WAV, or null if nothing was captured. */
    fun stopAndGetWav(): ByteArray? {
        stopped = true
        thread?.join(500)
        thread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        if (bytes.isEmpty()) return null
        return wrapWav(bytes)
    }

    private fun wrapWav(pcmData: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcmData.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)
        return header.array() + pcmData
    }
}
