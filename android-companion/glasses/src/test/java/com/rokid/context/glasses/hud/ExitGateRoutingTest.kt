package com.rokid.context.glasses.hud

import com.rokid.context.core.hud.Swipe
import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.GestureDefaults
import com.rokid.context.core.model.Overlay
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 2026-10-14 §2: DOUBLE_TAP goes to the exit gate only with the default mapping and a page showing. */
class ExitGateRoutingTest {
    private val defaults = GestureDefaults.map

    @Test fun defaultDoubleTapOnAPageArmsTheGate() {
        assertEquals(Routed.ExitGate, InputRouter.route(PadInput.DoubleTapped, null, defaults, gate = true))
        assertEquals(Routed.ExitGate, InputRouter.route(PadInput.DoubleTapped, null, emptyMap(), gate = true), "unmapped = default")
        assertEquals(Routed.Mapped(GestureAction.PAUSE_RESUME), InputRouter.route(PadInput.DoubleTapped, null, defaults), "no page: as before")
        assertEquals(Routed.Mapped(GestureAction.ASK), InputRouter.route(PadInput.Tapped, null, defaults, gate = true))
    }

    @Test fun remappedDoubleTapBypassesGate() {
        for (a in listOf(GestureAction.NONE, GestureAction.ASK, GestureAction.NEXT_PAGE, GestureAction.DISMISS)) {
            val custom = defaults + (Gesture.DOUBLE_TAP to a)
            assertEquals(Routed.Mapped(a), InputRouter.route(PadInput.DoubleTapped, null, custom, gate = true), "$a")
        }
        // Pause moved to the tap: the tap pauses at once, the gate is not involved.
        val tapPauses = defaults + (Gesture.TAP to GestureAction.PAUSE_RESUME) + (Gesture.DOUBLE_TAP to GestureAction.NONE)
        assertEquals(Routed.Mapped(GestureAction.PAUSE_RESUME), InputRouter.route(PadInput.Tapped, null, tapPauses, gate = true))
    }

    @Test fun overlaysKeepTheirDoubleTap() {
        assertEquals(Routed.CancelListening, InputRouter.route(PadInput.DoubleTapped, Overlay.Listening(""), defaults, gate = true))
        assertEquals(Routed.DismissOverlay, InputRouter.route(PadInput.DoubleTapped, Overlay.Answer("q", "a", speaking = false), defaults, gate = true))
        assertEquals(Routed.Undo("p"), InputRouter.route(PadInput.DoubleTapped, Overlay.Answer("q", "a", false, undoProposalId = "p"), defaults, gate = true))
        assertEquals(Routed.Undo("p"), InputRouter.route(PadInput.DoubleTapped, Overlay.Toast("Added", undoProposalId = "p"), defaults, gate = true))
        assertEquals(Routed.ConfirmNo, InputRouter.route(PadInput.DoubleTapped, Overlay.Confirm("p", "t", "d"), defaults, gate = true))
        assertEquals(Routed.CancelListening, InputRouter.route(PadInput.DoubleTapped, null, defaults, recording = true, gate = true))
        // A swipe while the gate is up is still a swipe (the activity cancels the gate first).
        assertEquals(Routed.Mapped(GestureAction.NEXT_PAGE), InputRouter.route(PadInput.Swiped(Swipe(true, false)), null, defaults, gate = true))
    }
}
