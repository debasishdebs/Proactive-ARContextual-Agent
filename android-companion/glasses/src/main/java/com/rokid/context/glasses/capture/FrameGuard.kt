package com.rokid.context.glasses.capture

/** Why a captured frame is not kept. */
enum class SkipReason { LOW_BATTERY, DARK, DUPLICATE, THROTTLED }

/**
 * Capture guardrails (spec §1.3, §3.1 `FrameGuard`), values unchanged from PulseKeepAliveService: battery ≤ 10 % and
 * not charging, mean luma < 8, a 64-bit average hash within Hamming 4 of the last kept frame, at most one kept frame
 * per 2.5 s. [skipDedupe] (debug adb checks) bypasses only the last two.
 */
object FrameGuard {
    const val LOW_BATTERY_PCT = 10
    const val DARK_MEAN_LUMA = 8.0
    const val DEDUP_HAMMING = 4
    const val MIN_KEEP_INTERVAL_MS = 2_500L

    fun check(
        batteryPct: Int, charging: Boolean, meanLuma: Double, hash: Long, lastHash: Long?,
        nowMs: Long, lastKeptAtMs: Long, skipDedupe: Boolean = false,
    ): SkipReason? = when {
        lowBattery(batteryPct, charging) -> SkipReason.LOW_BATTERY
        meanLuma < DARK_MEAN_LUMA -> SkipReason.DARK
        !skipDedupe && lastHash != null && hamming(lastHash, hash) <= DEDUP_HAMMING -> SkipReason.DUPLICATE
        // A clock that moved back (negative gap) does not throttle, or capture would stop until it caught up.
        !skipDedupe && nowMs - lastKeptAtMs in 0 until MIN_KEEP_INTERVAL_MS -> SkipReason.THROTTLED
        else -> null
    }

    /** Unknown battery (-1) fails open on purpose: a missing reading must not stop capture for good. */
    fun lowBattery(batteryPct: Int, charging: Boolean): Boolean = batteryPct in 0..LOW_BATTERY_PCT && !charging

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
