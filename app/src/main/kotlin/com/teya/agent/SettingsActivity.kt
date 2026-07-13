package com.teya.agent

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.teya.agent.harness.ConfigManager
import com.teya.agent.harness.HarnessService
import com.teya.agent.household.HouseholdManager
import com.teya.agent.household.LocationProbe
import com.teya.agent.household.Member
import com.teya.agent.household.MemoryEntry
import com.teya.agent.household.MemoryManager
import com.teya.agent.household.TeyaColors
import com.teya.agent.ui.admin.AdminSection
import com.teya.agent.ui.admin.ApiPanel
import com.teya.agent.ui.admin.ConstellationNav
import com.teya.agent.ui.admin.HomePanel
import com.teya.agent.ui.admin.HouseholdTapOverlay
import com.teya.agent.ui.admin.LanguagesPanel
import com.teya.agent.ui.admin.MemoryPanel
import com.teya.agent.ui.admin.PersonPager
import com.teya.agent.ui.admin.VoiceTuningPanel
import com.teya.agent.ui.admin.WakeWordSamplePanel
import com.teya.agent.ui.face.OnboardingCategory
import com.teya.agent.ui.face.OnboardingParticles
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Admin — the management console reached by long-pressing the wall face. Reviews & edits the
 * household, languages, home, and API key. Defaults to landscape (opens in-place on the wall) with
 * a manual rotate toggle to portrait for heavier text entry (the wall device is physically fixed,
 * so we drive orientation explicitly, never via the sensor).
 *
 * Full-bleed, one section in focus at a time — no dashboard sidebar. The SAME particle field the
 * face and onboarding use runs behind everything, morphing per section (see [OnboardingCategory]),
 * so opening/using Admin reads as the same continuous surface, not a different screen bolted on.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var config: ConfigManager
    private lateinit var household: HouseholdManager
    private lateinit var memory: MemoryManager

    private val loadedMembers = mutableStateOf<List<Member>?>(null)
    private val loadedMemories = mutableStateOf<List<MemoryEntry>?>(null)
    private val lastDream = mutableStateOf<String?>(null)
    private val home = mutableStateOf(LocationProbe.Home("Detecting location…", ""))
    // Real echo-cancelled barge-in (WebViewAecHost) needs "draw over other apps" — Android makes
    // the user grant this manually via system Settings, no runtime dialog exists for it. Re-read on
    // resume so coming back from that Settings screen (or Admin itself) reflects the current state.
    private val overlayGranted = mutableStateOf(false)
    private val lastAuthErrorAt = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        config = ConfigManager(this)
        household = HouseholdManager(this)
        memory = MemoryManager(this)
        lastDream.value = dreamText()

        lifecycleScope.launch { loadedMembers.value = household.members() }
        lifecycleScope.launch { loadedMemories.value = memory.all() }
        lifecycleScope.launch { home.value = LocationProbe.detect(this@SettingsActivity) }

        val initialTuning = VoiceTuning(
            vadThreshold = config.vadThreshold.toString(),
            vadSpeechDurationMs = config.vadSpeechDurationMs.toString(),
            vadSilenceDurationMs = config.vadSilenceDurationMs.toString(),
            bargeInGain = config.bargeInGain.toString(),
            bargeInGapMs = config.bargeInGapMs.toString(),
            wakeWordThreshold = config.wakeWordThreshold.toString(),
            wakeWordInputGain = config.wakeWordInputGain.toString(),
            wakeWordPatience = config.wakeWordPatience.toString(),
            ttsVolumeBoostDb = config.ttsVolumeBoostDb.toString(),
            speakerIdThreshold = config.speakerIdThreshold.toString(),
            speakerIdConfidentThreshold = config.speakerIdConfidentThreshold.toString(),
        )

        setContent {
            AdminScreen(
                membersLoaded = loadedMembers.value,
                memoriesLoaded = loadedMemories.value,
                lastDreamText = lastDream.value,
                initialLanguages = config.languages,
                initialHomeConfirmed = config.homeConfirmed,
                initialApiKey = config.mistralApiKey ?: "",
                initialTuning = initialTuning,
                initialTtsVoice = config.ttsVoice,
                home = home.value,
                overlayGranted = overlayGranted.value,
                onGrantOverlay = ::requestOverlayPermission,
                lastAuthErrorAt = lastAuthErrorAt.value,
                onToggleRotation = ::toggleOrientation,
                onDeleteMemory = { id ->
                    lifecycleScope.launch { memory.delete(id); loadedMemories.value = memory.all() }
                },
                onRunDream = {
                    // Fire the real (full) dreamer in the service — LLM consolidation + decay.
                    Toast.makeText(this, "Dreaming…", Toast.LENGTH_SHORT).show()
                    val before = config.lastDreamAt
                    startService(Intent(this, HarnessService::class.java).setAction(HarnessService.ACTION_RUN_DREAM))
                    lifecycleScope.launch {
                        // Poll until the service records the run (it writes lastDreamAt when done), ~8s cap.
                        var waited = 0
                        while (config.lastDreamAt == before && waited < 8000) { delay(400); waited += 400 }
                        loadedMemories.value = memory.all()
                        lastDream.value = dreamText()
                        Toast.makeText(
                            this@SettingsActivity,
                            "Memory dream: ${config.lastDreamNote.ifBlank { "done" }}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onSave = { members, langs, homeConfirmed, apiKey, tuning, ttsVoice ->
                    config.mistralApiKey = apiKey.trim()
                    config.languages = langs
                    config.homeConfirmed = homeConfirmed
                    config.ttsVoice = ttsVoice
                    if (tuning != initialTuning) config.lastTuningChangedAt = System.currentTimeMillis()
                    tuning.vadThreshold.toFloatOrNull()?.let { config.vadThreshold = it }
                    tuning.vadSpeechDurationMs.toIntOrNull()?.let { config.vadSpeechDurationMs = it }
                    tuning.vadSilenceDurationMs.toIntOrNull()?.let { config.vadSilenceDurationMs = it }
                    tuning.bargeInGain.toFloatOrNull()?.let { config.bargeInGain = it }
                    tuning.bargeInGapMs.toLongOrNull()?.let { config.bargeInGapMs = it }
                    tuning.wakeWordThreshold.toFloatOrNull()?.let { config.wakeWordThreshold = it }
                    tuning.wakeWordInputGain.toFloatOrNull()?.let { config.wakeWordInputGain = it }
                    tuning.wakeWordPatience.toIntOrNull()?.let { config.wakeWordPatience = it }
                    tuning.ttsVolumeBoostDb.toFloatOrNull()?.let { config.ttsVolumeBoostDb = it }
                    tuning.speakerIdThreshold.toFloatOrNull()?.let { config.speakerIdThreshold = it }
                    tuning.speakerIdConfidentThreshold.toFloatOrNull()?.let { config.speakerIdConfidentThreshold = it }
                    lifecycleScope.launch {
                        household.saveHousehold(members)
                        finish()
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted.value = Settings.canDrawOverlays(this)
        lastAuthErrorAt.value = config.lastAuthErrorAt
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    /** Jumps straight to this app's "display over other apps" toggle — there's no runtime dialog
     *  for this permission, only this system screen (see [overlayGranted] KDoc). */
    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    /** Human-readable "last dream run" line for the Admin monitor, or null if it never ran. */
    private fun dreamText(): String? {
        val at = config.lastDreamAt
        if (at == 0L) return null
        val days = (System.currentTimeMillis() - at) / 86_400_000L
        val ago = when { days <= 0L -> "today"; days == 1L -> "yesterday"; else -> "${days}d ago" }
        return "${config.lastDreamNote} ($ago)"
    }
}

/**
 * Working copies of the barge-in/wake-word tuning knobs (see ConfigManager), kept as strings so
 * the text fields can hold an in-progress edit; parsed back to numbers on Save (an unparseable
 * value is silently dropped, keeping the previously stored one — see SettingsActivity.onSave).
 */
data class VoiceTuning(
    val vadThreshold: String,
    val vadSpeechDurationMs: String,
    val vadSilenceDurationMs: String,
    val bargeInGain: String,
    val bargeInGapMs: String,
    val wakeWordThreshold: String,
    val wakeWordInputGain: String,
    val wakeWordPatience: String,
    val ttsVolumeBoostDb: String,
    val speakerIdThreshold: String,
    val speakerIdConfidentThreshold: String,
) {
    companion object {
        /** Mirrors ConfigManager's hardcoded defaults — shown as each field's hint and by "Reset to defaults". */
        val DEFAULTS = VoiceTuning(
            vadThreshold = "0.7",
            vadSpeechDurationMs = "50",
            vadSilenceDurationMs = "300",
            bargeInGain = "6.0",
            bargeInGapMs = "350",
            wakeWordThreshold = "0.53",
            wakeWordInputGain = "6.0",
            wakeWordPatience = "3",
            ttsVolumeBoostDb = "6.0",
            speakerIdThreshold = "0.6",
            speakerIdConfidentThreshold = "0.8",
        )
    }
}

@Composable
private fun AdminScreen(
    membersLoaded: List<Member>?,
    memoriesLoaded: List<MemoryEntry>?,
    lastDreamText: String?,
    initialLanguages: List<String>,
    initialHomeConfirmed: Boolean,
    initialApiKey: String,
    initialTuning: VoiceTuning,
    initialTtsVoice: String,
    home: LocationProbe.Home,
    overlayGranted: Boolean,
    onGrantOverlay: () -> Unit,
    lastAuthErrorAt: Long,
    onToggleRotation: () -> Unit,
    onDeleteMemory: (Int) -> Unit,
    onRunDream: () -> Unit,
    onSave: (List<Member>, List<String>, Boolean, String, VoiceTuning, String) -> Unit,
) {
    // systemBarsPadding lives HERE, on the shared container — not just on the content Column below.
    // Content and the particle field must measure within the same inset-adjusted bounds, or the
    // field centers on the true screen center while content centers on the inset-shrunk one (this
    // device's landscape nav bar sits on the right edge), reading as the field drifting off to the side.
    Box(Modifier.fillMaxSize().background(TeyaColors.Page).systemBarsPadding()) {
        if (membersLoaded == null) {
            OnboardingParticles(OnboardingCategory.HOUSEHOLD, 1, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TeyaColors.Accent)
            }
            return@Box
        }

        // Editable working copies — survive the rotate toggle (activity isn't recreated).
        val members = remember { mutableStateListOf<Member>().apply { addAll(membersLoaded) } }
        val langs = remember { mutableStateListOf<String>().apply { addAll(initialLanguages) } }
        var apiKey by remember { mutableStateOf(initialApiKey) }
        var homeConfirmed by remember { mutableStateOf(initialHomeConfirmed) }
        var tuning by remember { mutableStateOf(initialTuning) }
        var ttsVoice by remember { mutableStateOf(initialTtsVoice) }
        var section by remember { mutableStateOf(AdminSection.HOUSEHOLD) }
        var personIdx by remember { mutableStateOf(0) }
        var closing by remember { mutableStateOf(false) }

        val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
        val activeSlot = if (section == AdminSection.HOUSEHOLD && members.isNotEmpty())
            personIdx.coerceIn(0, members.size - 1) else -1
        // idea 6: closing settles the field back to a calm swirl for a beat before the Activity
        // actually finishes, instead of an instant cut to whatever's behind it.
        val activeCategory = if (closing) OnboardingCategory.DONE else section.category

        OnboardingParticles(
            category = activeCategory,
            memberCount = members.size.coerceAtLeast(1),
            modifier = Modifier.fillMaxSize(),
            activeSlot = activeSlot,
        )

        if (section == AdminSection.HOUSEHOLD && !closing) {
            HouseholdTapOverlay(members.size, activeSlot, onSelect = { personIdx = it })
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ADMIN", color = TeyaColors.Muted2, fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    AdminIconButton(Icons.Filled.ScreenRotation, "Rotate", onToggleRotation)
                    // No separate Save button — closing IS saving, folded into the same collapse
                    // swirl (idea 6): the field settling back down doubles as "wrapping up".
                    AdminIconButton(Icons.Filled.Close, "Save & close") {
                        closing = true
                        onSave(members.toList(), langs.toList(), homeConfirmed, apiKey, tuning, ttsVoice)
                    }
                }
            }

            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = section,
                    transitionSpec = {
                        (fadeIn(tween(360)) + scaleIn(initialScale = 0.96f, animationSpec = tween(360))) togetherWith
                            (fadeOut(tween(240)) + scaleOut(targetScale = 1.04f, animationSpec = tween(240)))
                    },
                    label = "adminSection",
                    modifier = Modifier.fillMaxSize(),
                ) { sec ->
                    when (sec) {
                        AdminSection.HOUSEHOLD -> PersonPager(
                            members = members,
                            personIdx = activeSlot.coerceAtLeast(0),
                            onPersonIdxChange = { personIdx = it },
                            onMemberChange = { i, m -> members[i] = m },
                            onRemove = { i -> members.removeAt(i) },
                            onAdd = { members.add(Member()); personIdx = members.size - 1 },
                            portrait = portrait,
                        )
                        AdminSection.MEMORY -> MemoryPanel(
                            memories = memoriesLoaded,
                            lastDreamText = lastDreamText,
                            onRunDream = onRunDream,
                            onDelete = onDeleteMemory,
                            portrait = portrait,
                        )
                        AdminSection.LANGUAGES -> LanguagesPanel(
                            selected = langs.toSet(),
                            onToggle = { l -> if (l in langs) langs.remove(l) else langs.add(l) },
                        )
                        AdminSection.HOME -> HomePanel(
                            city = home.city, coords = home.coords, confirmed = homeConfirmed,
                            onConfirmedChange = { homeConfirmed = it },
                        )
                        AdminSection.VOICE -> VoiceTuningPanel(
                            tuning = tuning, onChange = { tuning = it }, portrait = portrait,
                            overlayGranted = overlayGranted, onGrantOverlay = onGrantOverlay,
                            ttsVoice = ttsVoice, onTtsVoiceChange = { ttsVoice = it },
                        )
                        AdminSection.API -> ApiPanel(apiKey = apiKey, onChange = { apiKey = it }, lastAuthErrorAt = lastAuthErrorAt)
                        AdminSection.TRAINER -> WakeWordSamplePanel(members = members)
                    }
                }
            }

            ConstellationNav(
                selected = section, portrait = portrait, onSelect = { section = it },
                modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun AdminIconButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(6.dp)) {
        Icon(icon, contentDescription = desc, tint = TeyaColors.Muted, modifier = Modifier.size(17.dp))
    }
}
