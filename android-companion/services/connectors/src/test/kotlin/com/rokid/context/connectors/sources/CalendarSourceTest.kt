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
        assertEquals(now to now + 24 * 3_600_000L, range)
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
}
