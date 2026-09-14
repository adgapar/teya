package com.teya.agent.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "Now" is fixed at a Monday so every weekday case has one right answer. */
class WhenResolverTest {

    private val zone: ZoneId = ZoneId.of("Europe/Madrid")
    private val monday = LocalDateTime.of(2026, 9, 14, 12, 51)

    private fun at(day: String?, time: String?): String? =
        WhenResolver.resolve(day, time, monday, zone)?.let {
            Instant.ofEpochMilli(it).atZone(zone)
                .format(DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm"))
        }

    @Test fun `weekday resolves to that weekday, not the next day`() {
        assertEquals("Mon 2026-09-14 17:30", at("monday", "17:30"))
        assertEquals("Wed 2026-09-16 17:30", at("wednesday", "17:30"))
        assertEquals("Fri 2026-09-18 17:30", at("friday", "17:30"))
    }

    @Test fun `today counts only while the time is still ahead`() {
        assertEquals("Mon 2026-09-14 23:00", at("today", "23:00"))
        assertEquals("Mon 2026-09-21 09:00", at("monday", "09:00"))
    }

    @Test fun `next skips a week`() {
        assertEquals("Fri 2026-09-25 17:30", at("next friday", "17:30"))
    }

    @Test fun `relative words and plain dates`() {
        assertEquals("Tue 2026-09-15 09:00", at("tomorrow", "9:00"))
        assertEquals("Fri 2026-09-18 17:30", at("2026-09-18", "17:30"))
        assertEquals("Fri 2026-09-18 17:30", at("2026-09-18T17:30", null))
    }

    @Test fun `time formats people actually say`() {
        assertEquals("Fri 2026-09-18 17:30", at("fri", "5:30pm"))
        assertEquals("Sun 2026-09-20 00:00", at("sunday", "12am"))
        assertEquals("Sat 2026-09-19 12:00", at("saturday", "12pm"))
        assertEquals("Sat 2026-09-19 12:00", at("saturday", null))
    }

    @Test fun `nonsense resolves to nothing rather than to a guess`() {
        assertNull(at("garbage", "10:00"))
        assertNull(at(null, "10:00"))
        assertNull(at("", "10:00"))
    }
}
