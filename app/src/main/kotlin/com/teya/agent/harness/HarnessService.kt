package com.teya.agent.harness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.teya.agent.R
import com.teya.agent.brain.BrainClient
import com.teya.agent.brain.BrainResponse
import com.teya.agent.safety.ContactAllowlistManager
import com.teya.agent.telephony.TelephonyActuator
import com.teya.agent.voice.VoicePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HarnessService : Service() {
    companion object {
        private const val CHANNEL_ID = "teya_harness_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "HarnessService"
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var voicePipeline: VoicePipeline
    private lateinit var brainClient: BrainClient
    private lateinit var telephonyActuator: TelephonyActuator
    private lateinit var allowlistManager: ContactAllowlistManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        allowlistManager = ContactAllowlistManager(this)
        telephonyActuator = TelephonyActuator(this, allowlistManager)
        voicePipeline = VoicePipeline(this)
        
        // BrainClient stub implementation
        brainClient = object : BrainClient {
            override suspend fun processText(input: String): BrainResponse {
                return if (input.contains("call", ignoreCase = true)) {
                    val name = input.substringAfter("call ").trim()
                    BrainResponse(
                        speechResponse = "Calling $name",
                        toolCall = com.teya.agent.brain.ToolCall("place_call", mapOf("name" to name))
                    )
                } else {
                    BrainResponse("I didn't quite catch that.")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        startAgentLoop()
        return START_STICKY
    }

    private fun startAgentLoop() {
        voicePipeline.startListening {
            scope.launch {
                handleVoiceTrigger()
            }
        }
    }

    private suspend fun handleVoiceTrigger() {
        // 1. STT
        val text = voicePipeline.speechToText(Any()) // Placeholder
        Log.d(TAG, "Recognized: $text")

        // 2. Brain
        val response = brainClient.processText(text)
        
        // 3. TTS
        voicePipeline.textToSpeech(response.speechResponse)

        // 4. Actuator
        response.toolCall?.let { tool ->
            if (tool.functionName == "place_call") {
                val name = tool.arguments["name"] ?: ""
                val success = telephonyActuator.placeCall(name)
                if (!success) {
                    voicePipeline.textToSpeech("I'm sorry, I can't call $name. They are not on the allowlist.")
                }
            }
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Teya Agent")
            .setContentText("Teya is listening...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Teya Harness Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
