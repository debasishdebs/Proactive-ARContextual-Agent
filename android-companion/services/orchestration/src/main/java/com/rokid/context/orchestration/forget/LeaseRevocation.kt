package com.rokid.context.orchestration.forget

import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.ForgetPlan
import com.rokid.context.memory.forget.lifecycle.ForgetPlanner
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.orchestration.DataOpGate

/**
 * Forget lifecycle M3: what a Forget tells the read leases (design 2.3). Called INSIDE the delete gate, so no new lease can open meanwhile. [before] revokes from the [ForgetPlanner]'s plan (which only reads) BEFORE
 * the delete transaction; a planner failure revokes EVERY lease (fail closed: a flow loses work, a Forget never leaks). [after] revokes from what the delete really reported. Neither changes what a Forget deletes.
 * Literal log lines only (no counts, ids or text).
 */
internal object LeaseRevocation {
    private const val TAG = "LeaseRevocation"
    private val leases get() = DataOpGate.shared.leases

    /** Plans with [plan] and revokes every lease that meets it; everything on a planning failure. Never throws except cancellation. */
    suspend fun before(db: ContextDatabase, plan: suspend (ForgetPlanner) -> ForgetPlan): Int = try {
        revokePlan(plan(ForgetPlanner(db)))
    } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c
    } catch (e: Exception) { all("plan failed") }

    fun revokePlan(p: ForgetPlan): Int = leases.revoke(p.nodes.keys).also { if (it > 0) PcLog.milestone(TAG, "leases revoked by the plan") }

    fun all(@Suppress("UNUSED_PARAMETER") why: String): Int = leases.revokeAll().also { if (it > 0) PcLog.milestone(TAG, "all leases revoked") }

    /** The memories (erased and invalidated) the delete reported, revoked after the commit: a second line behind [before]. */
    fun after(r: ForgetService.Result): Int {
        val refs = (r.forgotten + r.invalidated).map { Ref(Family.MEMORY, it) }
        return if (refs.isEmpty()) 0 else leases.revoke(refs).also { if (it > 0) PcLog.milestone(TAG, "leases revoked after the commit") }
    }
}
