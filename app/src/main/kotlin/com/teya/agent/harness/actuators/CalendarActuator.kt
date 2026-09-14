package com.teya.agent.harness.actuators

import com.teya.agent.calendar.CalendarManager
import com.teya.agent.brain.ToolCall
import com.teya.agent.household.HouseholdManager
import com.teya.agent.household.Member
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
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
            val startMillis = resolveStart(tool.arguments["start"], tool.arguments["weekday"])
            if (title == null || startMillis == null) {
                "I couldn't add it — I need a title and start as ISO YYYY-MM-DDTHH:MM " +
                    "(look the day up from This week in live state)."
            } else {
                val duration = tool.arguments["duration_minutes"]?.toIntOrNull()?.takeIf { it > 0 } ?: 60
                val location = tool.arguments["location"]?.takeIf { it.isNotBlank() }
                val rrule = repeatToRrule(tool.arguments["repeat"], tool.arguments["until"])
                val reminderMinutes = tool.arguments["reminder_minutes"]?.toIntOrNull()?.takeIf { it >= 0 }

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
                    if (reminderMinutes != null) {
                        withContext(Dispatchers.IO) {
                            calendarManager.updateEvent(
                                existing.id, existing.recurring, null, null, null, null, reminderMinutes,
                            )
                        }
                        "\"$title\" is already on the calendar at ${spellOut(existing.startMillis)}; " +
                            "reminder set to $reminderMinutes min before."
                    } else {
                        "\"$title\" is ALREADY on the calendar at ${spellOut(existing.startMillis)} — nothing added, " +
                            "there is no second copy. If the details need to change, use update_event on it."
                    }
                } else {
                    val id = withContext(Dispatchers.IO) {
                        calendarManager.addEvent(
                            title, startMillis, duration, location, rrule,
                            invitable.map { it.email }, reminderMinutes,
                        )
                    }
                    if (id != null) {
                        "Added \"$title\" on ${spellOut(startMillis)}" + (if (rrule != null) ", repeating" else "") +
                            (location?.let { " at $it" } ?: "") +
                            (reminderMinutes?.let { ", reminder $it min before" } ?: "") +
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
            val startArg = tool.arguments["start"]
            val endArg = tool.arguments["end"]
            val weekday = tool.arguments["weekday"]
            val zone = ZoneId.systemDefault()
            val day = weekday?.let { expectedDate(it.trim().lowercase(), LocalDate.now(zone)) }
            val start = when {
                day != null -> day.atStartOfDay(zone).toInstant().toEpochMilli()
                startArg.isNullOrBlank() ->
                    LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
                else -> parseIsoToMillis(startArg)
            }
            val end = when {
                day != null && endArg.isNullOrBlank() ->
                    day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                endArg.isNullOrBlank() -> start?.plus(7 * 24 * 3600_000L)
                else -> parseIsoToMillis(endArg)
            }
            when {
                start == null ->
                    "I couldn't read start — pass a weekday ('friday') or ISO YYYY-MM-DDTHH:MM."
                end == null ->
                    "I couldn't read end — pass ISO YYYY-MM-DDTHH:MM."
                else -> {
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
            }
        }
        "cancel_event" -> {
            val query = tool.arguments["title"]?.takeIf { it.isNotBlank() }
            if (query == null) {
                "Which event should I cancel? Tell me its name."
            } else withContext(Dispatchers.IO) {
                val matches = calendarManager.findEventsByTitle(query)
                val picked = pickEvent(matches, tool.arguments["start"], tool.arguments["weekday"], query)
                val all = tool.arguments["all"]?.toBoolean() == true
                when {
                    matches.isEmpty() -> "I couldn't find an event matching \"$query\" to cancel."
                    all -> {
                        if (!budget.spendOrRefuse()) {
                            "STOP — nothing was cancelled. You have already removed ${budget.limit} " +
                                "things from the calendar in this one turn, which is as many as you may " +
                                "do without checking. Tell the person what you have removed so far and " +
                                "what is still left to do, and wait for them to confirm before " +
                                "removing anything else."
                        } else {
                            val removed = matches.count { calendarManager.deleteEvent(it.id) }
                            "Removed all $removed events matching \"$query\"."
                        }
                    }
                    picked == null -> "STOP — nothing was cancelled, and you must not run any more " +
                        "calendar calls this turn. Several events match \"$query\". Reply asking " +
                        "which one, and wait for the answer before doing anything else: " +
                        describeMatches(matches)
                    !budget.spendOrRefuse() ->
                        "STOP — nothing was cancelled. You have already removed ${budget.limit} " +
                            "things from the calendar in this one turn, which is as many as you may " +
                            "do without checking. Tell the person what you have removed so far and " +
                            "what is still left to do, and wait for them to confirm before " +
                            "removing anything else."
                    calendarManager.deleteEvent(picked.id) ->
                        "Removed \"${picked.title}\" (${spellOut(picked.startMillis)})" +
                            (if (picked.recurring) " and the whole repeating series" else "") + " from the calendar."
                    else -> "I couldn't remove \"${picked.title}\" — the calendar refused the change."
                }
            }
        }
        "update_event" -> {
            val query = tool.arguments["title"]?.takeIf { it.isNotBlank() }
            if (query == null) {
                "Which event should I change? Tell me its name."
            } else withContext(Dispatchers.IO) {
                val matches = calendarManager.findEventsByTitle(query)
                val picked = pickEvent(matches, tool.arguments["start"], tool.arguments["weekday"], query)
                val newWeekday = tool.arguments["new_weekday"]
                val newStartArg = tool.arguments["new_start"]
                var newStart = resolveStart(newStartArg, newWeekday)
                val duration = tool.arguments["duration_minutes"]?.toIntOrNull()?.takeIf { it > 0 }
                val location = tool.arguments["location"]?.takeIf { it.isNotBlank() }
                val newTitle = tool.arguments["new_title"]?.takeIf { it.isNotBlank() }
                val reminderMinutes = tool.arguments["reminder_minutes"]?.toIntOrNull()?.takeIf { it >= 0 }
                when {
                    matches.isEmpty() -> "There's no event matching \"$query\" on the calendar to change."
                    picked == null -> "STOP — nothing was changed, and you must not run any more " +
                        "calendar calls this turn. Several events match \"$query\". Reply asking " +
                        "which one, and wait for the answer: " + describeMatches(matches)
                    newStartArg != null && newStart == null ->
                        "I couldn't read new_start — pass a clock time or ISO YYYY-MM-DDTHH:MM."
                    else -> {
                        // "Move it to Friday" with no clock time: keep the existing time of day.
                        if (newStart != null && newStartArg.isNullOrBlank() && !newWeekday.isNullOrBlank()) {
                            val zone = ZoneId.systemDefault()
                            val time = Instant.ofEpochMilli(picked.startMillis).atZone(zone).toLocalTime()
                            val date = Instant.ofEpochMilli(newStart).atZone(zone).toLocalDate()
                            newStart = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                        }
                        if (newStart == null && duration == null && location == null &&
                            newTitle == null && reminderMinutes == null
                        ) {
                            "Nothing to change — tell me what about \"${picked.title}\" is different now."
                        } else if (calendarManager.updateEvent(
                                picked.id, picked.recurring, newStart, duration, location, newTitle, reminderMinutes,
                            )
                        ) {
                            val what = listOfNotNull(
                                newStart?.let { "now ${spellOut(it)}" },
                                duration?.let { "$it minutes long" },
                                location?.let { "at $it" },
                                newTitle?.let { "called \"$it\"" },
                                reminderMinutes?.let { "reminder $it min before" },
                            ).joinToString(", ")
                            "Updated \"${picked.title}\": $what." +
                                if (picked.recurring) " That's a repeating event, so every occurrence changed." else ""
                        } else {
                            "I couldn't change \"${picked.title}\" — the calendar refused the update."
                        }
                    }
                }
            }
        }
        else -> "Unknown calendar tool: ${tool.functionName}"
    }

    /** The only match, or the one whose start was named. Null = ambiguous, and the caller must ask. */
    private fun pickEvent(
        matches: List<CalendarManager.EventMatch>,
        startArg: String?,
        weekdayArg: String?,
        query: String,
    ): CalendarManager.EventMatch? {
        if (matches.size == 1) return matches.first()
        val wanted = resolveStart(startArg, weekdayArg)
        if (wanted != null) {
            matches.firstOrNull { kotlin.math.abs(it.startMillis - wanted) < 60_000L }?.let { return it }
            calendarManager.findEventAt(query, wanted)?.let { inst ->
                return matches.firstOrNull { it.id == inst.id } ?: inst
            }
        }
        val hasClock = parseClockTime(startArg) != null || startArg?.let { parseIsoToMillis(it) } != null
        if (!hasClock && !weekdayArg.isNullOrBlank()) {
            val date = expectedDate(weekdayArg.trim().lowercase(), LocalDate.now()) ?: return null
            val inst = calendarManager.findEventOn(query, date) ?: return null
            return matches.firstOrNull { it.id == inst.id } ?: inst
        }
        return null
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
        val trimmed = iso.trim().replace(' ', 'T')
        return runCatching { LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(trimmed).atStartOfDay(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }

    /**
     * Date comes from [weekday] when they named a day ("monday", "wednesdays") — the model's ISO
     * date is often one day off. Time of day still comes from [startArg].
     */
    private fun resolveStart(startArg: String?, weekday: String?): Long? {
        val iso = startArg?.let { parseIsoToMillis(it) }
        val zone = ZoneId.systemDefault()
        val raw = weekday?.trim()?.lowercase()?.removePrefix("on ")?.trim().orEmpty()
        val date = if (raw.isNotEmpty()) expectedDate(raw, LocalDate.now(zone)) else null
        val time = iso?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            ?: parseClockTime(startArg)
        return when {
            date != null -> date.atTime(time ?: LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
            else -> iso
        }
    }

    /** "17:30", "5:30pm", "5pm". Null when it isn't a clock time. */
    private fun parseClockTime(time: String?): LocalTime? {
        val t = time?.trim()?.lowercase()?.replace(".", "")?.takeIf { it.isNotEmpty() } ?: return null
        if (t.contains("-") || t.contains("t")) return null
        val pm = t.endsWith("pm")
        val am = t.endsWith("am")
        val body = t.removeSuffix("pm").removeSuffix("am").trim()
        val parts = body.split(":", "h").filter { it.isNotBlank() }
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        if (minute !in 0..59) return null
        val h = when {
            pm && hour < 12 -> hour + 12
            am && hour == 12 -> 0
            else -> hour
        }
        return if (h in 0..23) LocalTime.of(h, minute) else null
    }

    companion object {
        private val WEEKDAYS: Map<String, DayOfWeek> = DayOfWeek.entries.flatMap { d ->
            val full = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()
            listOf(full to d, full.take(3) to d)
        }.toMap()

        internal fun expectedDate(raw: String, today: LocalDate): LocalDate? {
            when (raw) {
                "today", "tonight" -> return today
                "tomorrow" -> return today.plusDays(1)
                "yesterday" -> return today.minusDays(1)
            }
            runCatching { LocalDate.parse(raw) }.getOrNull()?.let { return it }
            val next = raw.startsWith("next ")
            val key = raw.removePrefix("next ").removePrefix("this ").removePrefix("coming ").trim()
            val dow = WEEKDAYS[key] ?: WEEKDAYS[key.removeSuffix("s")] ?: return null
            var date = today
            if (date.dayOfWeek != dow) do { date = date.plusDays(1) } while (date.dayOfWeek != dow)
            return if (next) date.plusWeeks(1) else date
        }
    }
}
