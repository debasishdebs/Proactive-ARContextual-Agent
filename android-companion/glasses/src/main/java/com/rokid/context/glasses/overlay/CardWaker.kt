package com.rokid.context.glasses.overlay

import android.os.PowerManager

/**
 * Pure decision for the card wake-up: the Settings switch must be on and at most one wake per [minIntervalMs]. A refused wake
 * does not start the interval. Carries no content (not even a card id); callers log counts only.
 */
class WakeLimiter(private val clock: () -> Long, private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private var lastWakeAt: Long? = null

    @Synchronized fun allow(enabled: Boolean): Boolean {
        if (!enabled) return false
        val now = clock()
        val last = lastWakeAt
        if (last != null && now - last in 0 until minIntervalMs) return false
        lastWakeAt = now
        return true
    }

    companion object {
        /** No more than one card wake per this long (ms). */
        const val MIN_INTERVAL_MS = 60_000L
        /** The wake lock is released by the system after this long, whatever else happens (ms). */
        const val HOLD_MS = 8_000L
    }
}

/**
 * Switches the glasses screen on for an auto-opened place card (the phone's own pc_nudge card pushed while the screen is off).
 * `am start` / the minimised pop-up alone do not wake an asleep screen, and the pop-up is an overlay window (no activity to
 * setTurnScreenOn on), so a timed ACQUIRE_CAUSES_WAKEUP wake lock is the least invasive option. [release] runs when the card
 * goes (withdrawn, exit, destroy); the timeout in [acquire] is the backstop. Never holds the screen on after the card.
 */
class CardWaker(private val pm: PowerManager?, clock: () -> Long) {
    private val limiter = WakeLimiter(clock)
    private var lock: PowerManager.WakeLock? = null

    /** True = the screen was asked to wake (the card may show). */
    @Synchronized fun wake(enabled: Boolean): Boolean {
        val p = pm ?: return false
        if (!limiter.allow(enabled)) return false
        return runCatching {
            release()
            @Suppress("DEPRECATION")
            val l = p.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "pca:cardWake")
            l.setReferenceCounted(false)
            l.acquire(WakeLimiter.HOLD_MS)
            lock = l
            true
        }.getOrDefault(false)
    }

    @Synchronized fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }
}
