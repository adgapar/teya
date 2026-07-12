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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.teya.agent.household.Member
import com.teya.agent.household.TeyaColors
import com.teya.agent.household.VoiceSample
import com.teya.agent.safety.TeyaDatabase
import com.teya.agent.voice.speaker.CamPlusPlusSpeakerEmbedder
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SAMPLE_RATE = 16000
private const val MAX_RECORD_MS = 6000
private const val WAV_HEADER_BYTES = 44

/**
 * Records a short clip on this phone, tagged to a household member — doubles as: (1) a per-member
 * voiceprint enrollment sample (see `docs/roadmap.md` → Household setup & personalization —
 * per-speaker voice ID), embedded via [CamPlusPlusSpeakerEmbedder] and stored in Room
 * (`VoiceSample`); and (2) raw material for a future custom "Hey Teya" wake-word model, still
 * saved as a WAV to this app's external files dir:
 *   adb pull /sdcard/Android/data/com.teya.agent/files/wake_word_samples ~/Desktop/
 *
 * A member must be selected before recording — enrollment is the panel's primary purpose now,
 * the wake-word training clips are the byproduct.
 */
@Composable
fun WakeWordSamplePanel(members: List<Member>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceSampleDao = remember { TeyaDatabase.get(context).voiceSampleDao() }
    val embedder = remember { CamPlusPlusSpeakerEmbedder(context) }
    DisposableEffect(Unit) { onDispose { embedder.close() } }

    val enrollable = remember(members) { members.filter { it.lookupKey != null && it.hasName } }
    var selected by remember { mutableStateOf<Member?>(null) }

    val outputDir = remember {
        File(context.getExternalFilesDir(null), "wake_word_samples").apply { mkdirs() }
    }
    val recorder = remember { WakeWordSampleRecorder() }
    var recording by remember { mutableStateOf(false) }
    var savedCount by remember { mutableStateOf(outputDir.listFiles { f -> f.extension == "wav" }?.size ?: 0) }
    var status by remember { mutableStateOf("") }

    var sampleCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    suspend fun reloadCounts() {
        sampleCounts = voiceSampleDao.getAll().groupingBy { it.lookupKey }.eachCount()
    }
    LaunchedEffect(Unit) { reloadCounts() }

    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val canRecord = hasPermission && selected != null

    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AdminEyebrow("VOICE ID / WAKE WORD SAMPLES")
        Spacer(Modifier.height(20.dp))

        if (enrollable.isEmpty()) {
            Text(
                "Add + save a household member first, then come back here to enroll their voice.",
                color = TeyaColors.Muted, fontSize = 11.5.sp, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(enrollable) { member ->
                    val isSelected = selected?.lookupKey == member.lookupKey
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) TeyaColors.AccentSoft else TeyaColors.Card)
                            .clickable { selected = member }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            member.displayName,
                            color = if (isSelected) TeyaColors.Accent else TeyaColors.Ink,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Box(
            Modifier
                .size(84.dp)
                .background(if (recording) TeyaColors.Danger else TeyaColors.Accent, CircleShape)
                .clickable(enabled = canRecord) {
                    if (!recording) {
                        recording = true
                        status = "Recording…"
                        recorder.start()
                    } else {
                        recording = false
                        val wav = recorder.stopAndGetWav()
                        val member = selected
                        if (wav == null || member?.lookupKey == null) {
                            status = "No audio captured — try again"
                        } else {
                            val take = savedCount + 1
                            val file = File(outputDir, "hey_teya_%02d.wav".format(take))
                            file.writeBytes(wav)
                            savedCount = take

                            val pcm = wavToPcm(wav)
                            scope.launch {
                                status = "Processing…"
                                try {
                                    val embedding = embedder.embed(pcm)
                                    voiceSampleDao.insert(
                                        VoiceSample(
                                            lookupKey = member.lookupKey,
                                            embedding = embedding.toVoiceSampleBytes(),
                                            recordedAt = System.currentTimeMillis(),
                                        )
                                    )
                                    reloadCounts()
                                    status = "Saved take $take for ${member.displayName}"
                                } catch (e: Exception) {
                                    status = "Enrollment failed — clip saved as a wake-word sample only"
                                }
                            }
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

        Spacer(Modifier.height(16.dp))
        Text(
            when {
                !hasPermission -> "Mic permission not granted"
                selected == null && enrollable.isNotEmpty() -> "Pick who's recording, then tap to record"
                status.isNotBlank() -> status
                else -> "Tap to record, tap again to stop & save"
            },
            color = TeyaColors.Muted, fontSize = 11.5.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )

        if (sampleCounts.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("ENROLLED VOICES", color = TeyaColors.Muted2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.widthIn(max = 300.dp)) {
                items(members.filter { it.lookupKey in sampleCounts.keys }) { member ->
                    val count = sampleCounts[member.lookupKey] ?: 0
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${member.displayName} — $count sample${if (count == 1) "" else "s"}",
                            color = TeyaColors.Ink, fontSize = 12.sp,
                        )
                        Text(
                            "Clear",
                            color = TeyaColors.Danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                val key = member.lookupKey ?: return@clickable
                                scope.launch { voiceSampleDao.deleteByMember(key); reloadCounts() }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Strips the 44-byte WAV header this panel writes and returns the remaining PCM as samples. */
private fun wavToPcm(wav: ByteArray): ShortArray {
    val pcmBytes = wav.copyOfRange(WAV_HEADER_BYTES, wav.size)
    val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
    return ShortArray(pcmBytes.size / 2) { buf.short }
}

/** float32-LE round-trip, matching MemoryManager's embedding BLOB convention. */
private fun FloatArray.toVoiceSampleBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buf.putFloat(it) }
    return buf.array()
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
