package com.rokid.context.phone.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.0 wear fallback, phone side: the glasses' estimate counts only while the system wear check is not on, and only on a live session. */
class GlassesWearEstimateTest {
    private val lines = mutableListOf<String>()
    private val e = GlassesWearEstimate(log = { lines += it }).apply { onLink(true) }

    @Test fun theEstimateCountsWithTheWearCheckOff() {
        e.onWearCheck(false); e.onWear(true) // the pinned "worn"
        e.onHint(true); assertTrue(e.active.value)
        assertEquals(listOf("glasses: not worn (estimated)"), lines)
        e.onHint(false); assertFalse(e.active.value)
        assertEquals("glasses: worn again (estimate ended)", lines.last())
    }

    @Test fun theRealSignalWins() {
        e.onWearCheck(true); e.onHint(true); assertFalse("wear check on", e.active.value)
        e.onWearCheck(null); e.onWear(true); assertFalse("a wear value with the check state unknown", e.active.value)
        e.onWear(null); assertFalse("an unknown wear check never estimates (Codex wear r1 P1-4)", e.active.value)
        e.onWearCheck(false); assertTrue("only a wear check read as OFF lets the estimate through", e.active.value)
        e.onWearCheck(true); assertFalse(e.active.value)
    }

    @Test fun anOldGlassesBuildNeverEstimates() {
        e.onWearCheck(false); e.onHint(null); assertFalse(e.active.value)
    }

    @Test fun aLostSessionDropsTheEstimate() {
        e.onWearCheck(false); e.onHint(true); assertTrue(e.active.value)
        e.onLink(false); assertFalse(e.active.value)
        e.onLink(true); assertFalse("a new session must report it again", e.active.value)
        e.onHint(true); assertTrue(e.active.value)
    }

    // ---- Codex wear r2 P1-3: freshness follows a monotonic clock and an early timer is re-armed ----

    @Test fun anEarlyTimerRearmsForTheRestAndTheEstimateStillExpires() {
        var now = 1_000L; val armed = mutableListOf<Long>()
        val x = GlassesWearEstimate(clock = { now }, scheduleExpiry = { armed += it }).apply { onLink(true); onWearCheck(false) }
        x.onHint(true); assertTrue(x.active.value); assertEquals(listOf(com.rokid.context.core.model.WearEstimate.FRESH_MS), armed)
        now += 100_000L; x.tick() // fires early (the deadline is 80 s away)
        assertTrue(x.active.value); assertEquals(com.rokid.context.core.model.WearEstimate.FRESH_MS - 100_000L, armed.last())
        now += 80_000L; x.tick(); assertFalse("expired at the deadline", x.active.value); assertEquals("nothing is re-armed once it ended", 2, armed.size)
    }

    @Test fun theDefaultClockIsMonotonic() {
        val a = GlassesWearEstimate(); a.onLink(true); a.onWearCheck(false); a.onHint(true)
        assertTrue(a.active.value) // System.nanoTime based: a wall clock moved back an hour cannot strand it past FRESH_MS (the injected-clock test above covers the deadline)
    }
}
