package com.rokid.context.phone.hub

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateBroadcasterTest {
    /** Spec §1.6: ≤ 4 Hz even when state changes 20 times a second. */
    @Test fun neverSendsMoreThanFourTimesASecond() = runTest {
        val sentAt = mutableListOf<Long>()
        val b = StateBroadcaster(backgroundScope, heartbeatMs = 60_000) { sentAt += testScheduler.currentTime }
        b.start()
        repeat(40) { b.markDirty(); delay(50) }
        advanceTimeBy(1_000)
        assertTrue("$sentAt", sentAt.zipWithNext { a, c -> c - a }.all { it >= 250 })
        assertTrue("$sentAt", sentAt.count { it < 1_000 } <= 4)
    }

    @Test fun theLatestChangeIsAlwaysSent() = runTest {
        var value = 0
        var sentValue = -1
        val b = StateBroadcaster(backgroundScope, heartbeatMs = 60_000) { sentValue = value }
        b.start(); runCurrent()
        repeat(10) { value = it + 1; b.markDirty(); delay(10) }
        advanceTimeBy(500)
        assertEquals(10, sentValue)
    }

    @Test fun heartbeatSendsWithoutChanges() = runTest {
        var sends = 0
        val b = StateBroadcaster(backgroundScope, heartbeatMs = 10_000) { sends++ }
        b.start()
        advanceTimeBy(35_000) // at 10 000, 20 250, 30 500
        assertEquals(3, sends)
    }

    @Test fun aFailingSendDoesNotStopTheLoop() = runTest {
        var calls = 0
        val errors = mutableListOf<Throwable>()
        val b = StateBroadcaster(backgroundScope, heartbeatMs = 60_000, onError = { errors += it }) { calls++; if (calls == 1) error("link down") }
        b.start()
        b.markDirty(); runCurrent()
        advanceTimeBy(300)
        b.markDirty(); advanceTimeBy(300)
        assertEquals(2, calls)
        assertEquals(1, errors.size)
    }

    /** Phase 8 C5: an error handler that throws must not end the loop (the HUD would freeze). */
    @Test fun aThrowingOnErrorDoesNotStopBroadcasting() = runTest {
        var calls = 0
        val logs = mutableListOf<String>()
        val b = StateBroadcaster(
            this, heartbeatMs = 60_000, onError = { throw IllegalStateException("handler") }, log = { logs += it },
        ) { calls++; if (calls == 1) error("link down") }
        val job = b.start()
        b.markDirty(); runCurrent()
        advanceTimeBy(300)
        b.markDirty(); advanceTimeBy(300)
        assertEquals(2, calls)
        assertEquals(1, logs.size)
        assertTrue(logs.single(), "IllegalStateException" in logs.single())
        assertFalse("handler" in logs.single())
        job.cancel()
    }

    /** Cancelling mid-send ends the loop and is not reported as a send error. */
    @Test fun cancellationStillStopsTheLoop() = runTest {
        var calls = 0
        val errors = mutableListOf<Throwable>()
        val b = StateBroadcaster(this, heartbeatMs = 60_000, onError = { errors += it }) { calls++; delay(100) }
        val job = b.start()
        b.markDirty(); runCurrent()
        job.cancel(); runCurrent()
        b.markDirty(); advanceTimeBy(1_000)
        assertEquals(1, calls)
        assertEquals(emptyList<Throwable>(), errors)
        assertTrue(job.isCancelled)
    }
}
