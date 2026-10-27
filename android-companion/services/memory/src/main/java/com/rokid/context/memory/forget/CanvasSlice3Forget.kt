package com.rokid.context.memory.forget

import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.ContextDatabase

/**
 * Slice 3a time-range Forget (S58, P7), the DB half: in ONE transaction the live traces overlapping `[fromMs, toMs]` are tombstoned (their events go, the row keeps
 * only its identity so a late chunk for them is refused by `acceptTraceFenced`), the marks and intent labels inside the range are deleted, `labelRev` moves, and every
 * replay and sweep that read a tombstoned trace, overlaps the range or was computed under older labels is INVALIDATED with its result deleted. Files and devices follow
 * through the `ForgetListener` seam (the caller's job, after commit); the durable `cleanup_outbox` rows (tombstoned glasses traces, the glasses range) are written in the same transaction by [CleanupOutbox].
 */
class CanvasSlice3Forget(private val db: ContextDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    data class Result(
        val tombstonedTraceIds: List<String>, val labelsDeleted: Int, val resultsInvalidated: Int,
        /** What the cascade removed (frames to delete, redacted proposals, invalidated derived facts): the caller must run the post-commit cleanup (`ForgetActions.apply`) on it. */
        val cascade: ForgetService.Result = ForgetService.Result.EMPTY,
        /** False when the post-commit cleanup could not delete a cached memory export (set by the coordinator). */
        val cacheClean: Boolean = true,
    )

    fun dao() = db.canvasSlice3Dao()

    /**
     * v0.9.7 review fix (R7): a time-range Forget is a MOMENT range Forget: every Moment of the range goes with its photos, memories, marks, run outputs and projection, and the seconds are fenced
     * (durable range + `moment_tombstone`), so no rebuild resolves a surviving source again. Same semantics as [MomentForget.forgetRange] (whole seconds, outward), which also runs [CanvasSlice3Dao.forgetRangeTx].
     */
    suspend fun forgetRange(fromMs: Long, toMs: Long): Result {
        val service = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db), clock)
        val r = MomentForget(db, service, clock).forgetRange(fromMs, toMs)
        return Result(r.tombstonedTraceIds, r.labelsDeleted, r.resultsInvalidated, r.cascade)
    }
}
