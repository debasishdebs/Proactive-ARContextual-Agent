package com.rokid.context.phone.ui.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** The range maths of the list's date and time filter, in a fixed zone (UTC+5:30 has no daylight saving). */
class RangeFilterTest {
    private val zone = ZoneOffset.ofHoursMinutes(5, 30)
    private val d = LocalDate.of(2026, 11, 5)
    private fun at(day: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(day, LocalTime.of(h, m))
    private fun ms(day: LocalDate, h: Int, m: Int = 0, s: Int = 0, n: Int = 0) = LocalDateTime.of(day, LocalTime.of(h, m, s, n)).atZone(zone).toInstant().toEpochMilli()
    private fun range(a: LocalDateTime, b: LocalDateTime) = RangeFilter.millis(RangeFilter.of(a, b)!!, zone)

    @Test fun theEndMinuteIsIncludedToItsLastMillisecond() {
        val r = range(at(d, 9), at(d, 17, 30))
        assertTrue(ms(d, 17, 30) in r); assertTrue(ms(d, 17, 30, 59, 999_000_000) in r); assertFalse(ms(d, 17, 31) in r)
    }

    @Test fun theStartMinuteIsIncludedAndTheMinuteBeforeIsNot() {
        val r = range(at(d, 9), at(d, 17))
        assertTrue(ms(d, 9) in r); assertFalse(ms(d, 8, 59, 59, 999_000_000) in r)
    }

    @Test fun dayAndHourBoundariesAreExact() {
        val r = range(at(d, 0), at(d, 23, 59))
        assertTrue(ms(d, 0) in r); assertTrue(ms(d, 23, 59, 59, 999_000_000) in r)
        assertFalse(ms(d.plusDays(1), 0) in r); assertFalse(ms(d.minusDays(1), 23, 59, 59, 999_000_000) in r)
        val hour = range(at(d, 14), at(d, 14, 59))
        assertEquals(60 * 60 * 1000L, hour.last - hour.first + 1)
    }

    @Test fun aRangeAcrossDaysCoversTheMiddleDaysWhole() {
        val r = range(at(d, 22), at(d.plusDays(2), 1))
        assertTrue(ms(d.plusDays(1), 12) in r); assertFalse(ms(d, 21, 59) in r); assertFalse(ms(d.plusDays(2), 1, 1) in r)
    }

    @Test fun oneMinuteRangeHoldsExactlyThatMinute() {
        val r = range(at(d, 9, 5), at(d, 9, 5))
        assertEquals(60_000L, r.last - r.first + 1)
    }

    @Test fun aStartAfterTheEndIsRefused() {
        assertNull(RangeFilter.of(at(d, 10), at(d, 9)))
        assertNull(RangeFilter.of(at(d.plusDays(1), 0), at(d, 23)))
        assertNotNull(RangeFilter.of(at(d, 9), at(d, 9)))
    }

    @Test fun secondsAreCutOff() {
        val r = RangeFilter.of(d.atTime(9, 0, 42), d.atTime(9, 30, 17))!!
        assertEquals(at(d, 9), r.start); assertEquals(at(d, 9, 30), r.end)
    }

    @Test fun narrowingKeepsThePeriodWhenThereIsNoFilterAndEmptiesWhenTheyDoNotMeet() {
        val period = 1_000L..2_999L
        assertEquals(period, RangeFilter.narrow(period, null))
        assertEquals(1_500L..2_000L, RangeFilter.narrow(period, 1_500L..2_000L))
        assertEquals(2_000L..2_999L, RangeFilter.narrow(period, 2_000L..9_000L))
        assertEquals(1_000L..1_000L, RangeFilter.narrow(period, 0L..1_000L))
        assertTrue(RangeFilter.narrow(period, 3_000L..4_000L).isEmpty())
        assertTrue(RangeFilter.narrow(period, 0L..999L).isEmpty())
    }

    @Test fun timesAreParsedStrictly() {
        assertEquals(LocalTime.of(9, 5), RangeFilter.parseTime("9:05")); assertEquals(LocalTime.of(23, 59), RangeFilter.parseTime(" 23:59 "))
        assertEquals(LocalTime.MIN, RangeFilter.parseTime("00:00"))
        for (bad in listOf("", "24:00", "9:60", "9", "09-30", "ab:cd", "9:5")) assertNull(bad, RangeFilter.parseTime(bad))
        assertEquals("09:05", RangeFilter.formatTime(LocalTime.of(9, 5)))
    }

    @Test fun theSavedFormReadsBackAndDamagedOnesAreIgnored() {
        val r = RangeFilter.of(at(d, 9), at(d.plusDays(1), 17, 30))!!
        assertEquals(r, RangeFilter.decode(RangeFilter.encode(r)))
        assertNull(RangeFilter.decode(null)); assertNull(RangeFilter.decode("")); assertNull(RangeFilter.decode("x|y")); assertNull(RangeFilter.decode(RangeFilter.encode(r) + "|z"))
        assertNull(RangeFilter.decode("${at(d, 10)}|${at(d, 9)}"))
    }

    @Test fun theLabelIsPlainWords() {
        val label = RangeFilter.label(RangeFilter.of(at(d, 9), at(d.plusDays(2), 17, 30))!!)
        assertEquals("5 Nov 09:00 to 7 Nov 17:30", label); assertFalse(label.contains('—'))
    }
}
