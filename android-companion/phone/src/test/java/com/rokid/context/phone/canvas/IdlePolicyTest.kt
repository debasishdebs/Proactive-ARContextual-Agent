package com.rokid.context.phone.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdlePolicyTest {
    private val min = 60_000L

    @Test fun stopsAfter30MinIdle() {
        assertFalse(IdlePolicy.shouldStop(0, 30 * min - 1, runActive = false, screenOpen = false))
        assertTrue(IdlePolicy.shouldStop(0, 30 * min, runActive = false, screenOpen = false))
        assertTrue(IdlePolicy.shouldStop(1_000, 1_000 + 31 * min, runActive = false, screenOpen = false))
    }

    @Test fun neverDuringRun() {
        assertFalse(IdlePolicy.shouldStop(0, 5 * 60 * min, runActive = true, screenOpen = false))
    }

    @Test fun neverWhileScreenOpen() {
        assertFalse(IdlePolicy.shouldStop(0, 5 * 60 * min, runActive = false, screenOpen = true))
    }
}
