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
import com.teya.agent.persona.AgentTools
import com.teya.agent.persona.TeyaPersona
import com.teya.agent.safety.ContactAllowlistManager
import com.teya.agent.telephony.TelephonyActuator
import com.teya.agent.ui.face.AgentState
import com.teya.agent.voice.VoicePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class HarnessService : Service() {
    companion object {
        const val ACTION_TRIGGER_VOICE = "com.teya.agent.action.TRIGGER_VOICE"
        const val ACTION_TRANSCRIPT = "com.teya.agent.TRANSCRIPT_UPDATE"
        private const val CHANNEL_ID = "teya_harness_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "HarnessService"
        private const val FOLLOWUP_LISTEN_MS = 8000  // wait this long for a follow-up before ending
        private const val MAX_HISTORY = 10           // bounded conversation history sent to the model
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val conversationActive = AtomicBoolean(false)

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
            val mistralClient = MistralClient(
                KtorClientFactory.create(),
                apiKey,
                TeyaPersona.systemPrompt,
                AgentTools.all
            )
            brainClient = mistralClient
            voicePipeline.setMistralClient(mistralClient)
            scope.launch { mistralClient.warmUp() }  // warm the TLS/connection pool at startup
        } else {
            Log.w(TAG, "No API key found, using stub brain")
            brainClient = object : BrainClient {
                override suspend fun processText(history: List<ChatMessage>): BrainResponse {
                    return BrainResponse("Please configure your Mistral API key in settings.")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        
        if (intent?.action == ACTION_TRIGGER_VOICE) {
            Log.d(TAG, "Manual trigger received")
            onTrigger()
        } else {
            Log.d(TAG, "Service started, initializing wake word loop")
            startAgentLoop()
        }
        
        return START_STICKY
    }

    private fun startAgentLoop() {
        voicePipeline.startListening {
            Log.d(TAG, "Wake word detected!")
            onTrigger()
        }
    }

    /** Entry point for a tap or wake-word trigger. Guards against overlapping conversations. */
    private fun onTrigger() {
        if (!conversationActive.compareAndSet(false, true)) {
            Log.d(TAG, "Conversation already active — ignoring trigger")
            return
        }
        scope.launch {
            try {
                runConversation()
            } finally {
                conversationActive.set(false)
            }
        }
    }

    /**
     * A multi-turn conversation: prompt once, then keep listening for follow-ups (no wake word
     * needed) until the user is silent for FOLLOWUP_LISTEN_MS. History is kept so the model has
     * context across turns. The wake-word recorder is paused for the whole session.
     */
    private suspend fun runConversation() {
        val history = mutableListOf<ChatMessage>()
        voicePipeline.pauseWakeWord()
        try {
            updateUiState(AgentState.SPEAKING)
            Log.d(TAG, "Prompting...")
            voicePipeline.textToSpeech("Yes?")

            while (true) {
                updateUiState(AgentState.LISTENING)
                sendDebug(user = "…", agent = "")
                Log.d(TAG, "Listening for command...")
                val text = voicePipeline.listenForCommand(FOLLOWUP_LISTEN_MS)
                if (text.isBlank()) {
                    Log.d(TAG, "No follow-up heard — ending conversation")
                    break
                }
                sendDebug(user = text)
                history.add(ChatMessage("user", text))
                trimHistory(history)

                updateUiState(AgentState.THINKING)
                Log.d(TAG, "Thinking...")
                val response = brainClient.processText(history)
                Log.d(TAG, "Brain response: ${response.speechResponse}")

                val toolSummary = response.toolCall?.let { "\n→ ${it.functionName}(${it.arguments})" } ?: ""
                sendDebug(agent = (response.speechResponse + toolSummary).ifBlank { "(action)" })
                history.add(ChatMessage("assistant", response.speechResponse.ifBlank { "(action taken)" }))

                updateUiState(AgentState.SPEAKING)
                if (response.speechResponse.isNotBlank()) {
                    voicePipeline.textToSpeech(response.speechResponse)
                }
                response.toolCall?.let { handleToolCall(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in conversation", e)
        } finally {
            voicePipeline.resumeWakeWord()
            updateUiState(AgentState.IDLE)
        }
    }

    private suspend fun handleToolCall(tool: ToolCall) {
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

    private fun trimHistory(history: MutableList<ChatMessage>) {
        while (history.size > MAX_HISTORY) history.removeAt(0)
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
