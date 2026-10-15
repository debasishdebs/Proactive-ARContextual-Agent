package com.rokid.context.core.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 2026-10-14 §2: a double-tap arms a 3 s exit gate; a second one minimises, expiry toggles pause once. */
class DoubleTapExitTest {
    private val w = DoubleTapExit.WINDOW_MS

    @Test fun secondDoubleTapStartsMinimisingThenWindowEndMinimisesNoPause() {
        val (armed, first) = DoubleTapExit.onDoubleTap(ExitGate.Idle, 1_000)
        assertNull(first)
        assertEquals(ExitGate.Armed(1_000 + w), armed)
        val (mini, second) = DoubleTapExit.onDoubleTap(armed, 1_000 + w - 1)
        assertNull(second)
        val m = DoubleTapExit.MINIMISE_WINDOW_MS
        assertEquals(ExitGate.Minimising(1_000 + w - 1 + m), mini)
        assertNull(DoubleTapExit.onTick(mini, 1_000 + w - 2 + m).second, "still inside the exit window")
        val (after, action) = DoubleTapExit.onTick(mini, 1_000 + w - 1 + m)
        assertEquals(PageAction.MINIMISE, action); assertEquals(ExitGate.Idle, after)
        assertNull(DoubleTapExit.onTick(after, 100_000).second, "no pause after a minimise")
    }

    @Test fun thirdDoubleTapInTheWindowExits() {
        val (armed, _) = DoubleTapExit.onDoubleTap(ExitGate.Idle, 0)
        val (mini, _) = DoubleTapExit.onDoubleTap(armed, 500)
        val (after, action) = DoubleTapExit.onDoubleTap(mini, 500 + DoubleTapExit.MINIMISE_WINDOW_MS - 1)
        assertEquals(PageAction.EXIT, action); assertEquals(ExitGate.Idle, after)
        assertNull(DoubleTapExit.onTick(after, 60_000).second, "an exit leaves no late minimise or pause")
        // too late: a fresh arm instead
        val (late, a2) = DoubleTapExit.onDoubleTap(mini, 500 + DoubleTapExit.MINIMISE_WINDOW_MS)
        assertNull(a2); assertEquals(ExitGate.Armed(500 + DoubleTapExit.MINIMISE_WINDOW_MS + w), late)
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

    @Test fun cancelWhileMinimisingNeverMinimises() {
        val (armed, _) = DoubleTapExit.onDoubleTap(ExitGate.Idle, 0)
        val (mini, _) = DoubleTapExit.onDoubleTap(armed, 500)
        val cancelled = DoubleTapExit.onOtherInput(mini)
        for (t in listOf(w, 60_000L)) assertNull(DoubleTapExit.onTick(cancelled, t).second)
    }

    @Test fun textsFollowTheGate() {
        assertEquals("Double-tap again to minimise", DoubleTapExit.countdownText(ExitGate.Armed(3_000), 0))
        assertEquals("Minimising... double-tap again to EXIT", DoubleTapExit.countdownText(ExitGate.Minimising(1_500), 0))
        assertNull(DoubleTapExit.countdownText(ExitGate.Idle, 0))
    }
}
