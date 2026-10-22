package com.rokid.context.glasses.capture.plan

/**
 * Local auto-revert rules a plan can trip (S59, slice 3a core): the hard ceiling hit [LIMIT] times in [WINDOW_MS]. (`auto:trial_end`
 * and `auto:reboot` are the receiver's; the opens-per-minute and `exprErr` rules arrive with later slices.) A plan is "live" only for
 * an effective TRIAL or LIVE: the settings path and SHADOW capture exactly as settings do, so their ceiling hits are not a plan's fault.
 * Thread-safe; [onCeilingHit] is cheap enough to call under the session lock.
 */
class AutoRevert(private val windowMs: Long = WINDOW_MS, private val limit: Int = LIMIT) {
    private val hits = ArrayDeque<Long>()

    @Synchronized fun onCeilingHit(nowMs: Long) { hits.addLast(nowMs); while (hits.size > MAX_KEPT) hits.removeFirst() }

    /** The reason to revert with now, or null. Firing clears the hits (once per breach); no live plan clears them too (stale hits never count against a later plan). */
    @Synchronized fun check(nowMs: Long, planLive: Boolean): String? {
        if (!planLive) { hits.clear(); return null }
        while (hits.isNotEmpty() && nowMs - hits.first() > windowMs) hits.removeFirst()
        if (hits.size < limit) return null
        hits.clear()
        return "auto:ceiling"
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val LIMIT = 3
        private const val MAX_KEPT = 64
    }
}
