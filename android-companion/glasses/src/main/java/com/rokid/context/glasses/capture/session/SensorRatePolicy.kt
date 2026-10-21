package com.rokid.context.glasses.capture.session

import com.rokid.context.glasses.capture.motion.SensorRate

/** LOW IMU rate once capture has been blocked for [LOW_AFTER_MS] by a reason where the motion rule is not needed. */
class SensorRatePolicy {
    private var sinceMs = -1L

    fun update(reason: String?, nowMs: Long): SensorRate {
        if (reason == null || reason !in LOW_REASONS) { sinceMs = -1L; return SensorRate.FULL }
        if (sinceMs < 0) sinceMs = nowMs
        return if (nowMs - sinceMs >= LOW_AFTER_MS) SensorRate.LOW else SensorRate.FULL
    }

    companion object {
        const val LOW_AFTER_MS = 10_000L
        val LOW_REASONS = setOf("not_worn", "parked", "paused", "rest", "battery_budget", "battery", "phone_in_use", "heat")
    }
}
