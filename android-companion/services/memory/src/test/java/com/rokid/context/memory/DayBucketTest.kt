package com.rokid.context.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayBucketTest {
    @Test fun dstDayHas23Hours() {
        val r = DayBucket.bounds(LocalDate.of(2026, 3, 29), ZoneId.of("Europe/Berlin"))
        assertEquals(23L * 3_600_000, r.last + 1 - r.first)
    }

    @Test fun bucketUsesGivenZone() {
        val instant = java.time.Instant.parse("2026-10-12T20:00:00Z").toEpochMilli() // 01:30 next day in India
        assertEquals(LocalDate.of(2026, 10, 13), DayBucket.dayOf(instant, ZoneId.of("Asia/Kolkata")))
        assertEquals(LocalDate.of(2026, 10, 12), DayBucket.dayOf(instant, ZoneId.of("Europe/London")))
    }
}
