package com.rokid.context.core.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoubleTapDetectorTest {
    @Test fun twoNotificationKeysCloseTogetherAreADoubleTap() {
        val d = DoubleTapDetector()
        assertFalse(d.onNotificationKey(1_000), "one key alone is not a double-tap")
        assertTrue(d.onNotificationKey(1_150))
    }

    @Test fun keysFurtherApartThanTheWindowAreNot() {
        val d = DoubleTapDetector()
        assertFalse(d.onNotificationKey(1_000))
        assertFalse(d.onNotificationKey(1_000 + DoubleTapDetector.WINDOW_MS + 1))
        assertTrue(d.onNotificationKey(1_000 + DoubleTapDetector.WINDOW_MS + 100), "the late key starts a new pair")
    }

    @Test fun backIsADoubleTapOnItsOwn() = assertTrue(DoubleTapDetector().onBack(0))

    @Test fun oneGestureReportedTwiceFiresOnce() {
        val d = DoubleTapDetector()
        d.onNotificationKey(1_000); assertTrue(d.onNotificationKey(1_150))
        assertFalse(d.onBack(1_300), "firmware that also sends BACK for the same double-tap")
        assertFalse(d.onNotificationKey(1_400)); assertFalse(d.onNotificationKey(1_450))
        assertTrue(d.onBack(1_150 + DoubleTapDetector.SETTLE_MS + 500), "a later double-tap counts")
    }

    @Test fun backThenNotificationKeysFireOnce() {
        val d = DoubleTapDetector()
        assertTrue(d.onBack(1_000))
        assertFalse(d.onNotificationKey(1_100)); assertFalse(d.onNotificationKey(1_250))
    }

    @Test fun aSwipeOrTapBetweenTwoTouchesIsNotADoubleTap() {
        // Every touchpad gesture starts with key 83: a quick swipe then a touch must not close the app.
        val d = DoubleTapDetector()
        assertFalse(d.onNotificationKey(0)); d.onGestureKey()
        assertFalse(d.onNotificationKey(200), "the swipe's touch then the next touch")
        assertTrue(d.onNotificationKey(350), "a real double-tap right after still counts")
    }

    @Test fun aThirdKeyDoesNotPairWithTheSecond() {
        val d = DoubleTapDetector()
        d.onNotificationKey(0); assertTrue(d.onNotificationKey(150))
        assertFalse(d.onNotificationKey(DoubleTapDetector.SETTLE_MS + 200), "pair consumed; a new first key")
    }

    /** Feeds 83 presses through the detector and the exit gate as MainActivity does; returns the gate actions. */
    private fun fourTap(vararg atMs: Long): List<PageAction?> {
        val d = DoubleTapDetector()
        var gate: ExitGate = ExitGate.Idle
        val out = mutableListOf<PageAction?>()
        for (t in atMs) {
            if (d.onNotificationKey(t, armed = gate is ExitGate.Armed)) {
                val (g, a) = DoubleTapExit.onDoubleTap(gate, t); gate = g; out += a
            }
        }
        return out
    }

    @Test fun fastFourTapWithin1sMinimises() =
        assertEquals(listOf(null, PageAction.MINIMISE), fourTap(0, 120, 300, 420))

    @Test fun fourTapWithNormalGapMinimises() =
        assertEquals(listOf(null, PageAction.MINIMISE), fourTap(0, 150, 500, 650))

    @Test fun tripleTapOutsideGateStillOneDoubleTap() {
        // Not armed: the third press is the pair's tail, so it cannot pair with a later lone touch.
        val d = DoubleTapDetector()
        val fired = listOf(0L, 150L, 300L, 600L).count { d.onNotificationKey(it) }
        assertEquals(1, fired)
    }

    @Test fun armedStillDropsTheOtherKindTwin() {
        // Firmware sending both the 83 pair and a BACK: the BACK twin must not minimise while armed.
        val d = DoubleTapDetector()
        d.onNotificationKey(0); assertTrue(d.onNotificationKey(150))
        assertFalse(d.onBack(300, armed = true))
        val b = DoubleTapDetector()
        assertTrue(b.onBack(0))
        assertFalse(b.onNotificationKey(100, armed = true)); assertFalse(b.onNotificationKey(250, armed = true))
        assertTrue(b.onBack(400, armed = true), "BACK-only firmware: a fast second double-tap counts while armed")
    }
}

