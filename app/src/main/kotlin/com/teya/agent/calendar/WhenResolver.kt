package com.teya.agent.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Resolves a day word ("monday", "next friday", "tomorrow") or ISO date, plus a time, to epoch millis. */
object WhenResolver {

    private val RELATIVE = mapOf(
        "today" to 0L, "tonight" to 0L, "this evening" to 0L, "this afternoon" to 0L,
        "tomorrow" to 1L, "day after tomorrow" to 2L, "overmorrow" to 2L,
        "yesterday" to -1L,
    )

    private val WEEKDAYS: Map<String, DayOfWeek> = DayOfWeek.entries.flatMap { d ->
        val full = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()
        listOf(full to d, full.take(3) to d)
    }.toMap()

    /** Null when [day] is not understood. A bare weekday means the next one still ahead; "next X" skips a week. */
    fun resolve(
        day: String?,
        time: String?,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val raw = day?.trim()?.lowercase()?.removePrefix("on ")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val at = parseTime(time)

        runCatching { LocalDateTime.parse(raw.uppercase().replace(" ", "")) }
            .getOrNull()?.let { return it.atZone(zone).toInstant().toEpochMilli() }

        val date = resolveDate(raw, now.toLocalDate(), now.toLocalTime(), at) ?: return null
        return date.atTime(at ?: LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    }

    private fun resolveDate(raw: String, today: LocalDate, nowTime: LocalTime, at: LocalTime?): LocalDate? {
        runCatching { LocalDate.parse(raw) }.getOrNull()?.let { return it }
        RELATIVE[raw]?.let { return today.plusDays(it) }

        val nextWeek = raw.startsWith("next ")
        val key = stripPrefixes(raw)
        val weekday = WEEKDAYS[key] ?: return null

        var date = today
        val todayStillOpen = at == null || at.isAfter(nowTime)
        if (date.dayOfWeek != weekday || !todayStillOpen) {
            do { date = date.plusDays(1) } while (date.dayOfWeek != weekday)
        }
        return if (nextWeek) date.plusWeeks(1) else date
    }

    private fun stripPrefixes(raw: String): String =
        raw.removePrefix("next ").removePrefix("this ").removePrefix("coming ").trim()

    /** Accepts "17:30", "5:30 pm", "5pm", "17"; null when unusable. */
    fun parseTime(time: String?): LocalTime? {
        val t = time?.trim()?.lowercase()?.replace(".", "")?.takeIf { it.isNotEmpty() } ?: return null
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
}
