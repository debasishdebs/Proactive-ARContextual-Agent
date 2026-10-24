package com.rokid.context.glasses.capture.plan

/**
 * Local auto-revert rules a plan can trip (S59, slice 3a core): the hard ceiling refused [LIMIT] times in [WINDOW_MS] under ONE plan.
 * (`auto:trial_end` and `auto:reboot` are the receiver's; the opens-per-minute rule arrives with a later slice; `auto:exprErr` (more than 10 non-div0 expression errors in a minute, F1) is here.)
 *
 * Every hit carries the identity ([PlanId]) of the plan that was running when it happened. A hit with no running plan (the settings path,
 * SHADOW) is dropped at once, and [check] counts only hits of the plan running now, so hits of plan A (or of settings) can never count
 * against B, whatever the supervisor happened to observe in between. Thread-safe; cheap enough to call under the session or gate lock.
 */
class AutoRevert(private val windowMs: Long = WINDOW_MS, private val limit: Int = LIMIT) {
    private class Hit(val at: Long, val id: PlanId)
    private val hits = ArrayDeque<Hit>()
    private val errs = ArrayDeque<Hit>()

    @Synchronized fun onCeilingHit(nowMs: Long, running: PlanId?) {
        if (running == null) return
        hits.addLast(Hit(nowMs, running)); while (hits.size > MAX_KEPT) hits.removeFirst()
    }

    /** One non-div0 expression ERROR (3c X3). Div0 never comes here (Q11). A hit with no running plan (settings, SHADOW) is dropped. */
    @Synchronized fun onExprErr(nowMs: Long, running: PlanId?) {
        if (running == null) return
        errs.addLast(Hit(nowMs, running)); while (errs.size > ERR_LIMIT + 1) errs.removeFirst()
    }

    /** The reason to revert [running] with now, or null. Hits of any other plan are discarded; firing clears the hits (once per breach). */
    @Synchronized fun check(nowMs: Long, running: PlanId?): String? {
        errs.removeAll { it.id != running || nowMs - it.at >= ERR_WINDOW_MS }
        if (running != null && errs.size > ERR_LIMIT) { errs.clear(); return "auto:exprErr" }   // more than 10 non-div0 errors in one minute (F1)
        hits.removeAll { it.id != running || nowMs - it.at > windowMs }
        if (running == null || hits.size < limit) return null
        hits.clear()
        return "auto:ceiling"
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val LIMIT = 3
        const val ERR_WINDOW_MS = 60_000L
        const val ERR_LIMIT = 10
        private const val MAX_KEPT = 64
    }
}
