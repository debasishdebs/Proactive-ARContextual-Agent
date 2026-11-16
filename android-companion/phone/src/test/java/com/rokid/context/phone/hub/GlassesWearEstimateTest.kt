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
}
