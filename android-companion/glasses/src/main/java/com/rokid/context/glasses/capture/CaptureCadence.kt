package com.rokid.context.glasses.capture

/**
 * Fixed mode only: delay to the next capture (spec §3.1 `CaptureScheduler`): `HudSettings.captureIntervalSec` (default 300 s, 30 s to
 * 1 h), doubled on battery ≤ 20 % or battery saver (the old 30 s → 60 s rule), 15 s after a failed attempt.
 */
object CaptureCadence {
    const val RETRY_MS = 15_000L
    /** Smart mode: the alarm is only a watchdog that restarts sampling after a process death (spec P1.1). */
    const val WATCHDOG_MS = 5 * 60_000L
    /** Smart mode after three failed camera binds: Fixed at 30 s for the rest of the session (spec P1.6). */
    const val FALLBACK_INTERVAL_SEC = 30
    const val MIN_INTERVAL_MS = 30_000L
    /** At most 1 h, so [CaptureScheduler] can treat a stored time beyond [MAX_DELAY_MS] as a clock jump. */
    const val MAX_INTERVAL_MS = 3_600_000L
    /** The longest real delay: [MAX_INTERVAL_MS] doubled on low battery. */
    const val MAX_DELAY_MS = 2 * MAX_INTERVAL_MS

    fun nextDelayMs(intervalSec: Int, batteryPct: Int, batterySaver: Boolean, failed: Boolean): Long {
        val base = (intervalSec * 1_000L).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        if (failed) return minOf(RETRY_MS, base)
        return if (batterySaver || batteryPct in 0..20) base * 2 else base
    }
}
