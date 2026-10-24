package com.rokid.context.memory.forget

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase

/**
 * Slice 3a time-range Forget (S58, P7), the DB half: in ONE transaction the live traces overlapping `[fromMs, toMs]` are tombstoned (their events go, the row keeps
 * only its identity so a late chunk for them is refused by `acceptTraceFenced`), the marks and intent labels inside the range are deleted, `labelRev` moves, and every
 * replay and sweep that read a tombstoned trace, overlaps the range or was computed under older labels is INVALIDATED with its result deleted. Files and devices follow
 * through the `ForgetListener` seam (the caller's job, after commit).
 */
class CanvasSlice3Forget(private val db: ContextDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    data class Result(val tombstonedTraceIds: List<String>, val labelsDeleted: Int, val resultsInvalidated: Int)

    fun dao() = db.canvasSlice3Dao()

    suspend fun forgetRange(fromMs: Long, toMs: Long): Result = db.withTransaction {
        val o = db.canvasSlice3Dao().forgetRangeTx(fromMs, toMs, clock())
        db.momentDao().dropBetween(fromMs, toMs) // its marks are gone: so are the stored Moments of that time
        Result(o.tombstonedTraceIds, o.labelsDeleted, o.resultsInvalidated)
    }
}
