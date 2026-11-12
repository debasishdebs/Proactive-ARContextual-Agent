package com.rokid.context.canvas.moment

import androidx.room.withTransaction
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.MomentTombstoneEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.moment.MomentValidity
import com.rokid.context.orchestration.DataOpGate

/**
 * The ONE-TIME purge of invalid Moments (v0.9.6; user rulings 2026-10-25). At the first start after the upgrade it deletes every stored Moment that is not valid ([MomentValidity]: no timestamp,
 * or no live photo, except a MARK Moment with an explicit error reason) and the memories whose source frames no longer exist. Destructive on purpose (the app is a debug build).
 *
 * It goes through the existing delete paths, not raw SQL: the whole run is a DELETE on the [DataOpGate] (it waits for a running export and is DEFERRED, nothing deleted, marker not stored, when the
 * export outlasts the wait); the Moments leave through `MomentDao.remove` (parts, sensor rows, audit, derived fields, trace deps; every Scene holding one shrinks and hides its derived fields) in one
 * transaction with a `moment_tombstone` per second (the builder and the frame intake refuse a tombstoned second, so a rebuild never brings an invalid Moment back) and the run rows keyed by the
 * Moment; the memories leave through [ForgetService.forgetFacts] (vectors, edges, cited asks and nudges, derived descendants, the outbox rows for files; the caller gets its result in [after] to sweep
 * files, drop the cached export and queue the re-summarise). A bare mark, a photo and a memory with a live photo are NOT touched: the mark stays a record of its own, only its Moment goes.
 * The marker in `canvas_meta` ([KEY]) is stored LAST, so a failure part-way is simply finished by the next start (the steps are idempotent). Logs: literal lines with counts only.
 */
class InvalidMomentPurge(
    private val db: ContextDatabase,
    private val service: ForgetService,
    private val gate: DataOpGate = DataOpGate.shared,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Receives the cascade result of the memory deletion (production: `ForgetActions.apply`). Not called when no memory went. */
    private val after: suspend (ForgetService.Result) -> Unit = {},
) {
    enum class Status { DONE, ALREADY_DONE, DEFERRED }
    /** Counts only: Moments looked at and deleted, memories deleted. */
    data class Result(val status: Status, val scanned: Int, val momentsDeleted: Int, val memoriesDeleted: Int)

    /** Test seam: runs after both deletions and before the marker is stored, so a test can prove a failure leaves no marker. Always null in production. */
    @Volatile internal var testFaultBeforeMarker: (() -> Unit)? = null

    suspend fun runOnce(): Result {
        val meta = db.canvasSlice3Dao()
        if (meta.meta(KEY) != null) return Result(Status.ALREADY_DONE, 0, 0, 0)
        return try {
            gate.delete { purge() }
        } catch (e: DataOpGate.ExportInProgress) {
            if (e is DataOpGate.WriteInProgress) PcLog.i(TAG, "invalid purge deferred: save in progress") else PcLog.i(TAG, "invalid purge deferred: export in progress")
            Result(Status.DEFERRED, 0, 0, 0)
        }
    }

    private suspend fun purge(): Result {
        PcLog.i(TAG, "invalid purge start")
        val md = db.momentDao()
        val all = md.allMoments()
        val parts = all.map { it.momentId }.chunked(CHUNK).flatMap { md.partsOf(it) }.groupBy { it.momentId }
        val live = parts.values.flatten().filter { it.kind == MomentPartKind.FRAME }.map { it.refId }.distinct().chunked(CHUNK).flatMap { md.liveCapturedFrameIds(it) }.toSet()
        val invalid = all.filter { m ->
            !MomentValidity.isValid(m.momentId, m.momentType, m.errorReason, parts[m.momentId].orEmpty().any { it.kind == MomentPartKind.FRAME && it.refId in live })
        }.map { it.momentId }
        if (invalid.isNotEmpty()) db.withTransaction {
            val now = clock()
            md.insertTombstones(invalid.mapNotNull { id -> com.rokid.context.memory.moment.MomentId.parse(id)?.let { MomentTombstoneEntity(id, now) } })
            invalid.chunked(CHUNK).forEach { c ->
                val keys = c.map { "moment:$it" }
                db.canvasDao().deleteItemsForFrames(keys); db.canvasDao().deleteScoresForFrames(keys); db.canvasDao().deleteTracesForFrames(keys)
            }
            md.remove(invalid)
        }
        val orphans = md.orphanMemoryIds()
        var cascade = ForgetService.Result.EMPTY
        for (c in orphans.chunked(MEMORY_CHUNK)) cascade = merge(cascade, service.forgetFacts(c))
        if (orphans.isNotEmpty()) after(cascade)
        testFaultBeforeMarker?.invoke()
        db.canvasSlice3Dao().putMeta(KEY, clock().toString())
        val scanned = all.size; val deleted = invalid.size; val memories = orphans.size
        PcLog.i(TAG, "invalid purge done: scanned=$scanned deleted=$deleted memories=$memories")
        return Result(Status.DONE, scanned, deleted, memories)
    }

    private fun merge(a: ForgetService.Result, b: ForgetService.Result) = ForgetService.Result(
        a.forgotten + b.forgotten, a.invalidated + b.invalidated, a.redactedAsks + b.redactedAsks, a.redactedNudges + b.redactedNudges,
        (a.framesToDelete + b.framesToDelete).distinct(), (a.redactedProposals + b.redactedProposals).distinct(), a.expiredFrames + b.expiredFrames,
        (a.redactedAskIds + b.redactedAskIds).distinct(),
    )

    companion object {
        /** `canvas_meta` key of the one-time marker (a time, no user data). */
        const val KEY = "invalid_moment_purge_v1_ms"
        private const val TAG = "PcMoments"
        private const val CHUNK = 500
        private const val MEMORY_CHUNK = 200
    }
}
