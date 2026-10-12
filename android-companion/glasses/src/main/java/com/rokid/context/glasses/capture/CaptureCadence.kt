package com.rokid.context.glasses.capture

/**
 * Delay to the next capture (spec §3.1 `CaptureScheduler`): `HudSettings.captureIntervalSec` (default 300 s, at least
 * 30 s), doubled on battery ≤ 20 % or battery saver (the old 30 s → 60 s rule), 15 s after a failed attempt.
 */
object CaptureCadence {
    const val RETRY_MS = 15_000L
    const val MIN_INTERVAL_MS = 30_000L

    fun nextDelayMs(intervalSec: Int, batteryPct: Int, batterySaver: Boolean, failed: Boolean): Long {
        val base = (intervalSec * 1_000L).coerceAtLeast(MIN_INTERVAL_MS)
        if (failed) return minOf(RETRY_MS, base)
        return if (batterySaver || batteryPct in 0..20) base * 2 else base
    }
}
