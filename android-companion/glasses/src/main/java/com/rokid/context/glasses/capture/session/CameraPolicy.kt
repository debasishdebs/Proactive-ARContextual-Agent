package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.MotionState

/**
 * Motion-driven camera policy (spec "Motion-driven camera"): a pure function of motion, time and settings that says
 * whether the camera should be open now, how often to analyse while open, and when the next brief check is due while closed.
 */
object CameraPolicy {
    /** [nextBriefCheckAtMs] is null while the camera is open (no brief check needed). */
    data class Decision(val open: Boolean, val analysisIntervalMs: Long, val nextBriefCheckAtMs: Long?)

    private const val ANALYSIS_MS = 333L          // 3 Hz while open
    private const val BURST_CHECK_MS = 2_000L     // 3 s Quick look: one brief check per 2 s while moving
    private const val SETTLED_CHECK_MS = 5_000L
    private const val QUIET_CHECK_MS = 15_000L
    private const val LOW_POWER_CHECK_MS = 10_000L
    private const val QUIET_AFTER_KEYFRAME_MS = 10 * 60_000L

    /** Hold H: how long the camera stays open after motion ends, per Quick look setting. */
    fun holdMs(quickLookMs: Int, lowPower: Boolean): Long {
        val h = when {
            quickLookMs <= 1000 -> 4000L
            quickLookMs <= 1500 -> 2000L
            quickLookMs <= 2000 -> 1000L
            else -> 0L
        }
        return if (lowPower) h / 2 else h
    }

    fun decide(
        state: MotionState, decelerating: Boolean, msSinceMotionEnd: Long?, msSinceKeyframe: Long, quickLookMs: Int,
        lowPower: Boolean, heatSlow: Boolean, nowMs: Long, lastBriefCheckAtMs: Long,
    ): Decision {
        val slow = if (heatSlow) 2L else 1L
        val interval = ANALYSIS_MS * slow
        val hold = holdMs(quickLookMs, lowPower)
        val moving = state == MotionState.HEAD_MOVING || state == MotionState.WALKING

        if (decelerating) return Decision(true, interval, null)
        if (moving) {
            if (hold == 0L) { // bursts: closed between brief checks every 2 s
                return Decision(false, interval, lastBriefCheckAtMs + BURST_CHECK_MS * slow)
            }
            return Decision(true, interval, null)
        }
        if (msSinceMotionEnd != null && msSinceMotionEnd in 0 until hold) return Decision(true, interval, null)

        var checkMs = when {
            lowPower -> LOW_POWER_CHECK_MS
            else -> SETTLED_CHECK_MS
        }
        if (msSinceKeyframe > QUIET_AFTER_KEYFRAME_MS) checkMs = maxOf(checkMs, QUIET_CHECK_MS)
        var next = lastBriefCheckAtMs + checkMs * slow
        // One immediate check at motion end + H when the hold is shorter than the look time.
        if (msSinceMotionEnd != null && hold < quickLookMs) {
            val atEnd = nowMs - msSinceMotionEnd + hold
            if (lastBriefCheckAtMs < atEnd) next = minOf(next, atEnd)
        }
        return Decision(false, interval, next)
    }
}
