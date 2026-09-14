package com.teya.agent.evals

import com.teya.agent.evals.EvalHarness.ask
import com.teya.agent.evals.EvalHarness.callsTo
import com.teya.agent.evals.EvalHarness.liveContext
import com.teya.agent.evals.EvalHarness.names
import com.teya.agent.evals.EvalHarness.toolRound
import com.teya.agent.evals.EvalHarness.user
import com.teya.agent.persona.AgentTools
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalendarEvals {

    @Before fun requireKey() { EvalHarness.requireKey() }

    @Test fun `changing an existing event updates it instead of adding another`() {
        val response = ask(
            listOf(
                user("add kids breakdance on wednesday at 5pm at the dance studio"),
            ) + toolRound(
                "add_event",
                mapOf("title" to "Kids breakdance", "start" to "2026-09-16T17:00"),
                "Added \"Kids breakdance\" on Wednesday 16 September, 5:00 PM at the dance studio.",
            ) + listOf(
                user("actually it's at 5:30, not 5"),
            ),
            liveContext(upcoming = listOf("Kids breakdance — Wed 16 Sep, 5:00 PM at dance studio")),
        )
        assertTrue(
            "Correcting an event must update it, not add a duplicate. Got: ${response.names()}",
            response.callsTo("update_event").isNotEmpty(),
        )
        assertTrue(
            "A correction must never create a second event. Got: ${response.names()}",
            response.callsTo("add_event").isEmpty(),
        )
    }

    @Test fun `a location or name correction updates the event, it does not add another`() {
        val response = ask(
            listOf(
                user("add swimming on tuesday at 4:35 at the pool"),
            ) + toolRound(
                "add_event",
                mapOf("title" to "Swimming", "start" to "2026-09-15T16:35"),
                "Added \"Swimming\" on Tuesday 15 September, 4:35 PM at the pool.",
            ) + listOf(
                user("it's at the studio now, and call it Asier swimming"),
            ),
            liveContext(upcoming = listOf("Swimming — Tue 15 Sep, 4:35 PM at the pool")),
        )
        assertTrue(
            "A place/name correction is update_event, not add_event. Got: ${response.names()}",
            response.callsTo("update_event").isNotEmpty(),
        )
        assertTrue(
            "A correction must never create a second event. Got: ${response.names()}",
            response.callsTo("add_event").isEmpty(),
        )
        val call = response.callsTo("update_event").first()
        val loc = call.arguments["location"].orEmpty().lowercase()
        val newTitle = call.arguments["new_title"].orEmpty().lowercase()
        assertTrue(
            "Should pass the new place and/or name. Got: ${call.arguments}",
            loc.contains("studio") || newTitle.contains("asier") || newTitle.contains("swimming"),
        )
    }

    @Test fun `a named weekday is copied as a word, not computed as a date`() {
        var history = listOf(user("put kids football on friday at 5:30pm at the football field, every week"))
        var response = ask(history, liveContext())
        response.callsTo("get_events").firstOrNull()?.let { check ->
            history = history + toolRound(
                "get_events", check.arguments, "No events found in that range.",
            )
            response = ask(history, liveContext())
        }
        val call = response.callsTo("add_event").firstOrNull()
        assertTrue("Expected an add_event call, got: ${response.names()}", call != null)
        val weekday = call!!.arguments["weekday"].orEmpty().lowercase()
        assertTrue(
            "weekday must be the word they used ('friday'), not a shifted day. Got weekday='$weekday' start='${call.arguments["start"]}'",
            weekday.contains("friday") || weekday.contains("fri"),
        )
        val start = call.arguments["start"].orEmpty().lowercase()
        assertTrue(
            "start should be a clock time (or ISO that still has 17:30). Got start='$start'",
            start.contains("17:30") || start.contains("5:30"),
        )
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
        val weekday = call!!.arguments["weekday"].orEmpty().lowercase()
        val start = call.arguments["start"].orEmpty()
        assertTrue(
            "It should pick Wednesday by weekday word, or the Wednesday start it was given. Got weekday='$weekday' start='$start'",
            weekday.contains("wednesday") || weekday.contains("wed") || start.contains("2026-09-16"),
        )
        assertTrue("all=true must not appear — only one was asked for",
            call.arguments["all"]?.lowercase() != "true")
    }

    /** The duplicate itself is refused by `add_event`; what the model owes is the recovery. */
    @Test fun `told an event already exists, it reports that instead of retrying`() {
        val response = ask(
            listOf(user("add kids football on friday at 5:30pm")) + toolRound(
                "add_event",
                mapOf("title" to "Kids football", "start" to "2026-09-18T17:30"),
                "\"Kids football\" is ALREADY on the calendar at Friday 18 September, 5:30 PM — " +
                    "nothing added, there is no second copy. If the details need to change, use " +
                    "update_event on it.",
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

    @Test fun `a whole-week schedule is applied in one turn, including reminders`() {
        var history = listOf(user(
            "Lets fix everything.\n" +
                "Mondays and Wednesdays 5:30-6:30 pm kids breakdance. Reminder 30 mins advance.\n" +
                "Tuesdays 16:35 Asier swimming. Reminder at 16:00.\n" +
                "Thursdays 17:30 kids football, reminder 1 hour in advance.\n" +
                "Nothing else should be on the calendar."
        ))
        var response = ask(
            history,
            liveContext(upcoming = listOf(
                "Kids breakdance — Wed 16 Sep, 5:30 PM",
                "Kids football — Fri 18 Sep, 5:30 PM",
                "Asier swimming — Tue 15 Sep, 4:35 PM",
            )),
        )
        response.callsTo("get_events").firstOrNull()?.let { check ->
            history = history + toolRound("get_events", check.arguments, "No events found in that range.")
            response = ask(history, liveContext())
        }
        val names = response.names()
        assertTrue(
            "A full week in one message must start changing the calendar, not only ask. Got: $names / '${response.speechResponse}'",
            response.callsTo("add_event").isNotEmpty() || response.callsTo("cancel_event").isNotEmpty(),
        )
        val cancels = response.callsTo("cancel_event")
        if (cancels.isNotEmpty()) {
            assertTrue(
                "A replace-everything cancel should pass all=true, not ask which copy. Got: ${cancels.map { it.arguments }}",
                cancels.any { it.arguments["all"]?.lowercase() == "true" },
            )
        }
        val adds = response.callsTo("add_event")
        if (adds.isNotEmpty()) {
            val reminders = adds.mapNotNull { it.arguments["reminder_minutes"] }
            assertTrue(
                "The asked-for advance reminders must land on add_event. Got: ${adds.map { it.arguments }}",
                reminders.any { it == "30" || it == "35" || it == "60" },
            )
        }
    }

    private companion object {
        val CALENDAR_TOOLS = setOf("add_event", "cancel_event", "update_event")
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
