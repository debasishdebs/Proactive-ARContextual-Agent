package com.rokid.context.glasses.capture.plan

/**
 * Local auto-revert rules a plan can trip (S59, slice 3a core): the hard ceiling refused [LIMIT] times in [WINDOW_MS] under ONE plan.
 * (`auto:trial_end` and `auto:reboot` are the receiver's; the opens-per-minute and `exprErr` rules arrive with later slices.)
 *
 * Every hit carries the identity ([PlanId]) of the plan that was running when it happened. A hit with no running plan (the settings path,
 * SHADOW) is dropped at once, and [check] counts only hits of the plan running now, so hits of plan A (or of settings) can never count
 * against B, whatever the supervisor happened to observe in between. Thread-safe; cheap enough to call under the session or gate lock.
 */
class AutoRevert(private val windowMs: Long = WINDOW_MS, private val limit: Int = LIMIT) {
    private class Hit(val at: Long, val id: PlanId)
    private val hits = ArrayDeque<Hit>()

    @Synchronized fun onCeilingHit(nowMs: Long, running: PlanId?) {
        if (running == null) return
        hits.addLast(Hit(nowMs, running)); while (hits.size > MAX_KEPT) hits.removeFirst()
    }

    /** The reason to revert [running] with now, or null. Hits of any other plan are discarded; firing clears the hits (once per breach). */
    @Synchronized fun check(nowMs: Long, running: PlanId?): String? {
        hits.removeAll { it.id != running || nowMs - it.at > windowMs }
        if (running == null || hits.size < limit) return null
        hits.clear()
        return "auto:ceiling"
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val LIMIT = 3
        private const val MAX_KEPT = 64
    }
}
