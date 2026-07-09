package com.teya.agent.harness

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.teya.agent.calendar.CalendarManager
import com.teya.agent.household.HouseholdManager
import com.teya.agent.household.Member
import com.teya.agent.household.MemoryManager
import com.teya.agent.persona.AgentTools
import com.teya.agent.persona.TeyaPersona
import com.teya.agent.safety.ContactAllowlistManager
import com.teya.agent.shopping.ShoppingListManager
import com.teya.agent.telephony.TelephonyActuator
import com.teya.agent.timers.TimerManager
import com.teya.agent.ui.face.AgentState
import com.teya.agent.voice.VoicePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
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
        const val ACTION_RUN_DREAM = "com.teya.agent.action.RUN_DREAM"
        const val EXTRA_TIMER_LABEL = "timer_label"
        const val EXTRA_TIMER_ID = "timer_id"
        private const val CHANNEL_ID = "teya_harness_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "HarnessService"
        private const val FOLLOWUP_LISTEN_MS = 8000  // wait this long for a follow-up before ending
        private const val BARGE_IN_GAP_MS = 900L      // deliberate pause after each sentence — see runConversation's speaker loop
        private const val MAX_HISTORY = 10           // bounded conversation history sent to the model
        private const val MAX_TOOL_ROUNDS = 4        // cap tool→result→model loops per user turn
        private const val DREAM_REQUEST_CODE = 7     // PendingIntent id for the nightly dream alarm
        private const val DREAM_HOUR = 3             // run the dreamer at ~3 AM (device idle + charging)
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val conversationActive = AtomicBoolean(false)
    // The in-flight "think + speak" round, if any — cancelled on barge-in (see onTrigger).
    @Volatile private var activeTurnJob: Job? = null

    private lateinit var voicePipeline: VoicePipeline
    private lateinit var brainClient: BrainClient
    private lateinit var telephonyActuator: TelephonyActuator
    private lateinit var allowlistManager: ContactAllowlistManager
    private lateinit var timerManager: TimerManager
    private lateinit var calendarManager: CalendarManager
    private lateinit var shoppingList: ShoppingListManager
    private lateinit var householdManager: HouseholdManager
    private lateinit var memoryManager: MemoryManager

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
        calendarManager = CalendarManager(this)
        shoppingList = ShoppingListManager(this)
        householdManager = HouseholdManager(this)
        memoryManager = MemoryManager(this)

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

        scheduleDream()  // nightly memory decay/consolidation (~3 AM, AlarmManager)
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
            ACTION_RUN_DREAM -> {
                Log.d(TAG, "Dream alarm fired — running memory decay")
                scope.launch { runDream() }
            }
            else -> {
                Log.d(TAG, "Service started, initializing wake word loop")
                startAgentLoop()
            }
        }
        
        return START_STICKY
    }

    private fun startAgentLoop() {
        voicePipeline.startListening(
            onWakeWord = {
                Log.d(TAG, "Wake word detected!")
                onTrigger()
            },
            onBargeIn = { onBargeIn() }
        )
    }

    /**
     * Run the memory "dreamer": first the LLM consolidation of recent episodic notes into durable
     * facts, then the deterministic decay/re-tier/prune. Records a one-line summary for the Admin
     * monitor.
     */
    private suspend fun runDream() {
        val cfg = ConfigManager(this)
        val since = cfg.lastDreamAt   // consolidate only notes captured since the last run
        val members = householdManager.members()
        val promoted = consolidateMemories(since, members)
        val summary = withContext(Dispatchers.IO) { memoryManager.runDecay() }
        // Drop persona memories for members that vanished outside the app (guarded: needs a real roster).
        val orphaned = memoryManager.pruneOrphans(members.mapNotNull { it.lookupKey }.toSet())
        val note = summary.note() +
            (if (promoted.isNotEmpty()) " · learned: ${promoted.joinToString("; ")}" else "") +
            (if (orphaned > 0) " · dropped $orphaned orphan(s)" else "")
        cfg.lastDreamAt = summary.at
        cfg.lastDreamNote = note
        cfg.appendDreamLog(summary.at, note)
        Log.d(TAG, "Dream done: $note")
    }

    /**
     * The dreamer's LLM half: feed recent EPISODIC notes to mistral-small and promote any durable
     * facts/preferences/routines it extracts into long-term memory (conservative; Admin can review).
     * Parses lines "CATEGORY | SUBJECT | TEXT". Returns how many were promoted.
     */
    private suspend fun consolidateMemories(since: Long, members: List<Member>): List<String> {
        val notes = memoryManager.recentEpisodic(since)
        if (notes.isEmpty()) { Log.d(TAG, "Consolidation: no new episodic notes since last dream"); return emptyList() }
        val out = brainClient.complete(
            TeyaPersona.consolidationPrompt, notes.joinToString("\n") { "- ${it.text}" },
        )?.trim()
        if (out.isNullOrBlank() || out.uppercase() == "NONE") {
            Log.d(TAG, "Consolidation: reviewed ${notes.size} note(s), nothing durable to promote")
            return emptyList()
        }
        val promoted = mutableListOf<String>()
        out.lineSequence().forEach { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size < 3) return@forEach
            val category = parts[0]
            val subject = parts[1]
            val text = parts.drop(2).joinToString(" | ").trim()
            if (text.isBlank()) return@forEach
            val member = subject.takeIf { it.isNotBlank() && !it.equals("GENERAL", ignoreCase = true) }
                ?.let { householdManager.resolveMember(it, members) }
            val subjectKey = member?.lookupKey
            if (memoryManager.hasSimilar(text, subjectKey)) return@forEach   // already known → don't duplicate
            val emb = brainClient.embed(text)
            val id = if (subjectKey != null) {
                memoryManager.remember(text, MemoryManager.SUBJECT_CONTACT, subjectKey, category, emb)
            } else {
                memoryManager.remember(text, MemoryManager.SUBJECT_GENERAL, null, category, emb)
            }
            if (id >= 0) promoted.add(text)
        }
        Log.d(TAG, "Consolidation promoted ${promoted.size} memories from ${notes.size} notes")
        return promoted
    }

    /**
     * End-of-session capture (background): summarize a finished conversation into one EPISODIC note
     * (or skip on NONE / trivia). Episodic notes decay fast and feed the nightly consolidation.
     */
    private fun captureEpisodic(history: List<ChatMessage>) {
        if (history.size < 2) return
        scope.launch {
            val convo = history
                .filter { it.role == "user" || (it.role == "assistant" && !it.content.isNullOrBlank()) }
                .joinToString("\n") { "${it.role}: ${it.content}" }
            if (convo.isBlank()) return@launch
            val summary = brainClient.complete(TeyaPersona.episodicSummaryPrompt, convo)?.trim()
            if (summary.isNullOrBlank() || summary.uppercase() == "NONE") return@launch
            val emb = brainClient.embed(summary)
            memoryManager.remember(summary, MemoryManager.SUBJECT_GENERAL, null, MemoryManager.CAT_EPISODIC, emb)
            Log.d(TAG, "Captured episodic memory: $summary")
        }
    }

    /** Schedule the nightly dreamer (~3 AM) via AlarmManager — the wall device is idle + charging then. */
    private fun scheduleDream() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getService(
            this, DREAM_REQUEST_CODE,
            Intent(this, HarnessService::class.java).setAction(ACTION_RUN_DREAM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextDreamTimeMillis(), AlarmManager.INTERVAL_DAY, pi)
        Log.d(TAG, "Dream scheduled for next ${DREAM_HOUR}:00")
    }

    /** Epoch millis of the next DREAM_HOUR o'clock local time (today if still ahead, else tomorrow). */
    private fun nextDreamTimeMillis(): Long {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(DREAM_HOUR, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
     * Real barge-in: the user started talking (any words — "stop" or otherwise) while Teya is
     * thinking/speaking, detected by a local Silero VAD running on the mic stream (see
     * [com.teya.agent.voice.vad.SileroVad]) rather than a wake phrase or a loudness guess — armed
     * only during that window (never while idle). Stop audio immediately and cancel the in-flight
     * turn; [runConversation]'s loop then goes straight back to listening for what they're saying.
     */
    private fun onBargeIn() {
        if (!conversationActive.get()) return // nothing to interrupt
        Log.d(TAG, "Barge-in — interrupting Teya")
        voicePipeline.interrupt()
        activeTurnJob?.cancel()
    }

    /**
     * A multi-turn conversation: prompt once, then keep listening for follow-ups (no wake word
     * needed) until the user is silent for FOLLOWUP_LISTEN_MS. History is kept so the model has
     * context across turns. Barge-in detection is armed only while Teya is thinking/speaking —
     * never during command capture (the wake-word engine is paused there anyway, mic needed
     * exclusively) or once the conversation ends.
     */
    private suspend fun runConversation() {
        val history = mutableListOf<ChatMessage>()
        try {
            // AEC3's session-wide render feed (Plan B, Phase 2) — independent of setBargeInArmed's
            // per-turn arm/disarm below, which continues to govern only sileroVad. See
            // VoicePipeline.startAecSession()'s doc comment for why this is session-scoped.
            voicePipeline.startAecSession()
            updateUiState(AgentState.SPEAKING)
            Log.d(TAG, "Prompting...")
            voicePipeline.setBargeInArmed(true)
            voicePipeline.textToSpeech("Yes?")

            while (true) {
                voicePipeline.setBargeInArmed(false)
                voicePipeline.pauseWakeWord()
                updateUiState(AgentState.LISTENING)
                sendDebug(user = "…", agent = "")
                Log.d(TAG, "Listening for command...")
                val text = voicePipeline.listenForCommand(FOLLOWUP_LISTEN_MS, sttContextBias())
                voicePipeline.resumeWakeWord()
                if (text.isBlank()) {
                    Log.d(TAG, "No follow-up heard — ending conversation")
                    break
                }
                Log.d(TAG, "Heard (STT): \"$text\"")
                sendDebug(user = text)
                history.add(ChatMessage("user", text))
                trimHistory(history)

                voicePipeline.setBargeInArmed(true) // Teya's about to think/speak — arm for interruption
                respond(history)
            }
            captureEpisodic(history)   // summarize the finished session into episodic memory (background)
        } catch (e: Exception) {
            Log.e(TAG, "Error in conversation", e)
        } finally {
            voicePipeline.endAecSession()
            voicePipeline.setBargeInArmed(false)
            voicePipeline.resumeWakeWord() // back to idle wake-word listening for the next trigger
            updateUiState(AgentState.IDLE)
        }
    }

    /** Vocabulary hints for STT — see [MistralClient.transcribe]'s `contextBias`. */
    private suspend fun sttContextBias(): List<String> {
        val members = householdManager.members()
        val names = members.flatMap { listOf(it.first, it.last) + it.aliases }.filter { it.isNotBlank() }
        return (names + householdManager.languages()).distinct()
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
     * so it can produce a final spoken answer. Query tools (weather, …) depend on
     * this round-trip — the tool returns data, the model turns it into a sentence. Bounded by
     * [MAX_TOOL_ROUNDS]. Appends every turn (assistant tool-calls + tool results) to [history] so
     * context is preserved across the round-trip and into later turns.
     */
    private suspend fun respond(history: MutableList<ChatMessage>) {
        // Refresh live device state once per user turn; the same snapshot is used across any tool
        // rounds within this turn (time won't drift meaningfully over a few seconds).
        val liveContext = buildLiveContext()
        var interrupted = false
        for (round in 0 until MAX_TOOL_ROUNDS) {
            updateUiState(AgentState.THINKING)

            val fullText = StringBuilder()
            var queued = 0            // chars already handed to the TTS consumer
            var spoke = false         // did audio start playing this round?
            val sentences = Channel<String>(Channel.UNLIMITED)
            var response = BrainResponse("")

            // Run this round as its own cancellable job: a barge-in (repeated wake word while
            // Teya is thinking/speaking — see onTrigger) cancels it directly, which unwinds the
            // streaming LLM read and the sentence loop below without tearing down the whole
            // conversation. join() never throws even if cancelled, so this is safe to await plainly.
            val roundJob = scope.launch {
                coroutineScope {
                    // Consumer: speak each completed sentence in order, in parallel with generation.
                    // The transcript is revealed HERE — one sentence at a time, as each starts playing —
                    // so the caption tracks the voice. (Driving it off the model's token stream instead
                    // makes the text race far ahead of the audio, since generation is much faster than TTS.)
                    val spokenSoFar = StringBuilder()
                    val speaker = launch {
                        for (sentence in sentences) {
                            if (!spoke) { spoke = true; updateUiState(AgentState.SPEAKING) }
                            if (spokenSoFar.isNotEmpty()) spokenSoFar.append(' ')
                            spokenSoFar.append(sentence)
                            sendDebug(agent = spokenSoFar.toString())   // reveal as this sentence is voiced
                            try {
                                voicePipeline.textToSpeech(sentence)
                            } catch (e: Exception) {
                                Log.e(TAG, "TTS failed for sentence", e)
                            }
                            if (voicePipeline.isInterrupted()) break // barge-in — stop queuing more speech
                            // Barge-in only listens between sentences, not during them (see
                            // VoicePipeline.forwardArmedChunk) — back-to-back playback otherwise
                            // leaves no real gap to react to. Cancelled instantly via activeTurnJob
                            // the moment barge-in fires.
                            Log.d(TAG, "Barge-in: listening gap open (${BARGE_IN_GAP_MS}ms)")
                            delay(BARGE_IN_GAP_MS)
                            Log.d(TAG, "Barge-in: listening gap closed")
                            if (voicePipeline.isInterrupted()) break
                        }
                    }

                    response = brainClient.streamChat(history, liveContext) { soFar ->
                        fullText.setLength(0); fullText.append(soFar)
                        val cut = lastSentenceEnd(soFar, queued) // hand any completed sentence(s) to TTS
                        if (cut > queued) {
                            val chunk = soFar.substring(queued, cut).trim()
                            if (chunk.isNotEmpty()) sentences.trySend(chunk)
                            queued = cut
                        }
                    }

                    // Queue the trailing partial sentence, then let the consumer drain and finish.
                    val rest = fullText.substring(queued).trim()
                    if (rest.isNotEmpty()) sentences.trySend(rest)
                    sentences.close()
                }
            }
            activeTurnJob = roundJob
            roundJob.join()
            activeTurnJob = null
            // coroutineScope returned → all queued audio has finished playing (or was cut short).

            if (voicePipeline.consumeInterrupted()) {
                Log.d(TAG, "Turn interrupted by barge-in — abandoning this round, no tool calls run")
                if (fullText.isNotBlank()) {
                    history.add(ChatMessage(role = "assistant", content = fullText.toString()))
                }
                interrupted = true
                break
            }

            if (response.toolCalls.isEmpty()) {
                val finalText = fullText.toString()
                when {
                    finalText.isNotBlank() ->
                        history.add(ChatMessage(role = "assistant", content = finalText))
                    // Stream produced no text (e.g. a connection error surfaced as speechResponse):
                    // speak it directly so the user isn't left in silence.
                    response.speechResponse.isNotBlank() -> {
                        updateUiState(AgentState.SPEAKING)
                        sendDebug(agent = response.speechResponse)
                        voicePipeline.textToSpeech(response.speechResponse)
                        history.add(ChatMessage(role = "assistant", content = response.speechResponse))
                    }
                }
                return
            }

            // Tools requested: record the assistant turn (with any spoken preamble + ALL calls),
            // run each sequentially (no races on shared stores), feed results back, then loop.
            history.add(ChatMessage(
                role = "assistant",
                content = fullText.toString().ifBlank { null },
                toolCalls = response.toolCalls,
            ))
            for (toolCall in response.toolCalls) {
                Log.d(TAG, "Tool call: ${toolCall.functionName}(${toolCall.arguments})")
                val result = executeTool(toolCall)
                Log.d(TAG, "Tool result: $result")
                history.add(ChatMessage(
                    role = "tool",
                    content = result,
                    toolCallId = toolCall.id,
                    name = toolCall.functionName,
                ))
            }
        }
        if (interrupted) return
        // All tool rounds exhausted without a text answer.
        val fallback = "Sorry, I got a bit tangled up just now."
        history.add(ChatMessage(role = "assistant", content = fallback))
        updateUiState(AgentState.SPEAKING)
        sendDebug(agent = fallback)
        voicePipeline.textToSpeech(fallback)
    }

    /**
     * Index just past the last sentence-ending punctuation (. ! ? or newline) at or after [from],
     * so we only ever hand whole sentences to TTS. Returns [from] when no sentence has completed.
     */
    private fun lastSentenceEnd(text: String, from: Int): Int {
        var end = from
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                var j = i + 1
                while (j < text.length && text[j] in charArrayOf('.', '!', '?', '"', '\'', ')')) j++
                end = j
            }
            i++
        }
        return end
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
        "add_event" -> {
            val title = tool.arguments["title"]?.takeIf { it.isNotBlank() }
            val startMillis = tool.arguments["start"]?.let { parseIsoToMillis(it) }
            if (title == null || startMillis == null) {
                "I couldn't add it — I need at least a title and a start time."
            } else {
                val duration = tool.arguments["duration_minutes"]?.toIntOrNull()?.takeIf { it > 0 } ?: 60
                val location = tool.arguments["location"]?.takeIf { it.isNotBlank() }
                val rrule = repeatToRrule(tool.arguments["repeat"])
                val id = withContext(Dispatchers.IO) {
                    calendarManager.addEvent(title, startMillis, duration, location, rrule)
                }
                if (id != null) {
                    "Added \"$title\"" + (if (rrule != null) ", repeating" else "") +
                        (location?.let { " at $it" } ?: "") + "."
                } else {
                    "I couldn't add that to the calendar."
                }
            }
        }
        "get_events" -> {
            val start = tool.arguments["start"]?.let { parseIsoToMillis(it) } ?: System.currentTimeMillis()
            val end = tool.arguments["end"]?.let { parseIsoToMillis(it) } ?: (start + 7 * 24 * 3600_000L)
            val events = withContext(Dispatchers.IO) { calendarManager.events(start, end) }
            if (events.isEmpty()) {
                "Nothing is scheduled in that period."
            } else {
                events.joinToString("; ") { e ->
                    val whenStr = Instant.ofEpochMilli(e.beginMillis).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH))
                    "${e.title} — $whenStr" + (e.location?.let { " at $it" } ?: "")
                }
            }
        }
        "cancel_event" -> {
            val query = tool.arguments["title"]?.takeIf { it.isNotBlank() }
            if (query == null) {
                "Which event should I cancel? Tell me its name."
            } else {
                val deleted = withContext(Dispatchers.IO) { calendarManager.deleteEventsByTitle(query) }
                when {
                    deleted.isEmpty() -> "I couldn't find an event matching \"$query\" to cancel."
                    deleted.size == 1 -> "Removed \"${deleted[0]}\" from the calendar."
                    else -> "Removed ${deleted.size} events matching \"$query\": ${deleted.joinToString(", ")}."
                }
            }
        }
        "add_to_shopping_list" -> {
            val items = splitItems(tool.arguments["items"])
            if (items.isEmpty()) {
                "What should I add to the shopping list?"
            } else {
                val added = shoppingList.add(items)
                when {
                    added.isEmpty() -> "That's already on the list."
                    added.size == 1 -> "Added ${added[0]} to the shopping list."
                    else -> "Added ${added.joinToString(", ")} to the shopping list."
                }
            }
        }
        "remove_from_shopping_list" -> {
            val removed = shoppingList.remove(splitItems(tool.arguments["items"]))
            if (removed.isEmpty()) "I didn't find that on the list."
            else "Removed ${removed.joinToString(", ")} from the shopping list."
        }
        "read_shopping_list" -> {
            val items = shoppingList.items()
            if (items.isEmpty()) "The shopping list is empty."
            // Hand the model the raw list; it groups by category (dairy, produce, …) when speaking.
            else "Shopping list (${items.size} items): ${items.joinToString(", ")}."
        }
        "clear_shopping_list" -> {
            val n = shoppingList.clear()
            if (n == 0) "The shopping list was already empty." else "Cleared the shopping list ($n items)."
        }
        "remember" -> {
            val fact = tool.arguments["fact"]?.trim().orEmpty()
            if (fact.isEmpty()) {
                "I need to know what to remember."
            } else {
                // Link to a member when `about` names one; otherwise store it as a family-wide fact.
                val about = tool.arguments["about"]?.trim().orEmpty()
                val member = about.takeIf { it.isNotEmpty() }
                    ?.let { householdManager.resolveMember(it, householdManager.members()) }
                // Embed every memory so it stays semantically searchable once it cools out of the
                // always-loaded block (persona) or lives in the search-only general pool.
                val embedding = brainClient.embed(fact)
                val id = if (member?.lookupKey != null) {
                    memoryManager.remember(fact, MemoryManager.SUBJECT_CONTACT, member.lookupKey, tool.arguments["category"], embedding)
                } else {
                    memoryManager.remember(fact, MemoryManager.SUBJECT_GENERAL, null, tool.arguments["category"], embedding)
                }
                if (id < 0) "I couldn't save that."
                else "Saved to memory" + (member?.displayName?.let { " (about $it)" } ?: "") + "."
            }
        }
        "forget" -> {
            val fact = tool.arguments["fact"]?.trim().orEmpty()
            if (fact.isEmpty()) {
                "Tell me what to forget."
            } else {
                val about = tool.arguments["about"]?.trim().orEmpty()
                val member = about.takeIf { it.isNotEmpty() }
                    ?.let { householdManager.resolveMember(it, householdManager.members()) }
                val n = memoryManager.forget(fact, member?.lookupKey)
                if (n == 0) "I didn't have anything like that saved." else "Forgotten."
            }
        }
        "search_memory" -> {
            val query = tool.arguments["query"]?.trim().orEmpty()
            if (query.isEmpty()) {
                "What should I look for in my memory?"
            } else {
                val hits = memoryManager.search(query, brainClient.embed(query))
                if (hits.isEmpty()) "I don't have anything about that saved."
                else hits.joinToString("; ") { it.text }
            }
        }
        else -> "Unknown tool: ${tool.functionName}"
    }

    /** Split a free-text item string ("milk, eggs and bread") into individual trimmed items. */
    private fun splitItems(raw: String?): List<String> =
        raw?.split(Regex("\\s*(?:,|;|\\band\\b|\\n)\\s*"))?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    /** Parse an ISO local date-time ("2026-07-14T17:30") or date to epoch millis; null if unparseable. */
    private fun parseIsoToMillis(iso: String): Long? {
        val zone = ZoneId.systemDefault()
        return runCatching { LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(iso).atStartOfDay(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }

    /** Map a friendly repeat word to an RFC-5545 RRULE (weekday of a weekly rule comes from the start). */
    private fun repeatToRrule(repeat: String?): String? = when (repeat?.lowercase()?.trim()) {
        "daily" -> "FREQ=DAILY"
        "weekly" -> "FREQ=WEEKLY"
        "monthly" -> "FREQ=MONTHLY"
        "yearly" -> "FREQ=YEARLY"
        "weekdays" -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
        else -> null
    }

    /**
     * The "live device state" block injected into the model's context every turn (ambient facts,
     * not tools): time, location, running timers, and today's remaining events. The model reads
     * these directly instead of spending a tool round-trip. Kept small — cheap, ubiquitous facts
     * only. Runs off the main thread (location + calendar are content-provider reads).
     */
    private suspend fun buildLiveContext(): String = withContext(Dispatchers.IO) {
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        // 12-hour format ("9:05 PM") so the model never has to do a 24h→12h conversion (it fumbles
        // that — reads "21:05" as "quarter past nine"). Hand it the time it will actually speak.
        val time = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH))
        val loc = lastKnownLocation()
        val locLine = if (loc != null) {
            "%.4f, %.4f (latitude, longitude)".format(loc.latitude, loc.longitude)
        } else {
            "unknown"
        }
        // Active timers ride in the ambient context so "how long left?" needs no tool round-trip.
        val timersLine = timerManager.active().joinToString("; ") { t ->
            val remaining = ((t.endAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            "${t.label.ifBlank { "unnamed" }}: ${remaining / 60}m ${remaining % 60}s left"
        }.ifBlank { "none" }
        // Today's remaining events, so "what's on today?" is free.
        val endOfDay = now.toLocalDate().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        val eventsLine = calendarManager.events(System.currentTimeMillis(), endOfDay).joinToString("; ") { e ->
            val at = Instant.ofEpochMilli(e.beginMillis).atZone(zone)
                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
            "${e.title} at $at"
        }.ifBlank { "nothing scheduled" }
        val context = """
            Live device state (authoritative — use these directly, do not ask the user):
            - Now: $time ($zone)
            - Location: $locLine
            - Active timers: $timersLine
            - Today's remaining events: $eventsLine
        """.trimIndent()
        // The household profile (who the family is + reply-language directive) and Teya's durable
        // memory ("what you remember") ride in the same ambient block, rebuilt each turn so Admin
        // edits apply with no restart. Members are loaded once and shared by both. Empty until set up.
        val members = householdManager.members()
        val profile = householdManager.profileContextBlock(members)
        val memory = memoryManager.memoryContextBlock(members)
        val full = buildString {
            append(context)
            if (profile.isNotBlank()) append("\n\n").append(profile)
            if (memory.isNotBlank()) append("\n\n").append(memory)
        }
        // TODO: gate behind BuildConfig.DEBUG — this line logs location + memory (PII).
        Log.d(TAG, "Live context: ${full.replace("\n", " | ")}")
        full
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
