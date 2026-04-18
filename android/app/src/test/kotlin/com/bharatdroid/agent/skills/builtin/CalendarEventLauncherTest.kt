package com.bharatdroid.agent.skills.builtin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarEventLauncherTest {

    @Test
    fun `resolveEventDate supports ordinal day suffixes`() {
        val now = ZonedDateTime.of(2026, 4, 18, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

        val resolved = resolveEventDate("18th April 2026", now)

        assertEquals("2026-04-18", resolved.toString())
    }

    @Test
    fun `resolveEventTime supports dotted am pm format`() {
        val resolved = resolveEventTime("4.30 pm")

        assertEquals("16:30", resolved.toString())
    }

    @Test
    fun `resolveEventTime supports noon`() {
        val resolved = resolveEventTime("noon")

        assertEquals("12:00", resolved.toString())
    }
}
