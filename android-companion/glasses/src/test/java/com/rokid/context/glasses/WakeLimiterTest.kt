package com.rokid.context.glasses

import com.rokid.context.glasses.overlay.WakeLimiter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeLimiterTest {
    private var now = 1_000_000L
    private val l = WakeLimiter({ now })

    @Test fun switchOffNeverWakesAndDoesNotUseTheSlot() {
        assertFalse(l.allow(false))
        assertTrue(l.allow(true))
    }

    @Test fun oneWakePerInterval() {
        assertTrue(l.allow(true))
        now += WakeLimiter.MIN_INTERVAL_MS - 1
        assertFalse(l.allow(true))
        now += 1
        assertTrue(l.allow(true))
    }

    @Test fun refusedWakeDoesNotExtendTheInterval() {
        assertTrue(l.allow(true))
        now += 30_000; assertFalse(l.allow(true))
        now += 30_000; assertTrue(l.allow(true))
    }

    @Test fun clockGoingBackwardsAllows() {
        assertTrue(l.allow(true))
        now -= 5_000
        assertTrue(l.allow(true))
    }
}
