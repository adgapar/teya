package com.teya.agent.evals

import com.teya.agent.brain.BrainResponse
import com.teya.agent.brain.ChatMessage
import com.teya.agent.brain.KtorClientFactory
import com.teya.agent.brain.MistralClient
import com.teya.agent.brain.ToolCall
import com.teya.agent.persona.AgentTools
import com.teya.agent.persona.TeyaPersona
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Live calls to Mistral with the real persona and tool specs, asserting which tools it chooses.
 * Opt-in: with no MISTRAL_API_KEY (env or `.env`) every eval skips.
 *
 *     ./gradlew testDebugUnitTest --tests "com.teya.agent.evals.*"
 */
object EvalHarness {

    /** Fixed "now": a Monday, mid-day. */
    val NOW: LocalDate = LocalDate.of(2026, 9, 14)

    private val apiKey: String? by lazy {
        System.getenv("MISTRAL_API_KEY")?.takeIf { it.isNotBlank() }
            ?: readDotEnv()?.takeIf { it.isNotBlank() }
    }

    private fun readDotEnv(): String? {
        val candidates = listOf(File(".env"), File("../.env"), File("../../.env"))
        val line = candidates.firstOrNull { it.exists() }
            ?.readLines()?.firstOrNull { it.trimStart().startsWith("MISTRAL_API_KEY") }
            ?: return null
        return line.substringAfter("=").trim().trim('"', '\'')
    }

    /** Skips the calling eval when no key is configured. */
    fun requireKey(): String {
        Assume.assumeTrue("No MISTRAL_API_KEY — skipping live evals", apiKey != null)
        return apiKey!!
    }

    private fun client() = MistralClient(
        KtorClientFactory.create(),
        { requireKey() },
        TeyaPersona.systemPrompt,
        AgentTools.all,
    )

    /** The ambient block, shaped like `HarnessService.buildLiveContext` but pinned to [NOW]. */
    fun liveContext(
        todaysEvents: List<String> = emptyList(),
        upcoming: List<String> = emptyList(),
        textTransport: Boolean = false,
    ): String {
        val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        fun week(monday: LocalDate) = (0..6L).joinToString(", ") { i ->
            val d = monday.plusDays(i)
            d.format(dayFmt) + if (d == NOW) " (today)" else ""
        }
        val monday = NOW.with(DayOfWeek.MONDAY)
        val lines = buildList {
            add("Now: ${NOW.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))}, 12:51 PM (Europe/Madrid)")
            add("This week: ${week(monday)}")
            add("Next week: ${week(monday.plusWeeks(1))}")
            add("Last week: ${week(monday.minusWeeks(1))}")
            if (todaysEvents.isNotEmpty()) add("Today's events: ${todaysEvents.joinToString("; ")}")
            if (upcoming.isNotEmpty()) add("Upcoming events (next 7 days): ${upcoming.joinToString("; ")}")
        }
        val base = "Live device state (authoritative — use these directly, do not ask the user; " +
            "a category not listed here currently has none): \n" + lines.joinToString("\n") { "- $it" } +
            "\n\nHousehold profile (authoritative — the family Teya serves):\n" +
            "- Members:\n    • Alex Rivera — called Dad\n    • Sam Rivera — called Mom\n" +
            "- Languages: The household speaks English."
        return if (!textTransport) base
        else base + "\n\n" + TeyaPersona.textTransportBlock("Alex Rivera", AgentTools.withheldFromText)
    }

    /** One turn against the live model; [history] is oldest first. */
    fun ask(
        history: List<ChatMessage>,
        liveContext: String,
        allowedTools: Set<String>? = null,
    ): BrainResponse = runBlocking {
        client().processText(history, liveContext, allowedTools)
    }

    fun user(text: String) = ChatMessage("user", text)
    fun assistant(text: String) = ChatMessage("assistant", text)

    /** A prior tool round: the assistant's call plus the result it got back. */
    fun toolRound(name: String, args: Map<String, String>, result: String): List<ChatMessage> {
        val id = "call_eval_${name}"
        return listOf(
            ChatMessage("assistant", null, toolCalls = listOf(ToolCall(id, name, args))),
            ChatMessage("tool", result, toolCallId = id, name = name),
        )
    }

    fun BrainResponse.callsTo(name: String): List<ToolCall> = toolCalls.filter { it.functionName == name }
    fun BrainResponse.names(): List<String> = toolCalls.map { it.functionName }
}
