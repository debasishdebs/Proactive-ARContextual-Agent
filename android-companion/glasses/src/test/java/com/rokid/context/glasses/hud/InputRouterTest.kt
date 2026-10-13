package com.rokid.context.glasses.hud

import com.rokid.context.core.hud.Swipe
import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.GestureDefaults
import com.rokid.context.core.model.Overlay
import kotlin.test.Test
import kotlin.test.assertEquals

class InputRouterTest {
    private val defaults = GestureDefaults.map
    private val fwd = PadInput.Swiped(Swipe(forward = true, long = false))
    private val back = PadInput.Swiped(Swipe(forward = false, long = true))

    @Test fun defaultsFollowSpec35() {
        assertEquals(Routed.Mapped(GestureAction.ASK), InputRouter.route(PadInput.Tapped, null, defaults))
        assertEquals(Routed.Mapped(GestureAction.PAUSE_RESUME), InputRouter.route(PadInput.DoubleTapped, null, defaults))
        assertEquals(Routed.Mapped(GestureAction.NEXT_PAGE), InputRouter.route(fwd, null, defaults))
        assertEquals(Routed.Mapped(GestureAction.PREV_PAGE, long = true), InputRouter.route(back, null, defaults))
    }

    @Test fun remappedGesturesUseTheUsersTable() {
        val custom = defaults + (Gesture.TAP to GestureAction.PAUSE_RESUME) + (Gesture.DOUBLE_TAP to GestureAction.NONE)
        assertEquals(Routed.Mapped(GestureAction.PAUSE_RESUME), InputRouter.route(PadInput.Tapped, null, custom))
        assertEquals(Routed.Mapped(GestureAction.NONE), InputRouter.route(PadInput.DoubleTapped, null, custom))
        assertEquals(Routed.Mapped(GestureAction.ASK), InputRouter.route(PadInput.Tapped, null, emptyMap()), "unmapped = default")
        val partial = mapOf(Gesture.TAP to GestureAction.NONE)
        assertEquals(Routed.Mapped(GestureAction.NONE), InputRouter.route(PadInput.Tapped, null, partial), "explicit NONE = none")
        assertEquals(Routed.Mapped(GestureAction.PAUSE_RESUME), InputRouter.route(PadInput.DoubleTapped, null, partial))
    }

    @Test fun confirmHasItsOwnRules() {
        val c = Overlay.Confirm("p1", "Add milk?", "Shopping list")
        assertEquals(Routed.ConfirmToggle, InputRouter.route(fwd, c, defaults))
        assertEquals(Routed.ConfirmChoose, InputRouter.route(PadInput.Tapped, c, defaults))
        assertEquals(Routed.ConfirmNo, InputRouter.route(PadInput.DoubleTapped, c, defaults))
    }

    @Test fun doubleTapCancelsWhileListening() {
        val l = Overlay.Listening("what")
        assertEquals(Routed.CancelListening, InputRouter.route(PadInput.DoubleTapped, l, defaults))
        assertEquals(Routed.FinishListening, InputRouter.route(PadInput.Tapped, l, defaults), "tap again = send")
    }

    @Test fun swipeOrDoubleTapDismissesAnAnswer() {
        val a = Overlay.Answer("q", "a", speaking = true)
        assertEquals(Routed.DismissOverlay, InputRouter.route(fwd, a, defaults))
        assertEquals(Routed.DismissOverlay, InputRouter.route(PadInput.DoubleTapped, a, defaults))
        assertEquals(Routed.Mapped(GestureAction.ASK), InputRouter.route(PadInput.Tapped, a, defaults))
    }

    /** Spec §3.2 "tap to send" holds whatever the tap is mapped to. */
    @Test fun aTapSendsWhileListeningEvenWhenTapIsRemapped() {
        val custom = defaults + (Gesture.TAP to GestureAction.NEXT_PAGE)
        assertEquals(Routed.FinishListening, InputRouter.route(PadInput.Tapped, Overlay.Listening(""), custom))
        assertEquals(Routed.Mapped(GestureAction.NEXT_PAGE), InputRouter.route(fwd, Overlay.Listening(""), defaults), "swipes still page")
    }

    /** Fix round 1: while the glasses record, a double-tap is Cancel whatever is shown (toast, splash, Confirm). */
    @Test fun aDoubleTapWhileRecordingAlwaysCancels() {
        val confirm = Overlay.Confirm("p1", "Add milk?", "Shopping list")
        for (o in listOf(null, Overlay.Toast("Capture resumed"), Overlay.PausedSplash, confirm, Overlay.Listening(""))) {
            assertEquals(Routed.CancelListening, InputRouter.route(PadInput.DoubleTapped, o, defaults, recording = true), "$o")
        }
        assertEquals(Routed.ConfirmNo, InputRouter.route(PadInput.DoubleTapped, confirm, defaults), "not recording: Confirm's own rule")
        assertEquals(Routed.Mapped(GestureAction.PAUSE_RESUME), InputRouter.route(PadInput.DoubleTapped, null, defaults))
    }
}
