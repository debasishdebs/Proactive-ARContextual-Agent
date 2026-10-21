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
}
