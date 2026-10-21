package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.motion.MotionClassifier
import com.rokid.context.glasses.capture.motion.SensorRate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * LOW IMU rate once capture has been blocked for [LOW_AFTER_MS] by a reason where the motion rule is not needed.
 * STILL (20 Hz, 0.5 s batching) once capture is running and the classifier has said STILL for [STILL_AFTER_MS].
 */
class SensorRatePolicy {
    private var sinceMs = -1L
    private var stillSinceMs = -1L

    /** A new session starts at FULL: forget any still run from the previous one. */
    fun reset() { sinceMs = -1L; stillSinceMs = -1L }

    fun update(reason: String?, nowMs: Long, state: MotionState? = null): SensorRate {
        if (reason != null && reason in LOW_REASONS) {
            stillSinceMs = -1L
            if (sinceMs < 0) sinceMs = nowMs
            return if (nowMs - sinceMs >= LOW_AFTER_MS) SensorRate.LOW else SensorRate.FULL
        }
        sinceMs = -1L
        if (reason == null && state == MotionState.STILL) {
            if (stillSinceMs < 0) stillSinceMs = nowMs
            return if (nowMs - stillSinceMs >= STILL_AFTER_MS) SensorRate.STILL else SensorRate.FULL
        }
        stillSinceMs = -1L
        return SensorRate.FULL
    }

    companion object {
        const val LOW_AFTER_MS = 10_000L
        const val STILL_AFTER_MS = 10_000L
        val LOW_REASONS = setOf("not_worn", "parked", "paused", "rest", "battery_budget", "battery", "phone_in_use", "heat")
    }
}

/**
 * Every sign of movement, without conflation: each onset (a SharedFlow, so a glance that starts and ends inside one
 * sensor batch still shows) as HEAD_MOVING, plus every non-STILL state. The rate job feeds these to the policy.
 */
fun MotionClassifier.movementSignals(): Flow<MotionState> =
    merge(onset.map { MotionState.HEAD_MOVING }, state.filter { it != MotionState.STILL })
