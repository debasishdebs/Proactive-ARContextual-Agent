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
    /** True = keep the candidate. */
    fun keep(nowMs: Long): Boolean {
        val r = active().exprRunner ?: return true
        val e0 = r.errTotal
        val keep = r.keep(nowMs, signals, applied(), ctxAgeS())
        if (r.errTotal != e0) revert.onExprErr(nowMs, running(), (r.errTotal - e0).toInt())
        return keep
    }
}
