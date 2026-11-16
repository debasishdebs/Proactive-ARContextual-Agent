package com.rokid.context.glasses.overlay

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PlaceCardInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Codex r4: a place pop-up that is already visible while minimised is withdrawn on every ground the HUD card is, not only for an Answer. */
class PlacePopupTrackerTest {
    private val card = PlaceCardInfo("Charminar", "A monument.", "Wikipedia", id = "pc1")
    private val nudge = Overlay.Nudge("Charminar: A monument.", card)
    private fun shown() = PlacePopupTracker().also { it.show(card, "Charminar\nA monument.\nWikipedia", 1_000) }

    @Test fun theSameCardRepeatedByAHeartbeatStays() = assertNull(shown().overlayChanged(nudge))

    @Test fun overlayNullWithdrawsIt() { val t = shown(); assertEquals("Charminar\nA monument.\nWikipedia", t.overlayChanged(null)?.text); assertNull(t.current()) }

    @Test fun anyOtherOverlayOrAnotherCardWithdrawsIt() {
        assertNotNull(shown().overlayChanged(Overlay.Toast("x")))
        assertNotNull(shown().overlayChanged(Overlay.Nudge("a: b", card.copy(id = "pc2"))))
    }

    @Test fun aZoneActivatingWithdrawsItAndAClearZoneDoesNot() {
        assertNull(shown().zoneActive(false)); assertEquals(card, shown().zoneActive(true)?.card)
    }

    @Test fun forgetAndDeleteAllLookLikeOverlayNullAndWithdrawIt() { val t = shown(); assertEquals(card, t.overlayChanged(null)?.card); assertNull(t.overlayChanged(null)) }

    @Test fun motionOnsetAfterTwoAndAHalfSecondsDismissesLikeThePresenter() {
        val t = shown(); t.onMotion(MotionState.STILL, 1_100)
        assertEquals(card, t.onMotion(MotionState.HEAD_MOVING, 3_600)?.card)
    }

    @Test fun motionOnsetBeforeTwoAndAHalfSecondsDoesNot() {
        val t = shown(); t.onMotion(MotionState.STILL, 1_100)
        assertNull(t.onMotion(MotionState.HEAD_MOVING, 3_499)); assertNotNull(t.current())
        assertNull("still moving is not a new onset", t.onMotion(MotionState.HEAD_MOVING, 9_000)); assertNotNull(t.current())
    }

    @Test fun nothingShownMeansNothingToWithdraw() {
        val t = PlacePopupTracker()
        assertNull(t.overlayChanged(null)); assertNull(t.zoneActive(true)); assertNull(t.onMotion(MotionState.HEAD_MOVING, 5_000))
    }

    @Test fun aPopupThatEndedOnItsOwnIsForgotten() { val t = shown(); t.gone(); assertNull(t.overlayChanged(null)) }
}
