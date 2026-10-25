package com.rokid.context.core.model.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchMotionRingTest {
    private fun s(at: Long, st: WatchState = WatchState.WALKING, c: Float = 1f) = WatchSample(at, st, c)

    @Test fun scoreIsTheStateLevelTimesConfidence() {
        assertEquals(0.5, WatchMotionScore.of(WatchState.WALKING, 1f)!!, 1e-9)
        assertEquals(0.4, WatchMotionScore.of(WatchState.WALKING, 0.8f)!!, 1e-6)
        assertEquals(1.0, WatchMotionScore.of(WatchState.RUNNING, 1f)!!, 1e-9)
        assertEquals(1.0, WatchMotionScore.of(WatchState.RUNNING, 7f)!!, 1e-9) // clamped
    }

    @Test fun aStillWatchIsARealZeroButUnknownHasNoScore() {
        assertEquals(0.0, WatchMotionScore.of(WatchState.STILL, 0.9f)!!, 0.0)
        assertEquals(0.0, WatchMotionScore.of(WatchState.ASLEEP, 0.9f)!!, 0.0)
        assertNull(WatchMotionScore.of(WatchState.UNKNOWN, 1f))
        assertNull(WatchMotionScore.of(WatchState.WALKING, Float.NaN))
        assertNull(s(1, WatchState.UNKNOWN).score)
    }

    @Test fun recentReturnsTheWindowInArrivalOrder() {
        val r = WatchMotionRing()
        listOf(s(100), s(200), s(300), s(400)).forEach(r::add)
        assertEquals(listOf(200L, 300L), r.recent(200, 300).map { it.atMs })
        assertEquals(emptyList<WatchSample>(), r.recent(500, 900))
    }

    @Test fun theOldestDropsWhenFull() {
        val r = WatchMotionRing(3)
        (1L..5L).forEach { r.add(s(it)) }
        assertEquals(listOf(3L, 4L, 5L), r.recent(0, 10).map { it.atMs })
    }

    @Test fun clearForgetsEverything() {
        val r = WatchMotionRing(); r.add(s(1)); r.clear()
        assertEquals(emptyList<WatchSample>(), r.recent(0, 10))
    }
}
