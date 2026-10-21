package com.rokid.context.glasses.capture.motion.source

import com.rokid.context.core.model.MotionState

/** Combines the latest evidence of each source. Shadow-only in wave A3: nothing in capture reads the result. */
object MotionFusion {
    const val MAX_AGE_MS = 3_000L
    const val MIN_CONFIDENCE = 0.5f
    const val LIFT_CONFIDENCE = 0.7f
    const val GLASSES_ID = "glasses_imu"

    fun fuse(latest: List<MotionEvidence>, nowMs: Long): MotionEvidence? {
        val fresh = latest.filter { nowMs - it.atMs <= MAX_AGE_MS && it.confidence >= MIN_CONFIDENCE }
        val g = fresh.firstOrNull { it.sourceId == GLASSES_ID }
            ?: return fresh.filter { it.sourceId != GLASSES_ID }.maxByOrNull { it.confidence }
        if (g.state == MotionState.HEAD_MOVING) return g
        if (g.state == MotionState.STILL) {
            val lift = fresh.firstOrNull { it.sourceId != GLASSES_ID && it.state == MotionState.WALKING && it.confidence >= LIFT_CONFIDENCE }
            if (lift != null) return lift
        }
        return g
    }
}
