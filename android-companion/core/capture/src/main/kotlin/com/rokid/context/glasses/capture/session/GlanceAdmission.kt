package com.rokid.context.glasses.capture.session

import com.rokid.context.glasses.capture.motion.GlanceParams

/**
 * The free IMU admission test a glance (dwell) passes before the camera may open (spec A3 section 4): the novelty gate,
 * then the per-minute cap. Extracted unchanged from SamplingSession.onDwell so it can be tested and replayed on its own. Pure.
 */
object GlanceAdmission {
    enum class Decision { OPEN, NOVEL_SKIP, CAPPED }

    /**
     * @param sinceLastKeyframeMs now minus the time of the last keyframe
     * @param keptLastMinute keyframes kept in the last 60 s
     * @param cap the per-minute cap now (resting, A3 or context cap, whichever is lower)
     * @param hardCap no dwell bonus: while resting or while a context cap is active
     * @param turnedRad head rotation since the last keyframe pose
     * @param walked a walk was seen since the last keyframe pose
     */
    fun decide(
        sinceLastKeyframeMs: Long,
        keptLastMinute: Int,
        cap: Int,
        hardCap: Boolean,
        turnedRad: Float,
        walked: Boolean,
        glance: GlanceParams,
    ): Decision = when {
        sinceLastKeyframeMs < glance.noveltyWindowMs && turnedRad < glance.noveltyMinRad && !walked -> Decision.NOVEL_SKIP
        keptLastMinute >= cap && (hardCap || turnedRad < glance.bigTurnRad && !walked) || keptLastMinute.toLong() >= 2L * cap -> Decision.CAPPED
        else -> Decision.OPEN
    }
}
