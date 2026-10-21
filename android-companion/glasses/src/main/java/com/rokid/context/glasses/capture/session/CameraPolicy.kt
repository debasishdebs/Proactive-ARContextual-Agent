package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.MotionState

/**
 * Motion-driven camera policy (spec "Motion-driven camera"): a pure function of motion, time and settings that says
 * whether the camera should be open now, how often to analyse while open, and when the next brief check is due while closed.
 */
object CameraPolicy {
    /** [nextBriefCheckAtMs] is null while the camera is open (no brief check needed). */
    data class Decision(val open: Boolean, val analysisIntervalMs: Long, val nextBriefCheckAtMs: Long?)

    private const val BURST_CHECK_MS = 2_000L     // 3 s Quick look: one brief check per 2 s while moving
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
        lowPower: Boolean, heatSlow: Boolean, nowMs: Long, lastBriefCheckAtMs: Long, quiet: Boolean = false,
        profile: CaptureProfile = CaptureProfile.DEFAULT,
    ): Decision {
        val slow = if (heatSlow) 2L else 1L
        val interval = profile.analysisMs * slow
        val hold = holdMs(quickLookMs, lowPower)
        val moving = state == MotionState.HEAD_MOVING || state == MotionState.WALKING

        if (profile.wake == CameraWake.SETTLE) { // never a continuous stream: brief checks only
            if (state == MotionState.HEAD_MOVING) return Decision(false, interval, null)
            if (state == MotionState.WALKING) {
                return Decision(false, interval, lastBriefCheckAtMs + (profile.walkCheckMs ?: profile.settledCheckMs) * slow)
            }
            var settleMs = maxOf(if (lowPower) LOW_POWER_CHECK_MS else 0L, profile.settledCheckMs)
            if (msSinceKeyframe > QUIET_AFTER_KEYFRAME_MS || quiet) settleMs = maxOf(settleMs, profile.quietCheckMs)
            return Decision(false, interval, lastBriefCheckAtMs + settleMs * slow)
        }
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
            else -> profile.settledCheckMs
        }
        // [quiet]: several brief checks in a row found nothing usable (dark, face-down): the 15 s cadence
        if (msSinceKeyframe > QUIET_AFTER_KEYFRAME_MS || quiet) checkMs = maxOf(checkMs, profile.quietCheckMs)
        var next = lastBriefCheckAtMs + checkMs * slow
        // One immediate check at motion end + H when the hold is shorter than the look time.
        if (msSinceMotionEnd != null && hold < quickLookMs) {
            val atEnd = nowMs - msSinceMotionEnd + hold
            if (lastBriefCheckAtMs < atEnd) next = minOf(next, atEnd)
        }
        return Decision(false, interval, next)
    }
}
