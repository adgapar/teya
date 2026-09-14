package com.teya.agent.harness.actuators

import android.util.Log
import com.teya.agent.calendar.CalendarManager
import com.teya.agent.calendar.WhenResolver
import com.teya.agent.brain.ToolCall
import com.teya.agent.household.HouseholdManager
import com.teya.agent.household.Member
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Runs the calendar tools and phrases their results for the model.
 *
 * [budget] caps how many events one turn may delete before it has to stop and report.
 */
class CalendarActuator(
    private val calendarManager: CalendarManager,
    private val householdManager: HouseholdManager,
) {
    /** A per-turn allowance for deletions, so a single turn cannot rewrite a calendar unattended. */
    class DeletionBudget(private val max: Int) {
        private var used = 0
        val limit: Int get() = max
        fun spendOrRefuse(): Boolean = ++used <= max
    }

    suspend fun run(tool: ToolCall, budget: DeletionBudget): String = when (tool.functionName) {
        "add_event" -> {
            val title = tool.arguments["title"]?.takeIf { it.isNotBlank() }
            val day = tool.arguments["day"]
            val startMillis = WhenResolver.resolve(day, tool.arguments["time"])
                ?: tool.arguments["start"]?.let { parseIsoToMillis(it) }
            if (title == null || startMillis == null) {
                "I couldn't add it — I need a title, which day, and what time."
            } else {
                val duration = tool.arguments["duration_minutes"]?.toIntOrNull()?.takeIf { it > 0 } ?: 60
                val location = tool.arguments["location"]?.takeIf { it.isNotBlank() }
                val rrule = repeatToRrule(tool.arguments["repeat"], tool.arguments["until"])

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

                val existing = withContext(Dispatchers.IO) { calendarManager.findEventsByTitle(title) }
                    .firstOrNull { kotlin.math.abs(it.startMillis - startMillis) < 60_000L }
                if (existing != null) {
                    "\"$title\" is ALREADY on the calendar at ${spellOut(startMillis)} — nothing added, " +
                        "there is no second copy. If the details need to change, use move_event on it."
                } else {
                    val id = withContext(Dispatchers.IO) {
                        calendarManager.addEvent(title, startMillis, duration, location, rrule, invitable.map { it.email })
                    }
                    if (id != null) {
                        "Added \"$title\" on ${spellOut(startMillis)}" + (if (rrule != null) ", repeating" else "") +
                            (location?.let { " at $it" } ?: "") +
                            (if (invitable.isNotEmpty()) ", invited ${invitable.joinToString(", ") { it.displayName }}" else "") +
                            "." +
                            (if (missingEmail.isNotEmpty()) " I couldn't invite ${missingEmail.joinToString(", ")} — no email on file." else "")
                    } else {
                        "I couldn't add that to the calendar."
                    }
                }
            }
        }
        "get_events" -> {
            // Default the range start to the START of today, not "now" — a bare "what's on today?"
            // must include events earlier in the day (the 6pm event asked about at 8pm), not just
            // what's still ahead.
            val start = tool.arguments["start"]?.let { parseIsoToMillis(it) }
                ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
            } else withContext(Dispatchers.IO) {
                val matches = calendarManager.findEventsByTitle(query)
                val picked = pickEvent(matches, tool.arguments["start"])
                val all = tool.arguments["all"]?.toBoolean() == true
                when {
                    !budget.spendOrRefuse() ->
                        "STOP — nothing was cancelled. You have already removed ${budget.limit} " +
                            "things from the calendar in this one turn, which is as many as you may " +
                            "do without checking. Tell the person what you have removed so far and " +
                            "what is still left to do, and wait for them to confirm before " +
                            "removing anything else."
                    matches.isEmpty() -> "I couldn't find an event matching \"$query\" to cancel."
                    all -> {
                        val removed = matches.count { calendarManager.deleteEvent(it.id) }
                        "Removed all $removed events matching \"$query\"."
                    }
                    picked == null -> "STOP — nothing was cancelled, and you must not run any more " +
                        "calendar calls this turn. Several events match \"$query\". Reply asking " +
                        "which one, and wait for the answer before doing anything else: " +
                        describeMatches(matches)
                    calendarManager.deleteEvent(picked.id) ->
                        "Removed \"${picked.title}\" (${spellOut(picked.startMillis)})" +
                            (if (picked.recurring) " and the whole repeating series" else "") + " from the calendar."
                    else -> "I couldn't remove \"${picked.title}\" — the calendar refused the change."
                }
            }
        }
        "move_event" -> {
            val query = tool.arguments["title"]?.takeIf { it.isNotBlank() }
            if (query == null) {
                "Which event should I change? Tell me its name."
            } else withContext(Dispatchers.IO) {
                val matches = calendarManager.findEventsByTitle(query)
                val picked = pickEvent(matches, tool.arguments["start"])
                val newStart = WhenResolver.resolve(tool.arguments["new_day"], tool.arguments["new_time"])
                    ?: tool.arguments["new_start"]?.let { parseIsoToMillis(it) }
                    // A time with no day means "same day, new time".
                    ?: WhenResolver.parseTime(tool.arguments["new_time"])?.let { t ->
                        picked?.let {
                            Instant.ofEpochMilli(it.startMillis).atZone(ZoneId.systemDefault())
                                .with(t).toInstant().toEpochMilli()
                        }
                    }
                val duration = tool.arguments["duration_minutes"]?.toIntOrNull()?.takeIf { it > 0 }
                val location = tool.arguments["location"]?.takeIf { it.isNotBlank() }
                val newTitle = tool.arguments["new_title"]?.takeIf { it.isNotBlank() }
                when {
                    matches.isEmpty() -> "There's no event matching \"$query\" on the calendar to change."
                    picked == null -> "STOP — nothing was changed, and you must not run any more " +
                        "calendar calls this turn. Several events match \"$query\". Reply asking " +
                        "which one, and wait for the answer: " + describeMatches(matches)
                    newStart == null && duration == null && location == null && newTitle == null ->
                        "Nothing to change — tell me what about \"${picked.title}\" is different now."
                    calendarManager.moveEvent(picked.id, picked.recurring, newStart, duration, location, newTitle) -> {
                        val what = listOfNotNull(
                            newStart?.let { "now ${spellOut(it)}" },
                            duration?.let { "$it minutes long" },
                            location?.let { "at $it" },
                            newTitle?.let { "called \"$it\"" },
                        ).joinToString(", ")
                        "Updated \"${picked.title}\": $what." +
                            if (picked.recurring) " That's a repeating event, so every occurrence moved." else ""
                    }
                    else -> "I couldn't change \"${picked.title}\" — the calendar refused the update."
                }
            }
        }
        else -> "Unknown calendar tool: ${tool.functionName}"
    }

    /** The only match, or the one whose start was named. Null = ambiguous, and the caller must ask. */
    private fun pickEvent(
        matches: List<CalendarManager.EventMatch>,
        startArg: String?,
    ): CalendarManager.EventMatch? {
        if (matches.size == 1) return matches.first()
        val wanted = startArg?.let { parseIsoToMillis(it) } ?: return null
        return matches.firstOrNull { kotlin.math.abs(it.startMillis - wanted) < 60_000L }
    }

    /** Weekday-first timestamp; every calendar result names the day it landed on, not an ISO echo. */
    private fun spellOut(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEEE d MMMM, h:mm a", Locale.ENGLISH))

    /** The ambiguity list; the ISO start is what the model hands back to pick a row. */
    private fun describeMatches(matches: List<CalendarManager.EventMatch>): String =
        matches.joinToString("; ") { m ->
            val iso = Instant.ofEpochMilli(m.startMillis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            "\"${m.title}\" ${spellOut(m.startMillis)} (start=$iso)" + if (m.recurring) " (repeating)" else ""
        }

    private fun trustedOrganizerEmails(members: List<Member>): Set<String> =
        members.mapNotNull { it.email.takeIf { e -> e.isNotBlank() }?.lowercase() }.toSet()

    private fun splitItems(raw: String?): List<String> =
        raw?.split(Regex("\\s*(?:,|;|\\band\\b|\\n)\\s*"))?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    /** RFC-5545 RRULE from a friendly repeat word; [until] is the last local day the series runs. */
    private fun repeatToRrule(repeat: String?, until: String?): String? {
        val freq = when (repeat?.lowercase()?.trim()) {
            "daily" -> "FREQ=DAILY"
            "weekly" -> "FREQ=WEEKLY"
            "monthly" -> "FREQ=MONTHLY"
            "yearly" -> "FREQ=YEARLY"
            "weekdays" -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
            else -> null
        } ?: return null
        val untilPart = until?.let { untilToRruleClause(it) }
        return if (untilPart != null) "$freq;$untilPart" else freq
    }

    private fun untilToRruleClause(untilDate: String): String? = runCatching {
        val endOfDayLocal = LocalDate.parse(untilDate).atTime(23, 59, 59).atZone(ZoneId.systemDefault())
        val utcStamp = endOfDayLocal.withZoneSameInstant(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        "UNTIL=$utcStamp"
    }.getOrNull()

    private fun parseIsoToMillis(iso: String): Long? {
        val zone = ZoneId.systemDefault()
        return runCatching { LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(iso).atStartOfDay(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private companion object {
        const val TAG = "CalendarActuator"
    }
}
