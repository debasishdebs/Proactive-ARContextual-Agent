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

    suspend fun forgetRange(fromMs: Long, toMs: Long): Result = db.withTransaction {
        val dao = db.canvasSlice3Dao()
        val ids = dao.liveTraces().filter { it.startedAtMs <= toMs && (it.endedAtMs ?: Long.MAX_VALUE) >= fromMs }.map { it.traceId }
        val now = clock()
        ids.forEach { dao.tombstoneTrace(it, now) }
        val labels = dao.deleteLabelsBetween(fromMs, toMs)
        val invalid = dao.invalidateBySources(ids, fromMs, toMs, labelRevChanged = labels > 0)
        Result(ids, labels, invalid)
    }
}
