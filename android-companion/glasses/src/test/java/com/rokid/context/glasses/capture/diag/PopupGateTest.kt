package com.rokid.context.glasses.capture.diag

import com.rokid.context.core.model.Overlay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGateTest {
    @Test fun nudgeIsHeldBackWhileTheScreenIsOff() = assertFalse(PopupGate.show(Overlay.Nudge("x"), interactive = false))
    @Test fun nudgeShowsWhileTheScreenIsOn() = assertTrue(PopupGate.show(Overlay.Nudge("x"), interactive = true))
    @Test fun otherOverlaysAlwaysShow() {
        assertTrue(PopupGate.show(Overlay.Answer("q", "a", false), interactive = false))
        assertTrue(PopupGate.show(Overlay.Toast("t"), interactive = false))
        assertTrue(PopupGate.show(Overlay.Confirm("p", "t", "d"), interactive = false))
    }

    @Test fun nudgeNeedsNudgesAllowedToo() {
        assertFalse(PopupGate.show(Overlay.Nudge("x"), interactive = true, nudgesAllowed = false))
        assertTrue(PopupGate.show(Overlay.Nudge("x"), interactive = true, nudgesAllowed = true))
        assertTrue(PopupGate.show(Overlay.Answer("q", "a", false), interactive = true, nudgesAllowed = false))
        assertTrue(PopupGate.show(Overlay.Answer("q", "a", false), interactive = false, nudgesAllowed = false))
    }
}
