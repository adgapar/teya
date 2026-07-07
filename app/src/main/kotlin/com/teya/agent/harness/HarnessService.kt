package com.teya.agent.harness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.provider.AlarmClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.teya.agent.R
import com.teya.agent.brain.*
import com.teya.agent.persona.AgentTools
import com.teya.agent.persona.TeyaPersona
import com.teya.agent.safety.ContactAllowlistManager
import com.teya.agent.telephony.TelephonyActuator
import com.teya.agent.timers.TimerManager
import com.teya.agent.ui.face.AgentState
import com.teya.agent.voice.VoicePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class HarnessService : Service() {
    companion object {
        const val ACTION_TRIGGER_VOICE = "com.teya.agent.action.TRIGGER_VOICE"
        const val ACTION_TRANSCRIPT = "com.teya.agent.TRANSCRIPT_UPDATE"
        const val ACTION_TIMER_FIRED = "com.teya.agent.action.TIMER_FIRED"
        const val EXTRA_TIMER_LABEL = "timer_label"
        const val EXTRA_TIMER_ID = "timer_id"
        private const val CHANNEL_ID = "teya_harness_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "HarnessService"
        private const val FOLLOWUP_LISTEN_MS = 8000  // wait this long for a follow-up before ending
        private const val MAX_HISTORY = 10           // bounded conversation history sent to the model
        private const val MAX_TOOL_ROUNDS = 4        // cap tool→result→model loops per user turn
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val conversationActive = AtomicBoolean(false)

    private lateinit var voicePipeline: VoicePipeline
    private lateinit var brainClient: BrainClient
    private lateinit var telephonyActuator: TelephonyActuator
    private lateinit var allowlistManager: ContactAllowlistManager
    private lateinit var timerManager: TimerManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        
        val configManager = ConfigManager(this)
        allowlistManager = ContactAllowlistManager(this)
        telephonyActuator = TelephonyActuator(this, allowlistManager)
        voicePipeline = VoicePipeline(this)
        timerManager = TimerManager(this)
        
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
                override suspend fun processText(history: List<ChatMessage>, liveContext: String?): BrainResponse {
                    return BrainResponse("Please configure your Mistral API key in settings.")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        
        when (intent?.action) {
            ACTION_TRIGGER_VOICE -> {
                Log.d(TAG, "Manual trigger received")
                onTrigger()
            }
            ACTION_TIMER_FIRED -> {
                val id = intent.getIntExtra(EXTRA_TIMER_ID, -1)
                val label = intent.getStringExtra(EXTRA_TIMER_LABEL).orEmpty()
                Log.d(TAG, "Timer fired: id=$id label='$label'")
                if (id != -1) timerManager.onFired(id)
                announceTimer(label)
            }
            else -> {
                Log.d(TAG, "Service started, initializing wake word loop")
                startAgentLoop()
            }
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
                val reply = generateReply(history)

                updateUiState(AgentState.SPEAKING)
                if (reply.isNotBlank()) {
                    voicePipeline.textToSpeech(reply)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in conversation", e)
        } finally {
            voicePipeline.resumeWakeWord()
            updateUiState(AgentState.IDLE)
        }
    }

    /**
     * A Teya-owned timer fired: announce it in her own voice. If a conversation is already running,
     * skip speaking (the wake word is paused and we'd talk over it) and just log — a rare overlap.
     * Otherwise pause the wake word, speak, and resume.
     */
    private fun announceTimer(label: String) {
        val message = if (label.isBlank()) "Time's up — your timer is done." else "Time's up — your $label timer is done."
        if (conversationActive.get()) {
            Log.d(TAG, "Timer fired during a conversation; not speaking over it: $message")
            return
        }
        scope.launch {
            voicePipeline.pauseWakeWord()
            updateUiState(AgentState.SPEAKING)
            try {
                voicePipeline.textToSpeech(message)
            } catch (e: Exception) {
                Log.e(TAG, "Timer announcement failed", e)
            } finally {
                voicePipeline.resumeWakeWord()
                updateUiState(AgentState.IDLE)
            }
        }
    }

    /**
     * Ask the brain for a reply, running any tool the model requests and feeding the result back
     * so it can produce a final spoken answer (M7). Query tools (get_time, weather, …) depend on
     * this round-trip — the tool returns data, the model turns it into a sentence. Bounded by
     * [MAX_TOOL_ROUNDS]. Appends every turn (assistant tool-calls + tool results) to [history] so
     * context is preserved across the round-trip and into later turns.
     */
    private suspend fun generateReply(history: MutableList<ChatMessage>): String {
        // Refresh live device state once per user turn; the same snapshot is used across any tool
        // rounds within this turn (time won't drift meaningfully over a few seconds).
        val liveContext = buildLiveContext()
        repeat(MAX_TOOL_ROUNDS) {
            val response = brainClient.processText(history, liveContext)
            val toolCall = response.toolCall
            if (toolCall == null) {
                Log.d(TAG, "Brain response: ${response.speechResponse}")
                sendDebug(agent = response.speechResponse.ifBlank { "(no reply)" })
                history.add(ChatMessage(role = "assistant", content = response.speechResponse))
                return response.speechResponse
            }
            // The model wants to act: record the call, execute it, feed the result back.
            Log.d(TAG, "Tool call: ${toolCall.functionName}(${toolCall.arguments})")
            sendDebug(agent = "→ ${toolCall.functionName}(${toolCall.arguments})")
            history.add(ChatMessage(
                role = "assistant",
                content = response.speechResponse.ifBlank { null },
                toolCalls = listOf(toolCall),
            ))
            val result = executeTool(toolCall)
            Log.d(TAG, "Tool result: $result")
            history.add(ChatMessage(
                role = "tool",
                content = result,
                toolCallId = toolCall.id,
                name = toolCall.functionName,
            ))
        }
        val fallback = "Sorry, I got a bit tangled up just now."
        history.add(ChatMessage(role = "assistant", content = fallback))
        return fallback
    }

    /**
     * The actuator: run one tool and return a short natural-language result for the model to
     * phrase (never spoken directly). Add a `when` branch here for each new [AgentTools] entry.
     */
    private suspend fun executeTool(tool: ToolCall): String = when (tool.functionName) {
        "place_call" -> {
            val name = tool.arguments["name"] ?: ""
            Log.d(TAG, "Actuator: Placing call to $name")
            if (telephonyActuator.placeCall(name)) "Calling $name now."
            else "$name is not on the family's approved contacts, so the call was not placed."
        }
        "set_timer" -> {
            val seconds = tool.arguments["duration_seconds"]?.toIntOrNull()
            if (seconds == null || seconds <= 0) {
                "Could not set the timer — I need a positive duration in seconds."
            } else {
                val label = tool.arguments["label"].orEmpty()
                // Teya-owned (AlarmManager) so it's cancellable and she announces it herself.
                timerManager.start(seconds, label)
                val mins = seconds / 60
                val secs = seconds % 60
                val dur = buildString {
                    if (mins > 0) append("$mins min ")
                    if (secs > 0 || mins == 0) append("$secs sec")
                }.trim()
                "Timer started for $dur" + if (label.isNotBlank()) " ($label)." else "."
            }
        }
        "cancel_timer" -> {
            val cancelled = timerManager.cancel(tool.arguments["label"])
            when {
                cancelled.isEmpty() -> "There are no timers running to cancel."
                cancelled.size == 1 -> "Cancelled the ${cancelled[0].label.ifBlank { "timer" }}."
                else -> "Cancelled ${cancelled.size} timers."
            }
        }
        "cancel_alarm" -> {
            val label = tool.arguments["label"]
            val hour = tool.arguments["hour"]?.toIntOrNull()
            val minute = tool.arguments["minute"]?.toIntOrNull() ?: 0
            val all = tool.arguments["all"]?.toBoolean() ?: false
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                when {
                    !label.isNullOrBlank() -> {
                        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    }
                    hour != null -> {
                        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    }
                    all -> putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
                    else -> putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                }
            }
            if (!startSystemActivity(intent)) {
                "I couldn't reach the clock to cancel the alarm."
            } else when {
                !label.isNullOrBlank() -> "Asked the clock to cancel the $label alarm."
                hour != null -> "Asked the clock to cancel the %02d:%02d alarm.".format(hour, minute)
                all -> "Asked the clock to cancel all alarms."
                else -> "Asked the clock to cancel the next alarm."
            }
        }
        "set_alarm" -> {
            val hour = tool.arguments["hour"]?.toIntOrNull()
            val minute = tool.arguments["minute"]?.toIntOrNull() ?: 0
            if (hour == null || hour !in 0..23 || minute !in 0..59) {
                "Could not set the alarm — I need a valid time (hour 0-23, minute 0-59)."
            } else {
                val label = tool.arguments["label"].orEmpty()
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                if (startSystemActivity(intent)) {
                    "Alarm set for %02d:%02d".format(hour, minute) +
                        if (label.isNotBlank()) " ($label)." else "."
                } else {
                    "I couldn't set the alarm — no clock app handled it."
                }
            }
        }
        else -> "Unknown tool: ${tool.functionName}"
    }

    /**
     * The "live device state" block injected into the model's context every turn (ambient facts,
     * not tools): current time + location. The model reads these directly instead of spending a
     * tool round-trip to learn "now" / "where". Keep it small — only cheap, ubiquitous, read-only
     * facts belong here.
     */
    private fun buildLiveContext(): String {
        val now = LocalDateTime.now()
        // 12-hour format ("9:05 PM") so the model never has to do a 24h→12h conversion (it fumbles
        // that — reads "21:05" as "quarter past nine"). Hand it the time it will actually speak.
        val time = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH))
        val zone = ZoneId.systemDefault()
        val loc = lastKnownLocation()
        val locLine = if (loc != null) {
            "%.4f, %.4f (latitude, longitude)".format(loc.latitude, loc.longitude)
        } else {
            "unknown"
        }
        // Active timers ride in the ambient context so "how long left?" needs no tool round-trip.
        val timersLine = timerManager.active().joinToString("; ") { t ->
            val remaining = ((t.endAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            val name = t.label.ifBlank { "unnamed" }
            "$name: ${remaining / 60}m ${remaining % 60}s left"
        }.ifBlank { "none" }
        val context = """
            Live device state (authoritative — use these directly, do not ask the user):
            - Now: $time ($zone)
            - Location: $locLine
            - Active timers: $timersLine
        """.trimIndent()
        // TODO(H2): gate behind BuildConfig.DEBUG — this line logs location (PII).
        Log.d(TAG, "Live context: ${context.replace("\n", " | ")}")
        return context
    }

    /** Most-recent cached fix across providers; instant (no async wait). Null if none / no permission. */
    private fun lastKnownLocation(): Location? = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        Log.w(TAG, "Location permission not granted; omitting from live context")
        null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read location", e)
        null
    }

    /** Fire a system intent (e.g. AlarmClock) from the service. Returns false if nothing handled it. */
    private fun startSystemActivity(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start activity for ${intent.action}", e)
        false
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
