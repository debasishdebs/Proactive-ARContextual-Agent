package com.rokid.context.phone.ui.activity

import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ActivityModelTest {
    @Test fun unknownPeriodFallsBackToToday() {
        assertEquals(Period.TODAY, Period.parse(null))
        assertEquals(Period.TODAY, Period.parse("yearly"))
        assertEquals(Period.WEEK, Period.parse(" week "))
        assertEquals(Period.MONTH, Period.parse("MONTH"))
    }

    @Test fun weekStartsMondayWithNarrowLabels() {
        val w = PeriodWindow.of(Period.WEEK, LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2026, 10, 12), w.start)
        assertEquals(LocalDate.of(2026, 10, 19), w.endExclusive)
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), ActivityModel.bars(w, emptyMap()).map { it.label })
    }

    @Test fun monthCoversTheWholeMonth() {
        val w = PeriodWindow.of(Period.MONTH, LocalDate.of(2026, 2, 14))
        assertEquals(28, w.days.size)
        assertEquals(listOf("1", "8", "15", "22"), ActivityModel.bars(w, emptyMap()).map { it.label }.filter { it.isNotEmpty() })
    }

    @Test fun dstWeekStillHasSevenBars() {
        val ny = ZoneId.of("America/New_York")
        val w = PeriodWindow.of(Period.WEEK, LocalDate.of(2026, 3, 8)) // US DST starts Sun 8 Mar 2026 02:00
        assertEquals(LocalDate.of(2026, 3, 2), w.start)
        assertEquals(7, w.days.size)
        val r = w.millis(ny)
        assertEquals(167L * 3_600_000, r.last + 1 - r.first)
        val lateSunday = LocalDate.of(2026, 3, 8).atTime(23, 30).atZone(ny).toInstant().toEpochMilli()
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 1), ActivityModel.bars(w, ActivityModel.countByDay(listOf(lateSunday, r.first), w, ny)).map { it.count })
    }

    @Test fun bucketsUseTheZoneGivenAtQueryTime() {
        val instant = Instant.parse("2026-10-11T20:00:00Z").toEpochMilli() // 01:30 on 12 Oct in Kolkata, still 11 Oct in UTC
        val w = PeriodWindow.of(Period.WEEK, TODAY)
        assertEquals(mapOf(TODAY to 1), ActivityModel.countByDay(listOf(instant), w, ZONE))
        assertTrue(ActivityModel.countByDay(listOf(instant), w, ZoneOffset.UTC).isEmpty())
    }

    @Test fun sectionsSplitAtNoonAndFivePmOldestFirst() {
        val mems = listOf(
            Fixtures.memory("e", "Dinner", Fixtures.at(TODAY, 17, 0)),
            Fixtures.memory("a2", "Late lunch", Fixtures.at(TODAY, 16, 59)),
            Fixtures.memory("a1", "Lunch", Fixtures.at(TODAY, 12, 0)),
            Fixtures.memory("m2", "Coffee", Fixtures.at(TODAY, 11, 59)),
            Fixtures.memory("m1", "Breakfast", Fixtures.at(TODAY, 7, 40), frameId = "f1", confidence = 0.92),
        )
        val s = Timeline.sections(mems, listOf(Fixtures.frame("f1", 1, path = "/f1.jpg")), ZONE)
        assertEquals(listOf("Morning", "Afternoon", "Evening"), s.map { it.label })
        assertEquals(listOf("m1", "m2"), s[0].rows.map { it.memoryId })
        assertEquals(listOf("a1", "a2"), s[1].rows.map { it.memoryId })
        assertEquals("07:40", s[0].rows[0].time)
        assertEquals("/f1.jpg", s[0].rows[0].framePath)
        assertEquals(null, s[0].rows[1].framePath)
    }

    @Test fun emptyWeekShowsZeroBarsAndHint() {
        val w = PeriodWindow.of(Period.WEEK, TODAY)
        assertEquals(List(7) { 0 }, ActivityModel.bars(w, emptyMap()).map { it.count })
        assertTrue(ActivityModel.dayRows(w, emptyMap(), emptyMap(), TODAY).isEmpty())
    }

    @Test fun dayRowsAreNewestFirstWithCounts() {
        val today = TODAY.plusDays(1) // Tue 13 Oct
        val w = PeriodWindow.of(Period.WEEK, today)
        val rows = ActivityModel.dayRows(w, frameCounts = mapOf(TODAY to 1), memoryCounts = mapOf(TODAY to 2, today to 1), today = today)
        assertEquals(listOf(DayRow(today, "Today", "0 frames · 1 memory"), DayRow(TODAY, "Yesterday", "1 frame · 2 memories")), rows)
        assertEquals("Sat 10 Oct", ActivityModel.dayLabel(LocalDate.of(2026, 10, 10), today))
        assertEquals("Fri 10 Oct 2025", ActivityModel.dayLabel(LocalDate.of(2025, 10, 10), today))
    }

    @Test fun headersPerPeriod() {
        assertEquals("Mon 12 Oct", ActivityModel.header(PeriodWindow.of(Period.TODAY, TODAY), TODAY))
        assertEquals("Week of 12 Oct", ActivityModel.header(PeriodWindow.of(Period.WEEK, TODAY), TODAY))
        assertEquals("October 2026", ActivityModel.header(PeriodWindow.of(Period.MONTH, TODAY), TODAY))
    }
}
