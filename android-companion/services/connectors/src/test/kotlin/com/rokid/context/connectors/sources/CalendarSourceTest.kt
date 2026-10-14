package com.rokid.context.connectors.sources

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarSourceTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int = 0, plusDays: Long = 0) = LocalDateTime.of(2026, 10, 12, h, m).plusDays(plusDays).atZone(zone).toInstant().toEpochMilli()

    @Test fun nextTwentyFourHoursOnlyFiveMax() = runBlocking {
        val now = at(9)
        val events = listOf(
            CalendarEvent("Six", at(15), at(16), null),
            CalendarEvent("Two", at(10), at(11), "Room 4"),
            CalendarEvent("Far", at(10, plusDays = 3), at(11, plusDays = 3), null),
            CalendarEvent("One", at(9, 30), at(10), null),
            CalendarEvent("Five", at(14), at(15), null),
            CalendarEvent("Three", at(12), at(13), null),
            CalendarEvent("Four", at(13), at(14), null),
        )
        var range: Pair<Long, Long>? = null
        val lines = CalendarSource(zone) { from, to -> range = from to to; events }.lines(null, now)
        // A day of slack each side, so an all-day event (UTC dates) is found in any zone; timed events are cut in code.
        assertEquals(now - 24 * 3_600_000L to now + 48 * 3_600_000L, range)
        assertEquals(
            listOf(
                "event \u00b7 One \u00b7 Mon 09:30\u201310:00",
                "event \u00b7 Two \u00b7 Mon 10:00\u201311:00 \u00b7 Room 4",
                "event \u00b7 Three \u00b7 Mon 12:00\u201313:00",
                "event \u00b7 Four \u00b7 Mon 13:00\u201314:00",
                "event \u00b7 Five \u00b7 Mon 14:00\u201315:00",
            ),
            lines,
        )
    }

    @Test fun titleAndLocationAreClipped() = runBlocking {
        val now = at(9)
        val line = CalendarSource(zone) { _, _ -> listOf(CalendarEvent("t".repeat(120), at(10), at(11), "l".repeat(90))) }.lines(null, now).single()
        val parts = line.split(" \u00b7 ")
        assertEquals(80, parts[1].length)
        assertEquals(60, parts[3].length)
    }

    /** An all-day event as CalendarContract stores it: UTC midnight to UTC midnight (end exclusive). */
    private fun allDay(title: String, y: Int, mo: Int, d: Int, days: Long = 1): CalendarEvent {
        val begin = java.time.LocalDate.of(y, mo, d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        return CalendarEvent(title, begin, begin + days * 24 * 3_600_000L, null, allDay = true)
    }

    /** BUG-R2-3: an all-day event is a date (UTC, as CalendarContract defines it) with no time, never "05:30". */
    @Test fun allDayEventShowsItsDateWithNoTime() = runBlocking {
        val lines = CalendarSource(zone) { _, _ -> listOf(allDay("Diwali", 2026, 10, 12), allDay("Trip", 2026, 10, 12, days = 3)) }.lines(null, at(9))
        assertEquals(listOf("event \u00b7 Diwali \u00b7 all day Mon 12 Oct", "event \u00b7 Trip \u00b7 all day Mon 12 Oct\u2013Wed 14 Oct"), lines)
    }

    /** BUG-R2-3 ruling: while a timed event remains today, all-day events come after every timed one (never the "next"). */
    @Test fun allDayEventsComeAfterTimedEventsWhileOneRemainsToday() = runBlocking {
        val events = listOf(allDay("Diwali", 2026, 10, 12), CalendarEvent("Standup", at(17), at(17, 30), null), CalendarEvent("Gym", at(7, plusDays = 1), at(8, plusDays = 1), null))
        val lines = CalendarSource(zone) { _, _ -> events }.lines(null, at(16, 24))
        assertEquals(
            listOf("event \u00b7 Standup \u00b7 Mon 17:00\u201317:30", "event \u00b7 Gym \u00b7 Tue 07:00\u201308:00", "event \u00b7 Diwali \u00b7 all day Mon 12 Oct"),
            lines,
        )
    }

    /** BUG-R2-3 ruling: with no timed event left today, today's all-day event leads; tomorrow's all-day ones still follow. */
    @Test fun todaysAllDayEventLeadsWhenNoTimedEventRemainsToday() = runBlocking {
        val events = listOf(
            allDay("Holiday tomorrow", 2026, 10, 13), CalendarEvent("Gym", at(7, plusDays = 1), at(8, plusDays = 1), null),
            allDay("Diwali", 2026, 10, 12), CalendarEvent("Done", at(9), at(10), null),
        )
        val lines = CalendarSource(zone) { _, _ -> events }.lines(null, at(16, 24))
        assertEquals(
            listOf("event \u00b7 Diwali \u00b7 all day Mon 12 Oct", "event \u00b7 Gym \u00b7 Tue 07:00\u201308:00", "event \u00b7 Holiday tomorrow \u00b7 all day Tue 13 Oct"),
            lines,
        )
    }

    /** BUG-R2-3: the dates are UTC whatever the zone: in New York a 12 Oct all-day event is still "12 Oct", all of 12 Oct. */
    @Test fun allDayDatesAreUtcInAnyZone() = runBlocking {
        val ny = ZoneId.of("America/New_York")
        val now = LocalDateTime.of(2026, 10, 12, 21, 0).atZone(ny).toInstant().toEpochMilli() // 01:00 UTC on the 13th
        val lines = CalendarSource(ny) { _, _ -> listOf(allDay("Diwali", 2026, 10, 12), allDay("Old", 2026, 10, 10)) }.lines(null, now)
        assertEquals(listOf("event \u00b7 Diwali \u00b7 all day Mon 12 Oct"), lines)
    }
}
