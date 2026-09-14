package com.teya.agent.harness.actuators

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CalendarWeekdayTest {

    private val monday = LocalDate.of(2026, 9, 14)

    @Test fun `named weekdays land on that weekday, not the next day`() {
        assertEquals(LocalDate.of(2026, 9, 14), CalendarActuator.expectedDate("monday", monday))
        assertEquals(LocalDate.of(2026, 9, 16), CalendarActuator.expectedDate("wednesday", monday))
        assertEquals(LocalDate.of(2026, 9, 18), CalendarActuator.expectedDate("friday", monday))
        assertEquals(LocalDate.of(2026, 9, 18), CalendarActuator.expectedDate("fri", monday))
        assertEquals(LocalDate.of(2026, 9, 14), CalendarActuator.expectedDate("mondays", monday))
        assertEquals(LocalDate.of(2026, 9, 16), CalendarActuator.expectedDate("wednesdays", monday))
    }

    @Test fun `next skips a week`() {
        assertEquals(LocalDate.of(2026, 9, 25), CalendarActuator.expectedDate("next friday", monday))
    }

    @Test fun `relative words and plain dates`() {
        assertEquals(LocalDate.of(2026, 9, 15), CalendarActuator.expectedDate("tomorrow", monday))
        assertEquals(LocalDate.of(2026, 9, 18), CalendarActuator.expectedDate("2026-09-18", monday))
    }

    @Test fun `nonsense is ignored rather than guessed`() {
        assertNull(CalendarActuator.expectedDate("garbage", monday))
        assertNull(CalendarActuator.expectedDate("", monday))
    }
}
