package com.teya.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.teya.agent.harness.HarnessService
import com.teya.agent.ui.face.AgentFace
import com.teya.agent.ui.face.AgentState
import com.teya.agent.ui.theme.TeyaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start the Harness Service
        startService(Intent(this, HarnessService::class.java))
        
        setContent {
            TeyaTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AgentFace(state = AgentState.IDLE)
                }
            }
        }
    }
}
