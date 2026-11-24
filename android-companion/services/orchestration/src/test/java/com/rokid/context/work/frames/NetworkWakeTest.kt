package com.rokid.context.work.frames

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkWakeTest {
    private var now = 1_000_000L
    private var nudges = 0
    private val limits = mutableListOf<Int>()
    private fun wake(revived: Int = 3) = NetworkWake(revive = { _, limit -> limits += limit; revived }, nudge = { nudges++ }, clock = { now })

    @Test fun firstEventNudgesAndRevives() = runBlocking {
        val w = wake()
        assertEquals(3, w.onValidated())
        assertEquals(1, nudges); assertEquals(listOf(NetworkWake.BATCH), limits)
    }

    @Test fun reviveIsRateLimitedButNudgeIsNot() = runBlocking {
        val w = wake()
        w.onValidated()
        now += 60_000
        assertEquals(0, w.onValidated())
        assertEquals(2, nudges); assertEquals(1, limits.size)
        now += NetworkWake.MIN_INTERVAL_MS
        assertEquals(3, w.onValidated())
        assertEquals(2, limits.size)
    }

    @Test fun failingReviveOrNudgeDoesNotThrow() = runBlocking {
        val w = NetworkWake(revive = { _, _ -> error("db") }, nudge = { error("wm") }, clock = { now })
        assertEquals(0, w.onValidated())
    }
}
