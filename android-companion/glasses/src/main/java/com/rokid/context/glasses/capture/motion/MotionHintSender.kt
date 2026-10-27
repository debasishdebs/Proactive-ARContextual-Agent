package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.MotionState

/**
 * Sends the glasses classifier state to the phone's hub (`pc_motion_hint`). Call [onSecond] once a second.
 * Sends when the state enters or leaves WALKING (at least [MIN_GAP_MS] since the last send) and every [REFRESH_MS].
 * [stillFrac5m] is the STILL share of the previous seconds (up to 300, none older than [WINDOW_MS]), -1 until [MIN_SLOTS] recent ones are recorded; the current
 * second is recorded after the decision. Times are glasses elapsedRealtime; the phone stamps receipt time.
 */
class MotionHintSender(
    private val clock: () -> Long,
    /** v0.9.7 A: the hash of the classifier thresholds in force, echoed on every hint; null = not reported. A change is sent at the next second. */
    private val classifierHash: () -> String? = { null },
    private val send: (MotionHint) -> Boolean,
) {
    private val ring = BooleanArray(SLOTS)
    private val at = LongArray(SLOTS)
    private var filled = 0
    private var next = 0
    private var stills = 0
    private var lastSendAt = clock()
    private var lastSentWalking = false
    private var force = false
    private var lastSentHash: String? = null

    /** Send at the next second whatever the cadence (the session just opened). */
    @Synchronized fun forceNext() { force = true }

    @Synchronized fun onSecond(state: MotionState, nowMs: Long) {
        expire(nowMs)
        val walking = state == MotionState.WALKING
        val since = nowMs - lastSendAt
        val hash = classifierHash()
        if (force || hash != lastSentHash || (walking != lastSentWalking && since >= MIN_GAP_MS) || since >= REFRESH_MS) {
            force = false
            val frac = if (filled < MIN_SLOTS) -1f else stills.toFloat() / filled
            // A failed send (session closed, bridge error) is not a send: it is retried on the next second.
            if (send(MotionHint(state = state, atMs = clock(), stillFrac5m = frac, classifierHash = hash))) { lastSendAt = nowMs; lastSentWalking = walking; lastSentHash = hash }
        }
        val still = state == MotionState.STILL
        if (filled == SLOTS && ring[next]) stills--
        ring[next] = still
        at[next] = nowMs
        if (still) stills++
        next = (next + 1) % SLOTS
        if (filled < SLOTS) filled++
    }

    /** Drops samples a full window old or older, so a scheduling gap never lets stale seconds count as recent coverage. */
    private fun expire(nowMs: Long) {
        while (filled > 0) {
            val oldest = (next - filled + SLOTS) % SLOTS
            if (at[oldest] > nowMs - WINDOW_MS) break
            if (ring[oldest]) stills--
            filled--
        }
    }

    companion object {
        const val WINDOW_MS = 300_000L
        const val SLOTS = 300
        const val MIN_SLOTS = 60
        const val MIN_GAP_MS = 5_000L
        const val REFRESH_MS = 60_000L
    }
}
