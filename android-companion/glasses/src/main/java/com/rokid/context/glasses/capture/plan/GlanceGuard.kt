package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.ExprSignals
import com.rokid.context.core.model.context.ContextState

/**
 * The glance guard of the plan in force (3c X3): evaluates its expression guards at a glance candidate and reports non-div0 errors to
 * [AutoRevert] (`auto:exprErr`). DROP-ONLY: true, UNAVAILABLE and ERROR keep the candidate; no plan or no guard keeps it. It runs only when
 * the session calls it at a glance that already happened, and reads the applied context state (after the local walking veto), never the raw one.
 */
class GlanceGuard(
    private val active: () -> ActiveConfig,
    private val signals: ExprSignals,
    private val applied: () -> ContextState,
    private val ctxAgeS: () -> Long,
    private val revert: AutoRevert,
    private val running: () -> PlanId?,
) {
    /**
     * The 1 Hz row tick of the plan's VALUE programs (3c E2a), from the existing 1 s loop: they tune the capture cap and the gap seconds of the editable rows, clamped, and an
     * ERROR or an UNAVAILABLE signal leaves the row's default. Their non-div0 errors count toward `auto:exprErr` like a guard's.
     */
    fun tickValues(nowMs: Long) {
        val r = active().exprRunner ?: return
        if (!r.hasValues) return
        val e0 = r.errTotal
        r.tickValues(nowMs, signals, applied(), ctxAgeS())
        if (r.errTotal != e0) revert.onExprErr(nowMs, running(), (r.errTotal - e0).toInt())
    }

    /** True = keep the candidate. */
    fun keep(nowMs: Long): Boolean {
        val r = active().exprRunner ?: return true
        val e0 = r.errTotal
        val keep = r.keep(nowMs, signals, applied(), ctxAgeS())
        if (r.errTotal != e0) revert.onExprErr(nowMs, running(), (r.errTotal - e0).toInt())
        return keep
    }
}
