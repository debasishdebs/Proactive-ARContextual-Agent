package com.rokid.context.glasses.hud

import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.Overlay

/** What a pad input means right now. */
sealed interface Routed {
    /** Through the wearer's gesture map; [long] = a long swipe (Today scroll). */
    data class Mapped(val action: GestureAction, val long: Boolean = false) : Routed
    data object ConfirmToggle : Routed
    data object ConfirmChoose : Routed
    data object ConfirmNo : Routed
    data object CancelListening : Routed
    data object DismissOverlay : Routed
}

/**
 * Spec §3.5: an overlay's own rules first (Confirm: swipe moves, tap answers, double-tap = No; Listening: double-tap
 * cancels; Answer: swipe or double-tap dismisses), otherwise `HudSettings.gestures` (unmapped = NONE).
 */
object InputRouter {
    fun route(input: PadInput, overlay: Overlay?, gestures: Map<Gesture, GestureAction>): Routed = when (overlay) {
        is Overlay.Confirm -> when (input) {
            is PadInput.Swiped -> Routed.ConfirmToggle
            PadInput.Tapped -> Routed.ConfirmChoose
            PadInput.DoubleTapped -> Routed.ConfirmNo
        }
        is Overlay.Listening -> if (input == PadInput.DoubleTapped) Routed.CancelListening else mapped(input, gestures)
        is Overlay.Answer -> if (input == PadInput.Tapped) mapped(input, gestures) else Routed.DismissOverlay
        else -> mapped(input, gestures)
    }

    private fun mapped(input: PadInput, g: Map<Gesture, GestureAction>): Routed = when (input) {
        is PadInput.Swiped -> Routed.Mapped(g[if (input.swipe.forward) Gesture.SWIPE_FORWARD else Gesture.SWIPE_BACK] ?: GestureAction.NONE, input.swipe.long)
        PadInput.Tapped -> Routed.Mapped(g[Gesture.TAP] ?: GestureAction.NONE)
        PadInput.DoubleTapped -> Routed.Mapped(g[Gesture.DOUBLE_TAP] ?: GestureAction.NONE)
    }
}
