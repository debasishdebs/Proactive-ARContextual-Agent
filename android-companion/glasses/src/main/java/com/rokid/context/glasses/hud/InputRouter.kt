package com.rokid.context.glasses.hud

import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.GestureDefaults
import com.rokid.context.core.model.Overlay

/** What a pad input means right now. */
sealed interface Routed {
    /** Through the wearer's gesture map; [long] = a long swipe (Today scroll). */
    data class Mapped(val action: GestureAction, val long: Boolean = false) : Routed
    data object ConfirmToggle : Routed
    data object ConfirmChoose : Routed
    data object ConfirmNo : Routed
    data object CancelListening : Routed
    /** Listening: a tap sends whatever the tap is mapped to (spec §3.2 "tap to send"). */
    data object FinishListening : Routed
    data object DismissOverlay : Routed
    /** A double-tap on an undo toast: undo that proposal (never the pause toggle). */
    data class Undo(val proposalId: String) : Routed
}

/**
 * Spec §3.5: an overlay's own rules first (Confirm: swipe moves, tap answers, double-tap = No; Listening: double-tap
 * cancels, tap sends; Answer: swipe or double-tap dismisses, but double-tap undoes while it carries an undo; undo toast: double-tap undoes), otherwise `HudSettings.gestures` (unmapped = default).
 */
object InputRouter {
    /**
     * While [recording] (the glasses' own push-to-talk), a double-tap is Cancel and a tap is send whatever is shown: a
     * tap meant as "send" can never vote on a Confirm (final review I1).
     */
    fun route(input: PadInput, overlay: Overlay?, gestures: Map<Gesture, GestureAction>, recording: Boolean = false): Routed = when {
        recording && input == PadInput.DoubleTapped -> Routed.CancelListening
        recording && input == PadInput.Tapped -> Routed.FinishListening
        else -> routeShown(input, overlay, gestures)
    }

    private fun routeShown(input: PadInput, overlay: Overlay?, gestures: Map<Gesture, GestureAction>): Routed = when (overlay) {
        is Overlay.Confirm -> when (input) {
            is PadInput.Swiped -> Routed.ConfirmToggle
            PadInput.Tapped -> Routed.ConfirmChoose
            PadInput.DoubleTapped -> Routed.ConfirmNo
        }
        is Overlay.Listening -> when (input) {
            PadInput.DoubleTapped -> Routed.CancelListening
            PadInput.Tapped -> Routed.FinishListening
            is PadInput.Swiped -> mapped(input, gestures)
        }
        is Overlay.Answer -> when {
            input == PadInput.Tapped -> mapped(input, gestures)
            input == PadInput.DoubleTapped -> overlay.undoProposalId?.let(Routed::Undo) ?: Routed.DismissOverlay // BUG-R2-5
            else -> Routed.DismissOverlay
        }
        is Overlay.Toast -> overlay.undoProposalId?.takeIf { input == PadInput.DoubleTapped }?.let(Routed::Undo) ?: mapped(input, gestures)
        else -> mapped(input, gestures)
    }

    /** An unmapped gesture uses its default (a partial table must not disable navigation); only an explicit NONE is none. */
    private fun action(g: Map<Gesture, GestureAction>, k: Gesture): GestureAction = g[k] ?: GestureDefaults.map[k] ?: GestureAction.NONE

    private fun mapped(input: PadInput, g: Map<Gesture, GestureAction>): Routed = when (input) {
        is PadInput.Swiped -> Routed.Mapped(action(g, if (input.swipe.forward) Gesture.SWIPE_FORWARD else Gesture.SWIPE_BACK), input.swipe.long)
        PadInput.Tapped -> Routed.Mapped(action(g, Gesture.TAP))
        PadInput.DoubleTapped -> Routed.Mapped(action(g, Gesture.DOUBLE_TAP))
    }
}
