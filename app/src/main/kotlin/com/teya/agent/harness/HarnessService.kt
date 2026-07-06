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
import com.teya.agent.brain.*
import com.teya.agent.safety.ContactAllowlistManager
import com.teya.agent.telephony.TelephonyActuator
import com.teya.agent.ui.face.AgentState
import com.teya.agent.voice.VoicePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HarnessService : Service() {
    companion object {
        const val ACTION_TRIGGER_VOICE = "com.teya.agent.action.TRIGGER_VOICE"
        const val ACTION_TRANSCRIPT = "com.teya.agent.TRANSCRIPT_UPDATE"
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
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        
        val configManager = ConfigManager(this)
        allowlistManager = ContactAllowlistManager(this)
        telephonyActuator = TelephonyActuator(this, allowlistManager)
        voicePipeline = VoicePipeline(this)
        
        val apiKey = configManager.mistralApiKey
        if (!apiKey.isNullOrBlank()) {
            Log.d(TAG, "Initializing Mistral brain with key")
            val mistralClient = MistralClient(KtorClientFactory.create(), apiKey)
            brainClient = mistralClient
            voicePipeline.setMistralClient(mistralClient)
        } else {
            Log.w(TAG, "No API key found, using stub brain")
            brainClient = object : BrainClient {
                override suspend fun processText(input: String): BrainResponse {
                    return BrainResponse("Please configure your Mistral API key in settings.")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        
        if (intent?.action == ACTION_TRIGGER_VOICE) {
            Log.d(TAG, "Manual trigger received")
            scope.launch {
                handleVoiceTrigger()
            }
        } else {
            Log.d(TAG, "Service started, initializing wake word loop")
            startAgentLoop()
        }
        
        return START_STICKY
    }

    private fun startAgentLoop() {
        voicePipeline.startListening {
            Log.d(TAG, "Wake word detected!")
            scope.launch {
                handleVoiceTrigger()
            }
        }
    }

    private suspend fun handleVoiceTrigger() {
        try {
            // Prompt the user that we're listening.
            updateUiState(AgentState.SPEAKING)
            Log.d(TAG, "Prompting...")
            voicePipeline.textToSpeech("Yes?")

            // 1. Listen + STT (VAD-based capture, then Voxtral transcription)
            updateUiState(AgentState.LISTENING)
            sendDebug(user = "…", agent = "")   // clear previous turn in the dev overlay
            Log.d(TAG, "Listening for command...")
            val text = voicePipeline.listenForCommand()
            Log.d(TAG, "Input: $text")

            if (text.isBlank()) {
                sendDebug(user = "(didn't catch that)")
                updateUiState(AgentState.SPEAKING)
                voicePipeline.textToSpeech("Sorry, I didn't catch that.")
                updateUiState(AgentState.IDLE)
                return
            }
            sendDebug(user = text)

            updateUiState(AgentState.THINKING)
            // 2. Brain
            Log.d(TAG, "Thinking...")
            val response = brainClient.processText(text)
            Log.d(TAG, "Brain response: ${response.speechResponse}")

            // Surface the brain output (and any tool call) in the dev overlay.
            val toolSummary = response.toolCall?.let { "\n→ ${it.functionName}(${it.arguments})" } ?: ""
            sendDebug(agent = (response.speechResponse + toolSummary).ifBlank { "(no reply)" })

            updateUiState(AgentState.SPEAKING)
            // 3. TTS (skip if the model only returned a tool call with no words)
            if (response.speechResponse.isNotBlank()) {
                voicePipeline.textToSpeech(response.speechResponse)
            }

            // 4. Actuator
            response.toolCall?.let { tool ->
                if (tool.functionName == "place_call") {
                    val name = tool.arguments["name"] ?: ""
                    Log.d(TAG, "Actuator: Placing call to $name")
                    val success = telephonyActuator.placeCall(name)
                    if (!success) {
                        val denied = "I'm sorry, I can't call $name. They are not on the allowlist."
                        sendDebug(agent = denied)
                        voicePipeline.textToSpeech(denied)
                    }
                }
            }
            updateUiState(AgentState.IDLE)
        } catch (e: Exception) {
            Log.e(TAG, "Error in voice trigger loop", e)
            updateUiState(AgentState.IDLE)
        }
    }

    private fun sendDebug(user: String? = null, agent: String? = null) {
        val intent = Intent(ACTION_TRANSCRIPT).apply {
            setPackage(packageName)
            user?.let { putExtra("user", it) }
            agent?.let { putExtra("agent", it) }
        }
        sendBroadcast(intent)
    }

    private fun updateUiState(state: AgentState) {
        val intent = Intent("com.teya.agent.STATE_UPDATE").apply {
            putExtra("state", state.name)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Teya Agent")
            .setContentText("Teya is active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
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
        Log.d(TAG, "onDestroy")
        job.cancel()
        voicePipeline.stop()
    }
}
