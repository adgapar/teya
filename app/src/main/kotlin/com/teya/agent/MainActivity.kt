package com.teya.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.core.content.ContextCompat
import com.teya.agent.harness.ConfigManager
import com.teya.agent.harness.HarnessService
import com.teya.agent.ui.face.AgentFace
import com.teya.agent.ui.face.AgentState
import com.teya.agent.ui.theme.TeyaTheme

class MainActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager

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
                    onOrbClick = { 
                        // Trigger the pipeline manually via Intent to Service
                        val intent = Intent(this, HarnessService::class.java).apply {
                            action = HarnessService.ACTION_TRIGGER_VOICE
                        }
                        startService(intent)
                    },
                    onSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun MainScreen(onOrbClick: () -> Unit, onSettingsClick: () -> Unit) {
    // In a real app, state would be observed from the Service/ViewModel
    var agentState by remember { mutableStateOf(AgentState.IDLE) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onOrbClick() }
        ) {
            AgentFace(state = agentState)
        }

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}
