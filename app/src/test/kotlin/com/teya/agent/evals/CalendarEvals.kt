package com.teya.agent.evals

import com.teya.agent.evals.EvalHarness.ask
import com.teya.agent.evals.EvalHarness.callsTo
import com.teya.agent.evals.EvalHarness.liveContext
import com.teya.agent.evals.EvalHarness.names
import com.teya.agent.evals.EvalHarness.toolRound
import com.teya.agent.evals.EvalHarness.user
import com.teya.agent.persona.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalendarEvals {

    @Before fun requireKey() { EvalHarness.requireKey() }

    @Test fun `changing an existing event moves it instead of adding another`() {
        val response = ask(
            listOf(
                user("add kids breakdance on wednesday at 5pm at the dance studio"),
            ) + toolRound(
                "add_event",
                mapOf("title" to "Kids breakdance", "day" to "wednesday", "time" to "17:00"),
                "Added \"Kids breakdance\" on Wednesday 16 September, 5:00 PM at the dance studio.",
            ) + listOf(
                user("actually it's at 5:30, not 5"),
            ),
            liveContext(upcoming = listOf("Kids breakdance — Wed 16 Sep, 5:00 PM at dance studio")),
        )
        assertTrue(
            "Correcting an event must move it, not add a duplicate. Got: ${response.names()}",
            response.callsTo("move_event").isNotEmpty(),
        )
        assertTrue(
            "A correction must never create a second event. Got: ${response.names()}",
            response.callsTo("add_event").isEmpty(),
        )
    }

    @Test fun `a weekday is passed as a word, not as a date the model computed`() {
        var history = listOf(user("put kids football on friday at 5:30pm at the football field, every week"))
        var response = ask(history, liveContext())
        // A get_events round before adding is correct, not a miss; feed it back and look again.
        response.callsTo("get_events").firstOrNull()?.let { check ->
            history = history + toolRound(
                "get_events", check.arguments, "No events found in that range.",
            )
            response = ask(history, liveContext())
        }
        val call = response.callsTo("add_event").firstOrNull()
        assertTrue("Expected an add_event call, got: ${response.names()}", call != null)
        val day = call!!.arguments["day"].orEmpty().lowercase()
        assertTrue(
            "add_event must receive the weekday word ('friday'), not a computed date. Got day='$day'",
            day.contains("friday") || day.contains("fri"),
        )
        if (day.startsWith("2026")) assertEquals("2026-09-18", day)
    }

    @Test fun `an ambiguous cancel stops and asks instead of guessing`() {
        val stop = "STOP — nothing was cancelled, and you must not run any more calendar calls " +
            "this turn. Several events match \"Kids breakdance\". Reply asking which one, and wait " +
            "for the answer before doing anything else: \"Kids breakdance\" Monday 14 September, " +
            "5:30 PM (start=2026-09-14T17:30) (repeating); \"Kids breakdance\" Wednesday 16 " +
            "September, 5:30 PM (start=2026-09-16T17:30)"
        val response = ask(
            listOf(user("delete kids breakdance")) +
                toolRound("cancel_event", mapOf("title" to "Kids breakdance"), stop),
            liveContext(upcoming = listOf(
                "Kids breakdance — Mon 14 Sep, 5:30 PM",
                "Kids breakdance — Wed 16 Sep, 5:30 PM",
            )),
        )
        assertTrue(
            "After a STOP the model must make no further calendar calls. Got: ${response.names()}",
            response.toolCalls.none { it.functionName in CALENDAR_TOOLS },
        )
        assertTrue("It should ask which one. Got: '${response.speechResponse}'",
            response.speechResponse.isNotBlank())
    }

    @Test fun `after being told which one, it cancels with the exact start it was given`() {
        val stop = "STOP — nothing was cancelled. Several events match \"Kids breakdance\": " +
            "\"Kids breakdance\" Monday 14 September, 5:30 PM (start=2026-09-14T17:30); " +
            "\"Kids breakdance\" Wednesday 16 September, 5:30 PM (start=2026-09-16T17:30)"
        val response = ask(
            listOf(user("delete kids breakdance")) +
                toolRound("cancel_event", mapOf("title" to "Kids breakdance"), stop) +
                listOf(
                    EvalHarness.assistant("There are two — Monday at 5:30 and Wednesday at 5:30. Which one?"),
                    user("the wednesday one"),
                ),
            liveContext(),
        )
        val call = response.callsTo("cancel_event").firstOrNull()
        assertTrue("Expected a cancel_event call, got: ${response.names()}", call != null)
        assertEquals(
            "It must reuse the exact start it was given for the Wednesday row",
            "2026-09-16T17:30", call!!.arguments["start"],
        )
        assertTrue("all=true must not appear — only one was asked for",
            call.arguments["all"]?.lowercase() != "true")
    }

    /** The duplicate itself is refused by `add_event`; what the model owes is the recovery. */
    @Test fun `told an event already exists, it reports that instead of retrying`() {
        val response = ask(
            listOf(user("add kids football on friday at 5:30pm")) + toolRound(
                "add_event",
                mapOf("title" to "Kids football", "day" to "friday", "time" to "17:30"),
                "\"Kids football\" is ALREADY on the calendar at Friday 18 September, 5:30 PM — " +
                    "nothing added, there is no second copy. If the details need to change, use " +
                    "move_event on it.",
            ),
            liveContext(upcoming = listOf("Kids football — Fri 18 Sep, 5:30 PM at football field")),
        )
        assertTrue(
            "It must not retry the add after being told it already exists. Got: ${response.names()}",
            response.callsTo("add_event").isEmpty(),
        )
        val said = response.speechResponse.lowercase()
        assertTrue(
            "It should say the event is already there. Got: '${response.speechResponse}'",
            said.contains("already") || said.contains("exist"),
        )
    }

    @Test fun `a whole-week schedule is not executed in one burst`() {
        val response = ask(
            listOf(user(
                "here's our week: breakdance monday and wednesday 5:30 at the studio, " +
                    "swimming tuesday 4:35, football thursday 5:30 at the field. " +
                    "clear whatever is there now and set this up"
            )),
            liveContext(upcoming = listOf(
                "Kids breakdance — Wed 16 Sep, 5:30 PM",
                "Kids football — Fri 18 Sep, 5:30 PM",
                "Asier swimming — Tue 15 Sep, 4:35 PM",
            )),
        )
        val destructive = response.callsTo("cancel_event").size
        assertTrue(
            "No more than 3 deletions may be attempted in one turn (the harness refuses past that). Got $destructive",
            destructive <= 3,
        )
        assertTrue(
            "A full rewrite should not fire more than a handful of calls before checking in. Got: ${response.names()}",
            response.toolCalls.size <= 6,
        )
    }

    private companion object {
        val CALENDAR_TOOLS = setOf("add_event", "cancel_event", "move_event")
    }
}

class TextTransportEvals {

    @Before fun requireKey() { EvalHarness.requireKey() }

    @Test fun `a withheld tool is never called over text`() {
        val response = ask(
            listOf(user("call dad")),
            liveContext(textTransport = true),
            allowedTools = AgentTools.textTransport,
        )
        assertTrue(
            "place_call is withheld on the text transport. Got: ${response.names()}",
            response.callsTo("place_call").isEmpty(),
        )
        assertTrue("It should say where to ask instead. Got: '${response.speechResponse}'",
            response.speechResponse.isNotBlank())
    }

    @Test fun `an allowed tool still works over text`() {
        val response = ask(
            listOf(user("what's on this week?")),
            liveContext(
                upcoming = listOf("Kids football — Fri 18 Sep, 5:30 PM"),
                textTransport = true,
            ),
            allowedTools = AgentTools.textTransport,
        )
        // Upcoming events ride in the ambient context, so answering with no tool call is correct.
        assertTrue(
            "Expected either a direct answer or get_events. Got: ${response.names()}",
            response.toolCalls.isEmpty() || response.names() == listOf("get_events"),
        )
    }
}
