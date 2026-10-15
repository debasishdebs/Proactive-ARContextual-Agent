package com.rokid.context.work.frames

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyTrackerTest {
    @Test fun concurrentRecordsAreAllKept() {
        val t = LatencyTracker(max = 10_000)
        val threads = (1..8).map { Thread { repeat(500) { t.record(5) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals("atomic update: no lost sample", 4_000, t.samples.value.size)
    }

    @Test fun keepsOnlyTheLastMax() {
        val t = LatencyTracker(max = 3)
        (1L..5L).forEach(t::record)
        assertEquals(listOf(3L, 4L, 5L), t.samples.value)
    }

    @Test fun onlyFramesReceivedWithinTenMinutesOfCaptureAreRecorded() {
        val t = LatencyTracker()
        t.record(12_000L, receiptLagMs = 1_000L)                       // a live keyframe
        t.record(10 * 60_000L, receiptLagMs = LatencyTracker.MAX_RECEIPT_LAG_MS) // on the line: kept
        t.record(442 * 60_000L, receiptLagMs = 442 * 60_000L)          // a backfilled legacy frame
        t.record(5_000L, receiptLagMs = null)                          // adopted: no receipt time
        assertEquals(listOf(12_000L, 10 * 60_000L), t.samples.value)
    }
}
