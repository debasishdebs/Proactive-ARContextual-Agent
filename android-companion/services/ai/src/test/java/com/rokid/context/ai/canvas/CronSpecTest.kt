package com.rokid.context.ai.canvas

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CronSpecTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun spec(s: String) = CronSpec.parse(s)!!

    @Test fun nextOccurrenceIsStrictlyAfter() {
        val daily = spec("0 9 * * *")
        assertEquals(ms(2026, 10, 28, 9, 0), daily.nextAfter(ms(2026, 10, 28, 8, 59), zone))
        assertEquals(ms(2026, 10, 29, 9, 0), daily.nextAfter(ms(2026, 10, 28, 9, 0), zone))
    }

    @Test fun listsRangesStepsAndWeekdays() {
        // 2026-10-28 is a Wednesday; weekdays only, 08:30 and 17:30
        val s = spec("30 8,17 * * 1-5")
        assertEquals(ms(2026, 10, 28, 17, 30), s.nextAfter(ms(2026, 10, 28, 9, 0), zone))
        assertEquals(ms(2026, 10, 30, 8, 30), s.nextAfter(ms(2026, 10, 29, 17, 30), zone)) // Thursday 17:30 -> Friday
        assertEquals(ms(2026, 11, 2, 8, 30), s.nextAfter(ms(2026, 10, 30, 17, 30), zone)) // Friday -> Monday
        assertEquals(ms(2026, 10, 28, 10, 15), spec("15,45 */5 * * *").nextAfter(ms(2026, 10, 28, 10, 0), zone))
        assertEquals(spec("0 9 * * 0").nextAfter(1L, zone), spec("0 9 * * 7").nextAfter(1L, zone)) // 0 and 7 are both Sunday
    }

    @Test fun theFloorIsFifteenMinutesAcrossMidnightToo() {
        assertNotNull(CronSpec.parse("*/15 * * * *"))
        assertNull(CronSpec.parse("*/14 * * * *"))
        assertNull(CronSpec.parse("0,5 0 * * *"))
        assertNotNull(CronSpec.parse("0 23,0 * * *")) // 23:00 and 00:00 are an hour apart
        assertNotNull(CronSpec.parse("55 23 * * *")) // one a day
        assertNull(CronSpec.parse("55,59 23 * * *")) // 4 minutes apart
    }

    @Test fun badExpressionsAreRefused() {
        for (bad in listOf("", "* * * *", "* * * * * *", "60 * * * *", "0 24 * * *", "0 9 0 * *", "0 9 * 13 *", "0 9 * * 8", "a b c d e", "5-2 9 * * *", "0 9 */0 * *", "0 9 * * * " + "x".repeat(70)))
            assertNull(bad, CronSpec.parse(bad))
    }

    @Test fun daylightSavingGapIsSkippedAndInstantsAreOrdered() {
        // Lord Howe springs forward 2026-10-04 02:00 -> 02:30: 02:00 and 02:20 do not exist, 02:40 does
        val lh = ZoneId.of("Australia/Lord_Howe")
        fun lms(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(lh).toInstant().toEpochMilli()
        val s = spec("0,20,40 2 * * *")
        assertEquals(lms(4, 2, 40), s.nextAfter(lms(4, 1, 0), lh))
        assertEquals(lms(5, 2, 0), s.nextAfter(lms(4, 2, 40), lh))
        // US fall back 2026-11-01: 01:30 happens twice and runs once, at its first instant
        val ny = ZoneId.of("America/New_York")
        val first = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(ny).toInstant().toEpochMilli()
        val daily = spec("30 1 * * *")
        assertEquals(first, daily.nextAfter(first - 3_600_000L, ny))
        assertEquals(LocalDateTime.of(2026, 11, 2, 1, 30).atZone(ny).toInstant().toEpochMilli(), daily.nextAfter(first, ny)) // not again an hour later
        // US spring forward 2026-03-08: 02:30 does not exist that day (skipped), back on the 9th
        val gap = spec("30 2 * * *")
        assertEquals(LocalDateTime.of(2026, 3, 9, 2, 30).atZone(ny).toInstant().toEpochMilli(), gap.nextAfter(LocalDateTime.of(2026, 3, 8, 0, 0).atZone(ny).toInstant().toEpochMilli(), ny))
    }

    @Test fun onlyDatesThatOccurEveryYearAreAllowed() {
        assertNull(CronSpec.parse("0 9 29 2 *")) // leap day only
        assertNull(CronSpec.parse("0 9 31 4 *")) // never
        assertNull(CronSpec.parse("0 9 31 2 *"))
        assertNull(CronSpec.parse("0,50 0,23 28,29 2 *")) // also the leap-day boundary is a ten minute gap
        assertNotNull(CronSpec.parse("0 9 28 2 *"))
        assertNotNull(CronSpec.parse("0 9 31 * *"))
    }
}
