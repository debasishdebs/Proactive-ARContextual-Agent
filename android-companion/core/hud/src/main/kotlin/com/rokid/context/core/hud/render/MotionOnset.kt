package com.rokid.context.core.hud.render

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlaceCardInfo

/**
 * Head-motion onset, the one definition for every place card surface (the HUD presenter and the minimised pop-up): a change from still or unknown to head moving, walking or a vehicle.
 * A card is dismissed by an onset only once it has been up [PlaceCardInfo.MOTION_DISMISS_AFTER_MS].
 */
class MotionOnset {
    @Volatile var lastOnsetAtMs = 0L
        private set
    private var last = MotionState.UNKNOWN

    /** Any thread, about once a second. True when this sample is an onset. */
    @Synchronized fun onMotion(state: MotionState, nowMs: Long): Boolean {
        val moving = state == MotionState.HEAD_MOVING || state == MotionState.WALKING || state == MotionState.VEHICLE
        val wasStill = last == MotionState.STILL || last == MotionState.UNKNOWN
        last = state
        if (moving && wasStill) { lastOnsetAtMs = nowMs; return true }
        return false
    }

    companion object {
        /** True when an onset at [onsetMs] dismisses a card shown at [shownAtMs], judged at [nowMs]. */
        fun dismisses(onsetMs: Long, shownAtMs: Long, nowMs: Long): Boolean = onsetMs > 0 && onsetMs >= shownAtMs + PlaceCardInfo.MOTION_DISMISS_AFTER_MS && onsetMs <= nowMs
    }
}
