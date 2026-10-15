package com.rokid.context.glasses.capture

/** Why a captured frame is not kept. */
enum class SkipReason { LOW_BATTERY, DARK, DUPLICATE, THROTTLED }

/**
 * Capture guardrails (spec §1.3, §3.1 `FrameGuard`), values unchanged from PulseKeepAliveService: battery <= 10 % and
 * not charging, mean luma < 8. The change test, throttle and blur floor are the [com.rokid.context.glasses.capture.keyframe.KeyframeGate]'s
 * (Smart and Fixed both go through it).
 */
object FrameGuard {
    const val LOW_BATTERY_PCT = 10
    const val DARK_MEAN_LUMA = 8.0

    /** Low battery and a dark frame: the skips that stay with FrameGuard (spec section P1.1). */
    fun checkBatteryAndDark(batteryPct: Int, charging: Boolean, meanLuma: Double): SkipReason? = when {
        lowBattery(batteryPct, charging) -> SkipReason.LOW_BATTERY
        meanLuma < DARK_MEAN_LUMA -> SkipReason.DARK
        else -> null
    }

    /** Unknown battery (-1) fails open on purpose: a missing reading must not stop capture for good. */
    fun lowBattery(batteryPct: Int, charging: Boolean): Boolean = batteryPct in 0..LOW_BATTERY_PCT && !charging
}
