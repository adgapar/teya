package com.teya.agent

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.teya.agent.harness.ConfigManager
import com.teya.agent.harness.HarnessService
import com.teya.agent.household.HomeConfirmCard
import com.teya.agent.household.HouseholdManager
import com.teya.agent.household.LanguagePicker
import com.teya.agent.household.LocationProbe
import com.teya.agent.household.Member
import com.teya.agent.household.MemberEditor
import com.teya.agent.household.MemoryEntry
import com.teya.agent.household.MemoryManager
import com.teya.agent.household.MemorySectionBody
import com.teya.agent.household.Note
import com.teya.agent.household.PrimaryButton
import com.teya.agent.household.SectionHead
import com.teya.agent.household.TeyaColors
import com.teya.agent.household.TeyaField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Admin — the management console reached by long-pressing the wall face. Reviews & edits the
 * household, languages, home, and API key. Defaults to landscape (opens in-place on the wall) with
 * a manual rotate toggle to portrait for heavier text entry (the wall device is physically fixed,
 * so we drive orientation explicitly, never via the sensor). Responsive: two-pane in landscape,
 * single-column stacked in portrait.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var config: ConfigManager
    private lateinit var household: HouseholdManager
    private lateinit var memory: MemoryManager

    private val loadedMembers = mutableStateOf<List<Member>?>(null)
    private val loadedMemories = mutableStateOf<List<MemoryEntry>?>(null)
    private val lastDream = mutableStateOf<String?>(null)
    private val home = mutableStateOf(LocationProbe.Home("Detecting location…", ""))

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

        setContent {
            AdminScreen(
                membersLoaded = loadedMembers.value,
                memoriesLoaded = loadedMemories.value,
                lastDreamText = lastDream.value,
                initialLanguages = config.languages,
                initialHomeConfirmed = config.homeConfirmed,
                initialApiKey = config.mistralApiKey ?: "",
                home = home.value,
                onToggleRotation = ::toggleOrientation,
                onClose = ::finish,
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
                onSave = { members, langs, homeConfirmed, apiKey ->
                    config.mistralApiKey = apiKey.trim()
                    config.languages = langs
                    config.homeConfirmed = homeConfirmed
                    lifecycleScope.launch {
                        household.saveHousehold(members)
                        finish()
                    }
                },
            )
        }
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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

private enum class AdminSection(val title: String) {
    HOUSEHOLD("Household"), MEMORY("Memory"), LANGUAGES("Languages"), HOME("Home location"), API("API")
}

@Composable
private fun AdminScreen(
    membersLoaded: List<Member>?,
    memoriesLoaded: List<MemoryEntry>?,
    lastDreamText: String?,
    initialLanguages: List<String>,
    initialHomeConfirmed: Boolean,
    initialApiKey: String,
    home: LocationProbe.Home,
    onToggleRotation: () -> Unit,
    onClose: () -> Unit,
    onDeleteMemory: (Int) -> Unit,
    onRunDream: () -> Unit,
    onSave: (List<Member>, List<String>, Boolean, String) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(TeyaColors.Page)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            AdminTopBar(onToggleRotation, onClose)

            if (membersLoaded == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TeyaColors.Accent)
                }
                return@Column
            }

            // Editable working copies — survive the rotate toggle (activity isn't recreated).
            val members = remember { mutableStateListOf<Member>().apply { addAll(membersLoaded) } }
            val langs = remember { mutableStateListOf<String>().apply { addAll(initialLanguages) } }
            var apiKey by remember { mutableStateOf(initialApiKey) }
            var homeConfirmed by remember { mutableStateOf(initialHomeConfirmed) }

            val onMembersChange: (List<Member>) -> Unit = { members.clear(); members.addAll(it) }
            val onToggleLang: (String) -> Unit = { l -> if (l in langs) langs.remove(l) else langs.add(l) }

            val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

            Box(Modifier.weight(1f)) {
                if (portrait) {
                    PortraitContent(members, onMembersChange, langs, onToggleLang, home, homeConfirmed,
                        { homeConfirmed = it }, apiKey, { apiKey = it }, memoriesLoaded, lastDreamText,
                        onRunDream, onDeleteMemory)
                } else {
                    LandscapeContent(members, onMembersChange, langs, onToggleLang, home, homeConfirmed,
                        { homeConfirmed = it }, apiKey, { apiKey = it }, memoriesLoaded, lastDreamText,
                        onRunDream, onDeleteMemory)
                }
            }

            Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 20.dp)) {
                PrimaryButton("Save changes", modifier = Modifier.fillMaxWidth()) {
                    onSave(members.toList(), langs.toList(), homeConfirmed, apiKey)
                }
            }
        }
    }
}

@Composable
private fun AdminTopBar(onToggleRotation: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Admin", color = TeyaColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚙ TEYA", color = TeyaColors.Muted, fontSize = 12.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace)
            IconChip(Icons.Filled.ScreenRotation, "Rotate", onToggleRotation)
            IconChip(Icons.Filled.Close, "Close", onClose)
        }
    }
}

@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(TeyaColors.Field, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = TeyaColors.Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PortraitContent(
    members: List<Member>, onMembersChange: (List<Member>) -> Unit,
    langs: List<String>, onToggleLang: (String) -> Unit,
    home: LocationProbe.Home, homeConfirmed: Boolean, onHomeConfirmedChange: (Boolean) -> Unit,
    apiKey: String, onApiKeyChange: (String) -> Unit,
    memories: List<MemoryEntry>?, lastDreamText: String?, onRunDream: () -> Unit, onDeleteMemory: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 8.dp)
    ) {
        SectionBlock(AdminSection.HOUSEHOLD) { HouseholdSectionBody(members, onMembersChange) }
        SectionBlock(AdminSection.MEMORY) { MemorySectionBody(memories, members, lastDreamText, onRunDream, onDeleteMemory) }
        SectionBlock(AdminSection.LANGUAGES) { LanguagePicker(langs.toSet(), onToggleLang) }
        SectionBlock(AdminSection.HOME) {
            HomeConfirmCard(home.city, home.coords, homeConfirmed, onHomeConfirmedChange)
        }
        SectionBlock(AdminSection.API) { ApiSectionBody(apiKey, onApiKeyChange) }
    }
}

@Composable
private fun LandscapeContent(
    members: List<Member>, onMembersChange: (List<Member>) -> Unit,
    langs: List<String>, onToggleLang: (String) -> Unit,
    home: LocationProbe.Home, homeConfirmed: Boolean, onHomeConfirmedChange: (Boolean) -> Unit,
    apiKey: String, onApiKeyChange: (String) -> Unit,
    memories: List<MemoryEntry>?, lastDreamText: String?, onRunDream: () -> Unit, onDeleteMemory: (Int) -> Unit,
) {
    var selected by remember { mutableStateOf(AdminSection.HOUSEHOLD) }
    Row(Modifier.fillMaxSize()) {
        // Left: section nav
        Column(
            Modifier.width(220.dp).fillMaxHeight().padding(start = 22.dp, end = 12.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AdminSection.entries.forEach { section ->
                NavItem(section.title, section == selected) { selected = section }
            }
        }
        // Right: detail/editor for the selected section
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 22.dp, top = 8.dp)
        ) {
            SectionHead(selected.title)
            Spacer(Modifier.height(12.dp))
            when (selected) {
                AdminSection.HOUSEHOLD -> HouseholdSectionBody(members, onMembersChange)
                AdminSection.MEMORY -> MemorySectionBody(memories, members, lastDreamText, onRunDream, onDeleteMemory)
                AdminSection.LANGUAGES -> LanguagePicker(langs.toSet(), onToggleLang)
                AdminSection.HOME -> HomeConfirmCard(home.city, home.coords, homeConfirmed, onHomeConfirmedChange)
                AdminSection.API -> ApiSectionBody(apiKey, onApiKeyChange)
            }
        }
    }
}

@Composable
private fun NavItem(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (selected) TeyaColors.AccentSoft else TeyaColors.Page, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            color = if (selected) TeyaColors.Accent else TeyaColors.Muted,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SectionBlock(section: AdminSection, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHead(section.title)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun HouseholdSectionBody(members: List<Member>, onMembersChange: (List<Member>) -> Unit) {
    if (members.isEmpty()) {
        Note("No household members yet. Add the family so Teya knows who’s who.")
        Spacer(Modifier.height(12.dp))
    }
    MemberEditor(members, onMembersChange)
}

@Composable
private fun ApiSectionBody(apiKey: String, onApiKeyChange: (String) -> Unit) {
    TeyaField(apiKey, onApiKeyChange, "sk-…", label = "Mistral API key", isPassword = true)
}
