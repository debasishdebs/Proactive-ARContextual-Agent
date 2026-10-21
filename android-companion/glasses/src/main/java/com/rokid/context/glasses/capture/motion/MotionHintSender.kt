package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.MotionState

/**
 * Sends the glasses classifier state to the phone's hub (`pc_motion_hint`). Call [onSecond] once a second.
 * Sends when the state enters or leaves WALKING (at least [MIN_GAP_MS] since the last send) and every [REFRESH_MS].
 * [stillFrac5m] is the STILL share of the previous seconds (up to 300), -1 until [MIN_SLOTS] are recorded; the current
 * second is recorded after the decision. Times are glasses elapsedRealtime; the phone stamps receipt time.
 */
class MotionHintSender(private val clock: () -> Long, private val send: (MotionHint) -> Boolean) {
    private val ring = BooleanArray(SLOTS)
    private var filled = 0
    private var next = 0
    private var stills = 0
    private var lastSendAt = clock()
    private var lastSentWalking = false
    private var force = false

    /** Send at the next second whatever the cadence (the session just opened). */
    @Synchronized fun forceNext() { force = true }

    @Synchronized fun onSecond(state: MotionState, nowMs: Long) {
        val walking = state == MotionState.WALKING
        val since = nowMs - lastSendAt
        if (force || (walking != lastSentWalking && since >= MIN_GAP_MS) || since >= REFRESH_MS) {
            force = false
            val frac = if (filled < MIN_SLOTS) -1f else stills.toFloat() / filled
            // A failed send (session closed, bridge error) is not a send: it is retried on the next second.
            if (send(MotionHint(state = state, atMs = clock(), stillFrac5m = frac))) { lastSendAt = nowMs; lastSentWalking = walking }
        }
        val still = state == MotionState.STILL
        if (filled == SLOTS && ring[next]) stills--
        ring[next] = still
        if (still) stills++
        next = (next + 1) % SLOTS
        if (filled < SLOTS) filled++
    }

    companion object {
        const val SLOTS = 300
        const val MIN_SLOTS = 60
        const val MIN_GAP_MS = 5_000L
        const val REFRESH_MS = 60_000L
    }
}
