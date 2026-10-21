package com.rokid.context.glasses.capture.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLogThrottleTest {
    @Test fun firstCallLogs() = assertTrue(StatusLogThrottle().shouldLog("k", "a", 0))

    @Test fun sameLineDoesNotLog() {
        val t = StatusLogThrottle()
        t.shouldLog("k", "a", 0)
        assertFalse(t.shouldLog("k", "a", 40))
    }

    @Test fun changedLogLineSameKeyWaitsThirtySeconds() {
        val t = StatusLogThrottle()
        t.shouldLog("k", "a", 0)
        assertFalse(t.shouldLog("k", "b", 5))
        assertTrue(t.shouldLog("k", "b", 30))
    }

    @Test fun keyChangeLogsImmediately() {
        val t = StatusLogThrottle()
        t.shouldLog("k", "a", 0)
        assertTrue(t.shouldLog("k2", "a", 1))
    }

    @Test fun forceLogs() {
        val t = StatusLogThrottle()
        t.shouldLog("k", "a", 0)
        assertTrue(t.shouldLog("k", "a", 1, force = true))
    }
}
