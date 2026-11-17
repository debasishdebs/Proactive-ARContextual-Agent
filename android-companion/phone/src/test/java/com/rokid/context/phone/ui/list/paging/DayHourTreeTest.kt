package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.DayBucket
import com.rokid.context.phone.ui.preview.PauseSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayHourTreeTest {
    private val utc = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 11, 12)
    private fun at(h: Int, m: Int = 0, d: LocalDate = day, zone: ZoneId = utc) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun nodes(e: List<HourEntry>) = e.filterIsInstance<HourNode>()

    @Test fun dstDaysHave23And25HourSlotsThatTileTheDay() {
        val ny = ZoneId.of("America/New_York")
        val spring = LocalDate.of(2026, 3, 8); val fall = LocalDate.of(2026, 11, 1)
        assertEquals(23, DayHourTree.slots(spring, ny).size); assertEquals(25, DayHourTree.slots(fall, ny).size); assertEquals(24, DayHourTree.slots(day, ny).size)
        for (d in listOf(spring, fall, day)) {
            val s = DayHourTree.slots(d, ny); val b = DayBucket.bounds(d, ny)
            assertEquals(b.first, s.first().startMs); assertEquals(b.last + 1, s.last().endMs)
            s.zipWithNext().forEach { (a, c) -> assertEquals("contiguous", a.endMs, c.startMs) }
        }
        // 01:00 happens twice on the 25-hour day: two distinct slots with the same label.
        assertEquals(2, DayHourTree.slots(fall, ny).count { it.label == "01:00" })
    }

    @Test fun hourCountsBucketByTheZonesOwnHoursNewestFirstAndSumToTheDay() {
        val times = listOf(at(9, 5), at(9, 59), at(10, 0), at(23, 59))
        val e = DayHourTree.hours(day, utc, times, emptyList())
        assertEquals(listOf("23:00", "10:00", "09:00"), nodes(e).map { it.label })
        assertEquals(listOf(1, 1, 2), nodes(e).map { it.count })
        assertEquals(times.size, nodes(e).sumOf { it.count })
        // The same instant in another zone lands on another day and hour (a zone change mid-session re-buckets).
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(setOf(day), DayHourTree.dayCounts(times, utc).keys)
        assertEquals(setOf(day, day.plusDays(1)), DayHourTree.dayCounts(times, tokyo).keys)
    }

    @Test fun consecutiveEmptyHoursFoldIntoOneNoActivityLine() {
        val e = DayHourTree.hours(day, utc, listOf(at(0, 10), at(7, 0)), emptyList())
        assertEquals(listOf("07:00", "00:00"), nodes(e).map { it.label })
        assertEquals(listOf(QuietRun::class, HourNode::class, QuietRun::class, HourNode::class).map { it.simpleName }, e.map { it::class.simpleName })
        assertEquals("No activity 08:00-24:00", (e[0] as QuietRun).label); assertEquals("No activity 01:00-07:00", (e[2] as QuietRun).label)
        assertEquals("a day with nothing is one run", 1, DayHourTree.hours(day, utc, emptyList(), emptyList()).size)
        assertEquals("No activity 00:00-24:00", (DayHourTree.hours(day, utc, emptyList(), emptyList()).single() as QuietRun).label)
    }

    @Test fun photosOfEarlierDaysGoToAnEarlierGroupAtTheEnd() {
        val e = DayHourTree.hours(day, utc, listOf(at(9), at(23, 0, day.minusDays(1)), at(22, 0, day.minusDays(3))), emptyList())
        val last = e.last() as HourNode
        assertTrue(last.earlier); assertEquals(2, last.count); assertEquals("Earlier photos", last.label); assertEquals(Long.MIN_VALUE, last.startMs)
        assertEquals(DayBucket.bounds(day, utc).first, last.endMs)
        assertEquals(3, nodes(e).sumOf { it.count })
    }

    @Test fun pauseChipsClipToTheHourNameNoZoneAndAPauseOnlyHourShowsTheChipWithoutACount() {
        val pauses = listOf(PauseSpan(at(14, 5), at(14, 40), privacyZone = false), PauseSpan(at(15, 30), at(16, 20), privacyZone = true), PauseSpan(at(20, 0), null, privacyZone = false))
        val e = nodes(DayHourTree.hours(day, utc, listOf(at(14, 10)), pauses)).associateBy { it.label }
        assertEquals(listOf("Paused 14:05-14:40"), e.getValue("14:00").pauses.map { it.label }); assertEquals(1, e.getValue("14:00").count)
        assertEquals("a pause only: chip, no count", listOf(0, listOf("Privacy zone")), listOf(e.getValue("15:00").count, e.getValue("15:00").pauses.map { it.label }))
        assertEquals(listOf("Privacy zone"), e.getValue("16:00").pauses.map { it.label })
        assertEquals("an open pause runs to the end of every later hour", listOf("Paused 20:00-21:00"), e.getValue("20:00").pauses.map { it.label })
        assertEquals(listOf("Paused 23:00-24:00"), e.getValue("23:00").pauses.map { it.label })
        assertTrue(e.values.flatMap { it.pauses }.none { "zone" in it.label.lowercase().replace("privacy zone", "") })
    }

    @Test fun dayTitlesAndCounts() {
        assertEquals("Today · 1 moment", DayHourTree.dayTitle(day, day, 1))
        assertEquals("Yesterday · 0 moments", DayHourTree.dayTitle(day.minusDays(1), day, 0))
        assertEquals("Tue 3 Nov · 143 moments", DayHourTree.dayTitle(LocalDate.of(2026, 11, 3), day, 143))
        assertEquals(mapOf(day to 2), DayHourTree.dayCounts(listOf(at(1), at(2)), utc))
    }
}
