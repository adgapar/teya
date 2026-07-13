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
import com.teya.agent.household.Languages
import com.teya.agent.household.Member
import com.teya.agent.household.MemoryManager
import com.teya.agent.household.SpeakerIdManager
import com.teya.agent.household.SpeakerMatch
import com.teya.agent.persona.AgentTools
import com.teya.agent.persona.TeyaPersona
import com.teya.agent.safety.ContactAllowlistManager
import com.teya.agent.shopping.ShoppingListManager
import com.teya.agent.telephony.CallResult
import com.teya.agent.telephony.TelephonyActuator
import com.teya.agent.timers.TeyaTimer
import com.teya.agent.timers.TimerManager
import com.teya.agent.ui.face.AgentState
import com.teya.agent.voice.SttFailedException
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
        // How long to wait between timer re-announcements (see timerNagLoop) — kept short and
        // genuinely a bit annoying on purpose: a kitchen timer nobody's acknowledged is more likely
        // to mean nobody's heard it yet than that they're ignoring it.
        private const val TIMER_NAG_INTERVAL_MS = 10_000L
        // Shorter than FOLLOWUP_LISTEN_MS on purpose — a nag cycle is "is anyone there to dismiss
        // this?", not an open conversational turn, so it shouldn't hold the mic as long before
        // re-nagging.
        private const val TIMER_NAG_LISTEN_MS = 4000
        // Deliberate pause after each sentence — see runConversation's speaker loop. Only applied
        // when that sentence fell back to playMp3, or the WebView AEC host isn't active this
        // session (see VoicePipeline.isContinuousBargeInActive); sentences streamed through the
        // WebView AEC path skip it entirely since barge-in listens continuously through them.
        // Default 350ms leaves ~300ms for speech onset plus Silero's speechDurationMs=50 confirm
        // (see VoicePipeline.setBargeInArmed) — narrow enough to feel responsive, wide enough to
        // catch a real interrupt starting right at the end of a sentence. Configurable via Admin's
        // "Voice tuning" section — see ConfigManager.bargeInGapMs.
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
    // The repeat-until-acknowledged timer nag loop, if one is running (see ensureTimerNagLoopRunning).
    // Only one ever runs — it re-reads TimerManager.ringing() each cycle, so timers that fire while
    // it's already going just get folded into the next announcement, no need for a loop per timer.
    @Volatile private var timerNagJob: Job? = null
    // Captured at interrupt time (see onBargeIn) and consumed by the next listenForCommand call in
    // runConversation's loop, so whatever the user was already saying when they interrupted
    // carries through into that recording — see VoicePipeline.consumeBargeInAudio's doc comment.
    @Volatile private var pendingBargeInAudio: ShortArray? = null
    // Set once per turn by onTrigger (wake-word path only — null for a manual tap), resolved by
    // buildLiveContext via SpeakerIdManager, then cleared at the end of the turn. A soft,
    // unconfirmed signal — see HouseholdManager.speakerContextBlock.
    @Volatile private var pendingSpeakerAudio: ShortArray? = null
    // Set after every listenForCommand call in runConversation's loop (see
    // VoicePipeline.consumeLastCommandAudio) — real conversational speech, usually a better sample
    // than the wake-word pre-roll, so buildLiveContext re-checks identification on it every turn
    // ("recognize during live conversation"), not just once at wake-word time.
    @Volatile private var pendingCommandAudio: ShortArray? = null
    // Resolved from pendingSpeakerAudio/pendingCommandAudio (see buildLiveContext) and reused
    // across a conversation until a better sample updates it; cleared at the end of the turn.
    @Volatile private var currentTurnSpeaker: SpeakerMatch? = null

    private lateinit var voicePipeline: VoicePipeline
    private lateinit var brainClient: BrainClient
    private lateinit var telephonyActuator: TelephonyActuator
    private lateinit var allowlistManager: ContactAllowlistManager
    private lateinit var timerManager: TimerManager
    private lateinit var calendarManager: CalendarManager
    private lateinit var shoppingList: ShoppingListManager
    private lateinit var householdManager: HouseholdManager
    private lateinit var memoryManager: MemoryManager
    private lateinit var configManager: ConfigManager
    private lateinit var speakerIdManager: SpeakerIdManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        
        configManager = ConfigManager(this)
        allowlistManager = ContactAllowlistManager(this)
        telephonyActuator = TelephonyActuator(this, allowlistManager)
        voicePipeline = VoicePipeline(this)
        timerManager = TimerManager(this)
        calendarManager = CalendarManager(this)
        shoppingList = ShoppingListManager(this)
        householdManager = HouseholdManager(this)
        memoryManager = MemoryManager(this)
        speakerIdManager = SpeakerIdManager(this)

        // Reads configManager.mistralApiKey fresh on every request (see MistralClient's
        // apiKeyProvider) rather than capturing it once here — this service runs for its whole
        // foreground lifetime, so a key added/changed/cleared in Admin takes effect on the very
        // next request, with no service restart needed. A blank key just 401s like a bad one,
        // which onMistralAuthError already turns into the same "check your key" BRAIN_OFF gate.
        Log.d(TAG, "Initializing Mistral brain (key ${if (configManager.mistralApiKey.isNullOrBlank()) "not yet set" else "present"})")
        val mistralClient = MistralClient(
            KtorClientFactory.create(),
            { configManager.mistralApiKey ?: "" },
            TeyaPersona.systemPrompt,
            AgentTools.all,
            { configManager.ttsVoice }
        )
        mistralClient.onAuthError = ::onMistralAuthError
        brainClient = mistralClient
        voicePipeline.setMistralClient(mistralClient)
        scope.launch { mistralClient.warmUp() }  // warm the TLS/connection pool at startup

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
                ensureTimerNagLoopRunning()
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
            onWakeWord = { audio ->
                Log.d(TAG, "Wake word detected!")
                onTrigger(audio)
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

    /**
     * Entry point for a tap or wake-word trigger. Guards against overlapping conversations.
     * [speakerAudio] is the ~3s pre-roll window captured right at wake-word-fire time (null for a
     * manual tap trigger, which has no such audio) — used for per-speaker voice ID (see
     * [SpeakerIdManager]), a soft signal only, resolved once per turn in [buildLiveContext].
     */
    private fun onTrigger(speakerAudio: ShortArray? = null) {
        if (!conversationActive.compareAndSet(false, true)) {
            Log.d(TAG, "Conversation already active — ignoring trigger")
            return
        }
        pendingSpeakerAudio = speakerAudio
        brainBroken = false // give a fresh attempt the benefit of the doubt — re-gates immediately if still bad
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
        pendingBargeInAudio = voicePipeline.consumeBargeInAudio() // grab before it's reset on next arm
        voicePipeline.interrupt()
        voicePipeline.playInterruptChime() // audible confirmation — wall-mounted, screen not always visible
        // Reflect the state change here, synchronously with the audio cut, instead of waiting for
        // activeTurnJob's cancellation to unwind back to runConversation's loop — that unwind
        // depends on however long the in-flight LLM stream read takes to notice cancellation, which
        // left the UI observed stuck on "speaking" (no audio) until it did. onBargeIn only ever
        // fires while runConversation is active (guarded above), and its loop always goes straight
        // back to listening after an interrupt, so this is never the wrong state to jump to.
        updateUiState(AgentState.LISTENING)
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
            // The WebView AEC host is session-scoped — independent of setBargeInArmed's per-turn
            // arm/disarm below, which continues to govern only sileroVad. See
            // VoicePipeline.startAecSession()'s doc comment for why.
            voicePipeline.startAecSession()
            updateUiState(AgentState.SPEAKING)
            Log.d(TAG, "Prompting...")
            voicePipeline.setBargeInArmed(true)
            voicePipeline.consumeInterrupted() // clear any stale flag before this fresh speaking phase — see VoicePipeline.textToSpeech's doc comment on why this can't live inside textToSpeech itself
            // Picks one of the household's speakable languages at random each trigger, then a
            // varied greeting within it — see Languages.greetings' doc comment for why this can't
            // just ask the LLM to translate "Yes?" on the fly.
            val greetingLang = householdManager.speakableLanguages().random()
            voicePipeline.textToSpeech(Languages.greetings(greetingLang).random())

            while (true) {
                voicePipeline.setBargeInArmed(false)
                voicePipeline.pauseWakeWord()
                updateUiState(AgentState.LISTENING)
                voicePipeline.playListeningChime() // audible cue — wall-mounted, screen not always visible
                sendDebug(user = "…", agent = "")
                Log.d(TAG, "Listening for command...")
                val prefixAudio = pendingBargeInAudio.also { pendingBargeInAudio = null } ?: ShortArray(0)
                val text = try {
                    voicePipeline.listenForCommand(FOLLOWUP_LISTEN_MS, sttContextBias(), prefixAudio)
                } catch (e: SttFailedException) {
                    // Distinct from blank (genuine silence) — tell the user instead of just going
                    // quiet, which used to be indistinguishable from her simply not hearing anything.
                    Log.e(TAG, "STT failed mid-conversation", e)
                    null
                } finally {
                    voicePipeline.resumeWakeWord()
                }
                // Per-speaker voice ID's live re-check — real conversational speech just captured
                // for STT, usually a better sample than the wake-word pre-roll alone. See
                // buildLiveContext's use of this.
                pendingCommandAudio = voicePipeline.consumeLastCommandAudio()
                if (text == null) {
                    updateUiState(AgentState.SPEAKING)
                    voicePipeline.textToSpeech("Sorry, I'm having trouble hearing you right now — check the connection.")
                    break
                }
                if (text.isBlank()) {
                    Log.d(TAG, "No follow-up heard — ending conversation")
                    break
                }
                Log.d(TAG, "Heard (STT): \"$text\"")
                sendDebug(user = text)
                history.add(ChatMessage("user", text))
                trimHistory(history)

                voicePipeline.setBargeInArmed(true) // Teya's about to think/speak — arm for interruption
                voicePipeline.consumeInterrupted() // clear any stale flag before this fresh speaking phase — see VoicePipeline.textToSpeech's doc comment
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
            pendingSpeakerAudio = null
            pendingCommandAudio = null
            currentTurnSpeaker = null
        }
    }

    /** Vocabulary hints for STT — see [MistralClient.transcribe]'s `contextBias`. */
    private suspend fun sttContextBias(): List<String> {
        val members = householdManager.members()
        val names = members.flatMap { listOf(it.first, it.last) + it.aliases }.filter { it.isNotBlank() }
        return (names + householdManager.languages()).distinct()
    }

    /** Starts the timer nag loop if one isn't already running — see [timerNagJob]'s doc comment. */
    private fun ensureTimerNagLoopRunning() {
        if (timerNagJob?.isActive == true) return
        timerNagJob = scope.launch { timerNagLoop() }
    }

    /**
     * Repeat-until-acknowledged: a fired kitchen timer nobody's there to hear the first time is
     * worse than one that's mildly annoying about it, so this keeps re-announcing every
     * [TIMER_NAG_INTERVAL_MS] until [TimerManager.ringing] is empty — i.e. until a real
     * conversation turn (this loop's own listen step below, or a completely separate wake-word
     * conversation happening at the same time) calls `cancel_timer` for it. Re-reads `ringing()`
     * fresh each cycle, so this naturally folds in any timer that fires while it's already going.
     */
    private suspend fun timerNagLoop() {
        while (true) {
            val ringing = timerManager.ringing()
            if (ringing.isEmpty()) {
                Log.d(TAG, "No more ringing timers — stopping nag loop")
                return
            }
            if (!conversationActive.compareAndSet(false, true)) {
                // A real conversation already has the mic — wait it out rather than talk over it.
                delay(TIMER_NAG_INTERVAL_MS)
                continue
            }
            try {
                nagOnce(ringing)
            } finally {
                conversationActive.set(false)
            }
            if (timerManager.ringing().isEmpty()) return
            delay(TIMER_NAG_INTERVAL_MS)
        }
    }

    /**
     * One nag cycle: speak, then listen briefly for a reply and — if anything was said — run it
     * through the exact same [respond] tool-calling turn a real conversation uses, so "cancel the
     * spaghetti one" resolves via `cancel_timer` naturally instead of needing special-cased parsing
     * here. Caller holds [conversationActive] for the duration (see [timerNagLoop]).
     */
    private suspend fun nagOnce(ringing: List<TeyaTimer>) {
        val message = if (ringing.size == 1) {
            val label = ringing[0].label
            if (label.isBlank()) "Time's up — your timer is done." else "Time's up — your $label timer is done."
        } else {
            "Time's up — your " + ringing.joinToString(" and ") { it.label.ifBlank { "unnamed" } } +
                " timers are done."
        }
        try {
            voicePipeline.startAecSession()
            updateUiState(AgentState.SPEAKING)
            voicePipeline.setBargeInArmed(true)
            voicePipeline.consumeInterrupted()
            voicePipeline.textToSpeech(message)

            voicePipeline.setBargeInArmed(false)
            voicePipeline.pauseWakeWord()
            updateUiState(AgentState.LISTENING)
            voicePipeline.playListeningChime()
            // Carry through whatever the user was already saying if they barged in on the
            // "Time's up" announcement itself (e.g. talking over it to say "cancel it") — same
            // contract as runConversation's prefixAudio; without this the start of that utterance
            // is silently dropped. See VoicePipeline.consumeBargeInAudio's doc comment.
            val prefixAudio = pendingBargeInAudio.also { pendingBargeInAudio = null } ?: ShortArray(0)
            val text = try {
                voicePipeline.listenForCommand(TIMER_NAG_LISTEN_MS, sttContextBias(), prefixAudio)
            } catch (e: SttFailedException) {
                null
            } finally {
                voicePipeline.resumeWakeWord()
            }
            if (!text.isNullOrBlank()) {
                Log.d(TAG, "Heard during timer nag: \"$text\"")
                val history = mutableListOf(ChatMessage("user", text))
                voicePipeline.setBargeInArmed(true)
                voicePipeline.consumeInterrupted()
                respond(history)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Timer nag turn failed", e)
        } finally {
            voicePipeline.endAecSession()
            voicePipeline.setBargeInArmed(false)
            updateUiState(AgentState.IDLE)
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
                            var streamed = true // path this specific sentence actually took
                            try {
                                streamed = voicePipeline.textToSpeech(sentence)
                            } catch (e: Exception) {
                                Log.e(TAG, "TTS failed for sentence", e)
                            }
                            if (voicePipeline.isInterrupted()) break // barge-in — stop queuing more speech
                            // Sentences streamed through the WebView AEC path don't need this gap —
                            // barge-in listens continuously during and after them (see
                            // VoicePipeline.forwardWebViewCapturedChunk). Sentences that fell back to
                            // playMp3, or ran while the WebView host wasn't active this session, keep
                            // the gap-gated behavior. Cancelled instantly via activeTurnJob the moment
                            // barge-in fires either way.
                            val gapNeeded = !streamed || !voicePipeline.isContinuousBargeInActive()
                            if (gapNeeded) {
                                val gapMs = configManager.bargeInGapMs
                                Log.d(TAG, "Barge-in: listening gap open (${gapMs}ms)")
                                delay(gapMs)
                                Log.d(TAG, "Barge-in: listening gap closed")
                            }
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
            when (telephonyActuator.placeCall(name)) {
                CallResult.SUCCESS -> "Calling $name now."
                CallResult.NO_SIM -> "I don't have a number to call from yet — there's no working phone line on this device."
                CallResult.NOT_ALLOWED -> "$name is not on the family's approved contacts, so the call was not placed."
                CallResult.NO_NUMBER -> "I don't have a phone number saved for $name."
            }
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
                cancelled.isEmpty() -> "There's nothing to cancel."
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

                // Default: invite the whole family (minus any excluded) on shared events; explicit
                // `attendees` narrows to just those people; `notify_family=false` (personal reminders,
                // chores) invites nobody.
                val notifyFamily = tool.arguments["notify_family"]?.toBooleanStrictOrNull() ?: true
                val explicitNames = splitItems(tool.arguments["attendees"])
                val excludeNames = splitItems(tool.arguments["exclude_attendees"])
                val members = if (notifyFamily || explicitNames.isNotEmpty()) householdManager.members() else emptyList()

                val invited: List<Member> = when {
                    explicitNames.isNotEmpty() -> explicitNames.mapNotNull { householdManager.resolveMember(it, members) }
                    notifyFamily -> {
                        val excludedKeys = excludeNames.mapNotNull { householdManager.resolveMember(it, members)?.lookupKey }.toSet()
                        members.filter { it.lookupKey !in excludedKeys }
                    }
                    else -> emptyList()
                }
                val invitable = invited.filter { it.email.isNotBlank() }
                // Only surface a "couldn't invite" note when specific people were named — silently
                // skipping members with no email during the invite-everyone default is expected
                // (e.g. kids), not worth mentioning every time.
                val missingEmail = if (explicitNames.isNotEmpty()) invited.filter { it.email.isBlank() }.map { it.displayName } else emptyList()

                val id = withContext(Dispatchers.IO) {
                    calendarManager.addEvent(title, startMillis, duration, location, rrule, invitable.map { it.email })
                }
                if (id != null) {
                    "Added \"$title\"" + (if (rrule != null) ", repeating" else "") +
                        (location?.let { " at $it" } ?: "") +
                        (if (invitable.isNotEmpty()) ", invited ${invitable.joinToString(", ") { it.displayName }}" else "") +
                        "." +
                        (if (missingEmail.isNotEmpty()) " I couldn't invite ${missingEmail.joinToString(", ")} — no email on file." else "")
                } else {
                    "I couldn't add that to the calendar."
                }
            }
        }
        "get_events" -> {
            val start = tool.arguments["start"]?.let { parseIsoToMillis(it) } ?: System.currentTimeMillis()
            val end = tool.arguments["end"]?.let { parseIsoToMillis(it) } ?: (start + 7 * 24 * 3600_000L)
            val trustedEmails = trustedOrganizerEmails(householdManager.members())
            val events = withContext(Dispatchers.IO) { calendarManager.events(start, end, trustedEmails) }
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

    /** Household member emails, lowercased — the calendar's organizer allowlist (see [CalendarManager.events]). */
    private fun trustedOrganizerEmails(members: List<Member>): Set<String> =
        members.mapNotNull { it.email.takeIf { e -> e.isNotBlank() }?.lowercase() }.toSet()

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
     * not tools): time, location, running timers, today's remaining events, inbound invitations.
     * The model reads these directly instead of spending a tool round-trip. Kept small — every
     * category but "Now" (which is never empty) is dropped entirely when it has nothing to report,
     * rather than padding every turn with an always-empty "none"/"nothing scheduled" line — the
     * header itself states that an absent category means there's currently none, so omission stays
     * unambiguous. Runs off the main thread (location + calendar are content-provider reads).
     */
    private suspend fun buildLiveContext(): String = withContext(Dispatchers.IO) {
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        // 12-hour format ("9:05 PM") so the model never has to do a 24h→12h conversion (it fumbles
        // that — reads "21:05" as "quarter past nine"). Hand it the time it will actually speak.
        val time = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH))
        val lines = mutableListOf("Now: $time ($zone)")

        lastKnownLocation()?.let { loc ->
            lines += "Location: %.4f, %.4f (latitude, longitude)".format(loc.latitude, loc.longitude)
        }

        // Active timers ride in the ambient context so "how long left?" needs no tool round-trip.
        timerManager.active().takeIf { it.isNotEmpty() }?.let { timers ->
            lines += "Active timers: " + timers.joinToString("; ") { t ->
                val remaining = ((t.endAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                "${t.label.ifBlank { "unnamed" }}: ${remaining / 60}m ${remaining % 60}s left"
            }
        }

        // Fired timers currently being re-announced (see timerNagLoop) — without this the model has
        // no signal that a timer just went off, so a casual reply during the nag ("all good", "yeah
        // thanks") reads as small talk instead of the acknowledgment it is. Any reply at all while
        // nagging almost always means "stop" — the persona is told to treat it that way.
        timerManager.ringing().takeIf { it.isNotEmpty() }?.let { timers ->
            lines += "Timers currently ringing (already finished, being re-announced every " +
                "~10s until cancelled): " + timers.joinToString("; ") { it.label.ifBlank { "unnamed" } }
        }

        // Members are loaded once here (also feeds the household profile block + the calendar
        // trust filter below) and shared by everything downstream. Empty until set up.
        val members = householdManager.members()
        val trustedEmails = trustedOrganizerEmails(members)

        // Today's remaining events, so "what's on today?" is free.
        val endOfDay = now.toLocalDate().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        calendarManager.events(System.currentTimeMillis(), endOfDay, trustedEmails)
            .takeIf { it.isNotEmpty() }?.let { events ->
                lines += "Today's remaining events: " + events.joinToString("; ") { e ->
                    val at = Instant.ofEpochMilli(e.beginMillis).atZone(zone)
                        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
                    "${e.title} at $at"
                }
            }

        // Invitations someone outside the household emailed to its synced calendar account —
        // informational only (see CalendarManager.inboundInvites doc comment): never treated as a
        // confirmed commitment and never a reason to call add_event on their own; only mention them
        // if relevant, and only add one for real if a person explicitly asks you to.
        val inboundStart = now.minusDays(1).atZone(zone).toInstant().toEpochMilli()
        val inboundEnd = now.plusDays(30).atZone(zone).toInstant().toEpochMilli()
        calendarManager.inboundInvites(inboundStart, inboundEnd, trustedEmails)
            .takeIf { it.isNotEmpty() }?.let { inbound ->
                lines += "Inbound invitations (sent by someone outside the household to Teya's " +
                    "calendar; see the persona's guidance on these): " + inbound.joinToString("; ") { e ->
                        val at = Instant.ofEpochMilli(e.beginMillis).atZone(zone)
                            .format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH))
                        "${e.title} — $at, from ${e.organizer}"
                    }
            }

        val context = "Live device state (authoritative — use these directly, do not ask the user; " +
            "a category not listed here currently has none): \n" + lines.joinToString("\n") { "- $it" }
        // The household profile (who the family is + reply-language directive) and Teya's durable
        // memory ("what you remember") ride in the same ambient block, rebuilt each turn so Admin
        // edits apply with no restart.
        val profile = householdManager.profileContextBlock(members)
        val memory = memoryManager.memoryContextBlock(members)
        // Per-speaker voice ID: an initial guess from the wake-word pre-roll (fires once, the
        // first turn of a fresh trigger), then re-checked every turn against the actual command
        // audio just captured ("recognize during live conversation") — the command audio is
        // usually a better sample (longer, natural conversational speech, VAD-trimmed) and
        // overrides the wake-word guess whenever it produces its own match. A soft signal only;
        // identification failures are swallowed (no speaker line that turn) rather than breaking
        // the conversation. See pendingSpeakerAudio/pendingCommandAudio/currentTurnSpeaker's doc
        // comments.
        val wakeAudio = pendingSpeakerAudio
        if (wakeAudio != null) {
            pendingSpeakerAudio = null
            currentTurnSpeaker = identifySpeaker(wakeAudio, members)
        }
        val commandAudio = pendingCommandAudio
        if (commandAudio != null) {
            pendingCommandAudio = null
            identifySpeaker(commandAudio, members)?.let { currentTurnSpeaker = it }
        }
        val speaker = householdManager.speakerContextBlock(currentTurnSpeaker)
        val full = buildString {
            append(context)
            if (profile.isNotBlank()) append("\n\n").append(profile)
            if (memory.isNotBlank()) append("\n\n").append(memory)
            if (speaker.isNotBlank()) append("\n\n").append(speaker)
        }
        // TODO: gate behind BuildConfig.DEBUG — this line logs location + memory (PII).
        Log.d(TAG, "Live context: ${full.replace("\n", " | ")}")
        full
    }

    /** Wraps [SpeakerIdManager.identify], swallowing failures (a soft signal isn't worth breaking the turn over). */
    private suspend fun identifySpeaker(audio: ShortArray, members: List<Member>): SpeakerMatch? =
        try {
            speakerIdManager.identify(audio, members, configManager.speakerIdThreshold, configManager.speakerIdConfidentThreshold)
        } catch (e: Exception) {
            Log.e(TAG, "Speaker ID failed", e)
            null
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

    private var lastAuthErrorShownAt = 0L
    // The gate: once Mistral rejects a request as unauthorized, every subsequent "go back to
    // resting" call shows BRAIN_OFF instead of IDLE (see updateUiState) until a fresh attempt is
    // made — reset optimistically at the top of each new trigger (onTrigger), not on a timer, so a
    // fixed key clears it the moment she's asked again, and a still-bad key re-gates immediately.
    private var brainBroken = false

    /** [MistralClient.onAuthError] callback — a 401 means the key itself is wrong, and since TTS is
     *  what's broken in that case, she can't speak the problem. Surfaced visually instead: recorded
     *  for Admin's API section, and gates the idle face to BRAIN_OFF (a genuinely different
     *  formation, not just IDLE recolored). The caption text is still shown once per debounce window
     *  (one bad turn can 401 on STT, chat, and TTS all in a row — that's one failure, not three).*/
    private fun onMistralAuthError() {
        configManager.lastAuthErrorAt = System.currentTimeMillis()
        // She can't say this aloud (TTS is what's broken), and it's the only way anyone will know
        // there's even a way to fix it — so the caption has to name the actual gesture, not just
        // "check Admin" and assume the reader already knows Admin exists or how to reach it.
        val note = "Brain's offline — hold the screen to open Admin, then fix the API key."
        configManager.lastAuthErrorNote = note // persisted: survives a missed/late-registered broadcast
        brainBroken = true
        val now = System.currentTimeMillis()
        if (now - lastAuthErrorShownAt < 10_000L) return
        lastAuthErrorShownAt = now
        Log.w(TAG, "Mistral rejected the API key (401) — brain marked off")
        sendDebug(agent = note)
    }

    private fun sendDebug(user: String? = null, agent: String? = null) {
        val intent = Intent(ACTION_TRANSCRIPT).apply {
            setPackage(packageName)
            user?.let { putExtra("user", it) }
            agent?.let { putExtra("agent", it) }
        }
        sendBroadcast(intent)
    }

    /** Every "back to resting" transition is gated to BRAIN_OFF while [brainBroken] — the one place
     *  this needs handling, instead of special-casing every IDLE call site in the conversation loop. */
    private fun updateUiState(state: AgentState) {
        val effective = if (state == AgentState.IDLE && brainBroken) AgentState.BRAIN_OFF else state
        val intent = Intent("com.teya.agent.STATE_UPDATE").apply {
            putExtra("state", effective.name)
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
