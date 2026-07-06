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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.teya.agent.harness.ConfigManager
import com.teya.agent.harness.HarnessService
import com.teya.agent.ui.face.AgentFace
import com.teya.agent.ui.face.AgentState
import com.teya.agent.ui.theme.TeyaTheme

class MainActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager
    private val _agentState = mutableStateOf(AgentState.IDLE)
    private val _userText = mutableStateOf("")
    private val _agentText = mutableStateOf("")

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
                    onSettingsClick = {
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
            Manifest.permission.CALL_PHONE
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

@Composable
fun MainScreen(
    state: AgentState,
    userText: String,
    agentText: String,
    onOrbClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0A0A)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onOrbClick() }
            ) {
                AgentFace(state = state)
            }

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.5f)
                )
            }

            // Dev overlay: live state + last transcript + brain reply.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .systemBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth(0.6f)
            ) {
                Text(
                    text = "state: ${state.name.lowercase()}",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp
                )
                if (userText.isNotBlank()) {
                    Text(
                        text = "You: $userText",
                        color = Color(0xFF9AD0FF),
                        fontSize = 16.sp
                    )
                }
                if (agentText.isNotBlank()) {
                    Text(
                        text = "Teya: $agentText",
                        color = Color(0xFF9AFFC4),
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
