package com.teya.agent

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.teya.agent.harness.ConfigManager
import com.teya.agent.harness.HarnessService
import com.teya.agent.ui.face.AgentFace
import com.teya.agent.ui.face.AgentState
import com.teya.agent.ui.face.FaceBackground
import com.teya.agent.ui.face.stateColor
import com.teya.agent.ui.theme.TeyaTheme
import kotlinx.coroutines.delay

/** Typewriter reveal speed for Teya's transcript (ms/char). Kept just under speaking pace so a
 *  sentence types out during its own audio, then waits for the next — no drift, no lag. */
private const val CHAR_REVEAL_MS = 32L

class MainActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager
    private val _agentState = mutableStateOf(AgentState.IDLE)
    private val _userText = mutableStateOf("")
    private val _agentText = mutableStateOf("")

    // Ambient status motes (idea 3) — read fresh in onResume so a change made in Admin (a dream
    // run, a retune) shows up on the face without needing an app restart.
    private val _lastDreamAt = mutableStateOf(0L)
    private val _lastDreamNote = mutableStateOf("")
    private val _lastTuningChangedAt = mutableStateOf(0L)
    // BRAIN_OFF's caption fallback — HarnessService also broadcasts this live, but that broadcast
    // isn't queued: if this Activity's receiver registers even slightly late (e.g. right after
    // SetupActivity hands off), the live text is silently missed while the state change survives.
    // Reading the persisted copy in onResume means the real reason still shows, not a placeholder.
    private val _lastAuthErrorNote = mutableStateOf("")

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.teya.agent.STATE_UPDATE" -> {
                    val stateName = intent.getStringExtra("state") ?: return
                    Log.d("MainActivity", "State update received: $stateName")
                    try {
                        _agentState.value = AgentState.valueOf(stateName)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Invalid state name: $stateName", e)
                    }
                }
                HarnessService.ACTION_TRANSCRIPT -> {
                    intent.getStringExtra("user")?.let { _userText.value = it }
                    intent.getStringExtra("agent")?.let { _agentText.value = it }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val callPhoneGranted = permissions[Manifest.permission.CALL_PHONE] ?: false
        
        if (recordAudioGranted && callPhoneGranted) {
            startHarnessService()
        } else {
            Toast.makeText(this, "Permissions required for Teya to work", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configManager = ConfigManager(this)

        if (!configManager.isConfigured()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        checkAndRequestPermissions()

        setContent {
            TeyaTheme {
                MainScreen(
                    state = _agentState.value,
                    userText = _userText.value,
                    agentText = _agentText.value,
                    lastDreamAt = _lastDreamAt.value,
                    lastDreamNote = _lastDreamNote.value,
                    lastTuningChangedAt = _lastTuningChangedAt.value,
                    lastAuthErrorNote = _lastAuthErrorNote.value,
                    onOrbClick = {
                        Log.d("MainActivity", "Orb clicked, triggering voice loop")
                        val intent = Intent(this, HarnessService::class.java).apply {
                            action = HarnessService.ACTION_TRIGGER_VOICE
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to trigger voice", e)
                        }
                    },
                    onOpenAdmin = {
                        Log.d("MainActivity", "Long-press — opening Admin")
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction("com.teya.agent.STATE_UPDATE")
            addAction(HarnessService.ACTION_TRANSCRIPT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        _lastDreamAt.value = configManager.lastDreamAt
        _lastDreamNote.value = configManager.lastDreamNote
        _lastTuningChangedAt.value = configManager.lastTuningChangedAt
        _lastAuthErrorNote.value = configManager.lastAuthErrorNote
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to unregister receiver", e)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            // Household members live in Contacts; location feeds the ambient "home"/weather context.
            // Requested here too so the upgrade path (onboarding already done) still grants them.
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startHarnessService()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startHarnessService() {
        val intent = Intent(this, HarnessService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start service", e)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    state: AgentState,
    userText: String,
    agentText: String,
    onOrbClick: () -> Unit,
    onOpenAdmin: () -> Unit,
    lastDreamAt: Long = 0L,
    lastDreamNote: String = "",
    lastTuningChangedAt: Long = 0L,
    lastAuthErrorNote: String = "",
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = FaceBackground
    ) {
        // Whole face is the control: short tap = talk, long-press = open Admin. No visible button —
        // keeps the wall display clean and self-gates from kids/guests.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onOrbClick, onLongClick = onOpenAdmin)
        ) {
            AgentFace(state = state)

            // idea 3: ambient status motes — Admin state leaks back into the idle face passively,
            // tap to reveal (no hover on a touchscreen), auto-hides after a couple seconds.
            if (state == AgentState.IDLE) {
                StatusMotes(
                    lastDreamAt = lastDreamAt,
                    lastDreamNote = lastDreamNote,
                    lastTuningChangedAt = lastTuningChangedAt,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Live transcript, centred over the field (the user's words while listening,
            // Teya's reply while speaking).
            CenteredTranscript(
                state = state,
                userText = userText,
                agentText = agentText,
                lastAuthErrorNote = lastAuthErrorNote,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp)
            )
        }
    }
}

@Composable
private fun CenteredTranscript(
    state: AgentState,
    userText: String,
    agentText: String,
    lastAuthErrorNote: String = "",
    modifier: Modifier = Modifier
) {
    // Pick what to show based on the state, like a voice agent's transcript.
    val role: String
    val line: String
    when (state) {
        AgentState.SPEAKING -> { role = "Teya"; line = agentText }
        AgentState.THINKING -> {
            // STT result is available now — Voxtral transcribes once listening ends.
            val heard = userText.takeIf { it.isNotBlank() && it != "…" } ?: ""
            role = if (heard.isBlank()) "" else "You"; line = heard
        }
        AgentState.LISTENING -> { role = ""; line = "Listening…" }
        AgentState.IDLE -> {
            if (agentText.isNotBlank()) { role = "Teya"; line = agentText }
            else { role = ""; line = "Say “Hey Teya”" }
        }
        AgentState.BRAIN_OFF -> {
            role = ""
            line = agentText.ifBlank { lastAuthErrorNote.ifBlank { "Brain's offline — hold the screen to open Admin." } }
        }
    }

    // Teya's reply arrives one spoken sentence at a time (synced to the audio). To smooth the jump
    // from chunk to chunk, reveal her words character by character with a typewriter effect; other
    // states (the heard transcript, prompts) show instantly. It self-resyncs at each sentence: if a
    // sentence finishes typing before the next arrives, it simply waits.
    val animate = state == AgentState.SPEAKING
    var revealed by remember { mutableStateOf("") }
    LaunchedEffect(line, animate) {
        if (!animate) { revealed = line; return@LaunchedEffect }
        if (!line.startsWith(revealed)) revealed = ""      // new reply → restart the typewriter
        while (revealed.length < line.length) {
            revealed = line.substring(0, revealed.length + 1)
            delay(CHAR_REVEAL_MS)
        }
    }
    val shown = if (animate) revealed else line
    val display = if (state == AgentState.SPEAKING && shown.isNotEmpty()) "$shown▌" else shown

    val shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 2f), blurRadius = 30f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (role.isNotBlank()) {
            Text(
                text = role.uppercase(),
                color = stateColor(state),
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium,
                style = TextStyle(shadow = shadow)
            )
            Spacer(Modifier.height(10.dp))
        }
        if (line.isNotBlank()) {
            Text(
                text = display,
                color = if (role.isBlank()) Color.White.copy(alpha = 0.5f) else Color.White,
                fontSize = if (role.isBlank()) 15.sp else 19.sp,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                style = TextStyle(shadow = shadow)
            )
        }
    }
}

/** Idea 3 — the two dots that can appear on the idle face: a dream having run, tuning having
 *  changed. Each only renders when its underlying event has actually happened (no placeholder
 *  state), positioned clear of the centred transcript. */
@Composable
private fun StatusMotes(
    lastDreamAt: Long,
    lastDreamNote: String,
    lastTuningChangedAt: Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (lastDreamAt > 0L) {
            val label = "Last dream: ${relativeMoteTime(lastDreamAt)}" +
                if (lastDreamNote.isNotBlank()) " — $lastDreamNote" else ""
            StatusMote(
                color = Color(0xFFFFBE4B),
                label = label,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 40.dp),
            )
        }
        if (lastTuningChangedAt > 0L) {
            StatusMote(
                color = Color(0xFF45D0E0),
                label = "Voice tuning changed ${relativeMoteTime(lastTuningChangedAt)}",
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 40.dp),
            )
        }
    }
}

@Composable
private fun StatusMote(color: Color, label: String, modifier: Modifier = Modifier) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (revealed) { delay(2200); revealed = false }
    }
    Box(modifier) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
                .clickable { revealed = true }
        )
        if (revealed) {
            Box(
                Modifier
                    .offset(y = 14.dp)
                    .widthIn(max = 200.dp)
                    .background(Color(0xFF0B1012).copy(alpha = 0.95f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            ) {
                Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}

/** "today" / "1d ago" / "3d ago" — used only by the status motes' tap-to-reveal label. */
private fun relativeMoteTime(millis: Long): String {
    val days = (System.currentTimeMillis() - millis) / 86_400_000L
    return when { days <= 0L -> "today"; days == 1L -> "1d ago"; else -> "${days}d ago" }
}
