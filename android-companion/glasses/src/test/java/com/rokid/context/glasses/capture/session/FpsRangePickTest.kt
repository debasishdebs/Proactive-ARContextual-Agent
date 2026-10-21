package com.rokid.context.glasses.capture.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsRangePickTest {
    @Test fun lowestLastThenWidest() = assertEquals(7..15, FpsRangePick.pick(listOf(15..30, 30..30, 7..15, 15..15)))
    @Test fun noneUnderCap() = assertNull(FpsRangePick.pick(listOf(30..30, 24..30)))
    @Test fun neverBelowFiveFps() = assertEquals(5..10, FpsRangePick.pick(listOf(3..3, 5..10)))
    @Test fun sameLastWidestWins() = assertEquals(5..15, FpsRangePick.pick(listOf(10..15, 5..15)))
    @Test fun emptyIsNull() = assertNull(FpsRangePick.pick(emptyList()))

    /** A23: a failed or empty read is not cached; the next call reads again, and a good read is cached. */
    @Test fun failedReadNotCached() {
        var reads = 0
        val results = mutableListOf<List<IntRange>?>(null, emptyList(), listOf(7..15))
        val cache = FpsRangePick.Cache { reads++; results.removeAt(0) }
        assertNull(cache.get()); assertNull(cache.get())
        assertEquals(7..15, cache.get())
        assertEquals(7..15, cache.get())
        assertEquals(3, reads)
    }

    @Test fun noRangeUnderCapIsCachedAsDefault() {
        var reads = 0
        val cache = FpsRangePick.Cache { reads++; listOf(30..30) }
        assertNull(cache.get()); assertNull(cache.get())
        assertEquals(1, reads)
    }
}
