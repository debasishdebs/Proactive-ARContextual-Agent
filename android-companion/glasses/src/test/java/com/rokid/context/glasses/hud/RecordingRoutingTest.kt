package com.rokid.context.glasses.hud

import com.rokid.context.core.hud.render.HudNav
import com.rokid.context.core.hud.render.HudPresenter
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.GestureDefaults
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Final review I1/I2: what a pad input does while the glasses record, routed on what the presenter shows (as MainActivity.handle does). */
class RecordingRoutingTest {
    private val confirm = Overlay.Confirm("p1", "Add milk?", "Shopping list")
    private fun frame(o: Overlay?) = StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok, overlay = o)
    private fun route(p: HudPresenter, o: Overlay?, input: PadInput, nowMs: Long, recording: Boolean): Routed =
        InputRouter.route(input, p.present(frame(o), Verbosity.MAX, HudNav(), null, nowMs, recording).overlay, GestureDefaults.map, recording)

    @Test fun aTapWhileRecordingSendsTheQuestionNeverVotesWhenTheConfirmArrivesDuringIt() {
        val p = HudPresenter()
        assertEquals(Routed.FinishListening, route(p, null, PadInput.Tapped, 0, recording = true))
        assertEquals(Routed.FinishListening, route(p, confirm, PadInput.Tapped, 1_000, recording = true))
        assertEquals(Routed.ConfirmChoose, route(p, confirm, PadInput.Tapped, 2_000, recording = false), "votes once it is on screen")
    }

    @Test fun aTapWhileRecordingSendsTheQuestionNeverVotesWhenTheConfirmWasShownFirst() {
        val p = HudPresenter()
        assertEquals(Routed.ConfirmToggle, route(p, confirm, PadInput.Swiped(com.rokid.context.core.hud.Swipe(forward = true, long = false)), 0, recording = false))
        assertEquals(Routed.FinishListening, route(p, confirm, PadInput.Tapped, 1_000, recording = true))
        assertEquals(Routed.ConfirmChoose, route(p, confirm, PadInput.Tapped, 20_000, recording = false), "still shown after the recording")
    }

    /** The router alone: a tap while recording is FinishListening whatever overlay is passed. */
    @Test fun aTapWhileRecordingIsAlwaysFinishListening() {
        for (o in listOf(null, confirm, Overlay.Toast("x"), Overlay.PausedSplash, Overlay.Listening(""), Overlay.Answer("q", "a", speaking = false))) {
            assertEquals(Routed.FinishListening, InputRouter.route(PadInput.Tapped, o, GestureDefaults.map, recording = true), "$o")
        }
    }

    @Test fun aPhoneToastAfterListenStartEndsTheLocalAsk() {
        val w = PhoneAskWatch()
        w.onStart(Overlay.Answer("q", "a", speaking = false))
        assertFalse(w.shouldEnd(true, Overlay.Answer("q", "a", speaking = false)))
        assertFalse(w.shouldEnd(true, Overlay.Listening("milk")))
        assertTrue(w.shouldEnd(true, Overlay.Toast("Couldn't hear that")))
        assertFalse(w.shouldEnd(false, Overlay.Toast("Couldn't hear that")), "once only")
    }

    @Test fun aToastAlreadyShownAtStartOrAnUndoToastDoesNotEndTheAsk() {
        val stale = Overlay.Toast("Couldn't hear that")
        val w = PhoneAskWatch()
        w.onStart(stale)
        assertFalse(w.shouldEnd(true, stale))
        assertFalse(w.shouldEnd(true, Overlay.Toast("Added", undoProposalId = "p")))
        assertTrue(w.shouldEnd(true, Overlay.Toast("Phone is listening")))
    }

    @Test fun noAskNoEnd() {
        val w = PhoneAskWatch()
        assertFalse(w.shouldEnd(true, Overlay.Toast("x")), "never armed")
        w.onStart(null)
        assertFalse(w.shouldEnd(true, null))
        assertFalse(w.shouldEnd(false, null), "recording ended")
        assertFalse(w.shouldEnd(true, Overlay.Toast("late toast")), "a toast after the ask ended is not this ask's")
    }
}
