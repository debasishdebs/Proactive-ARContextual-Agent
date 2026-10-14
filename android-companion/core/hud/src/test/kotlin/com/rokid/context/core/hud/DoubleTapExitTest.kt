package com.rokid.context.core.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 2026-10-14 §2: a double-tap arms a 3 s exit gate; a second one minimises, expiry toggles pause once. */
class DoubleTapExitTest {
    private val w = DoubleTapExit.WINDOW_MS

    @Test fun twoDoubleTapsInWindowMinimiseNoPause() {
        val (armed, first) = DoubleTapExit.onDoubleTap(ExitGate.Idle, 1_000)
        assertNull(first)
        assertEquals(ExitGate.Armed(1_000 + w), armed)
        val (after, second) = DoubleTapExit.onDoubleTap(armed, 1_000 + w - 1)
        assertEquals(PageAction.MINIMISE, second)
        assertEquals(ExitGate.Idle, after)
        assertNull(DoubleTapExit.onTick(after, 1_000 + w + 5_000).second, "no pause after a minimise")
    }

    @Test fun singleDoubleTapThenExpiryTogglesPauseOnce() {
        val (armed, _) = DoubleTapExit.onDoubleTap(ExitGate.Idle, 0)
        assertNull(DoubleTapExit.onTick(armed, w - 1).second, "still inside the window")
        val (idle, action) = DoubleTapExit.onTick(armed, w)
        assertEquals(PageAction.TOGGLE_PAUSE, action)
        assertEquals(ExitGate.Idle, idle)
        assertNull(DoubleTapExit.onTick(idle, w + 1_000).second, "exactly once")
        // A double-tap after the window is a fresh arm, not a minimise.
        val (rearmed, late) = DoubleTapExit.onDoubleTap(armed, w + 10)
        assertNull(late)
        assertEquals(ExitGate.Armed(w + 10 + w), rearmed)
    }

    @Test fun tapOrSwipeWhileArmedCancelsNoPause() {
        val (armed, _) = DoubleTapExit.onDoubleTap(ExitGate.Idle, 0)
        val cancelled = DoubleTapExit.onOtherInput(armed)
        assertEquals(ExitGate.Idle, cancelled)
        assertNull(DoubleTapExit.onTick(cancelled, w * 2).second)
    }

    @Test fun minimiseClearsGateNoLatePause() {
        val (armed, _) = DoubleTapExit.onDoubleTap(ExitGate.Idle, 0)
        val (gate, a) = DoubleTapExit.onDoubleTap(armed, 500)
        assertEquals(PageAction.MINIMISE, a)
        // Returning to the HUD later: every tick from here is silent.
        for (t in listOf(w, w + 1, 60_000L)) assertNull(DoubleTapExit.onTick(gate, t).second)
    }

    @Test fun countdownShowsWholeSeconds() {
        val armed = ExitGate.Armed(3_000)
        assertEquals("Double-tap again to exit \u00b7 3", DoubleTapExit.countdownText(armed, 0))
        assertEquals("Double-tap again to exit \u00b7 3", DoubleTapExit.countdownText(armed, 1))
        assertEquals("Double-tap again to exit \u00b7 1", DoubleTapExit.countdownText(armed, 2_500))
        assertNull(DoubleTapExit.countdownText(ExitGate.Idle, 0))
    }
}
