package com.rokid.context.canvas.plan

import com.rokid.context.canvas.trace.CollectSession
import com.rokid.context.canvas.trace.FencedFiles
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.core.model.TraceControl
import com.rokid.context.memory.forget.CanvasSlice3Forget
import com.rokid.context.memory.forget.ForgetListener
import com.rokid.context.memory.forget.ForgetListeners
import java.io.File

/**
 * Slice 3a deletion, the part `:services:memory` does not own (S58, P7): FILES and the GLASSES. Memory tombstones, deletes labels and invalidates results in
 * one transaction ([CanvasSlice3Forget]); this class then removes the sealed trace folders of what was tombstoned and tells the glasses (`pc_trace_ctl` with
 * the tombstones and the forget range, so unsent glasses traces overlapping it are deleted whole). A Delete all re-publishes the control with the NEW persisted epoch
 * (so the glasses drop their buffer, traces and unacked marks) and turns Collect off. A failed send is retried by the next session-ready control (the epoch is
 * persisted); a failed file delete is retried by Delete all and never blocks. Registered as a [ForgetListener]; idempotent.
 */
class CanvasForgetCoordinator(
    private val forget: CanvasSlice3Forget,
    private val filesDir: File,
    private val collect: CollectSession,
    private val send: suspend (TraceControl) -> Unit,
    /** Hub-held state that Delete all must drop from memory too (the desired capture plan that is re-sent at every session). */
    private val onWiped: () -> Unit = {},
    /** The post-commit cleanup of the cascade (production: `ForgetActions.afterRange`: photos, reminders, notifications, cached export, queue scrub); false = a cached export could not be deleted. */
    private val afterCascade: suspend (com.rokid.context.memory.forget.ForgetService.Result) -> Boolean = { true },
) : ForgetListener {
    /** The user forgot the time range. Returns what was tombstoned. */
    suspend fun forgetRange(fromMs: Long, toMs: Long): CanvasSlice3Forget.Result = com.rokid.context.orchestration.DataOpGate.shared.delete {
        // Forget lifecycle M3: the read leases that reach the range are revoked before the delete (a planner failure revokes them all)
        com.rokid.context.orchestration.forget.LeaseRevocation.before(forget.db) { it.planRange(fromMs, toMs) }
        val r = forget.forgetRange(fromMs, toMs)
        val clean = afterCascade(r.cascade)
        afterRange(fromMs, toMs, r.tombstonedTraceIds)
        ForgetListeners.rangeForgotten(fromMs, toMs, r.tombstonedTraceIds, except = this)
        r.copy(cacheClean = clean)
    }

    /** Files and glasses follow a committed range forget (also a photo's time link, through [ForgetListeners]). Idempotent. */
    override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) = afterRange(fromMs, toMs, traceIds)

    /** A photo was forgotten or expired: its marks and traces went in the same transaction (the time link); any trace folder that is still there is removed now. */
    override suspend fun onFramesForgotten(frameIds: List<String>) { sweepTombstoned() }

    private suspend fun afterRange(fromMs: Long, toMs: Long, ids: List<String>) {
        deleteTraceFiles(ids)
        sweepTombstoned() // also recovers a crash between the transaction and the file delete
        trySend(collect.control(tombstones = ids, forgetFromMs = fromMs, forgetToMs = toMs))
    }

    /** Removes the folder of every tombstoned trace that is still on disk (the tombstone is durable, so a crash after the DB commit is repaired here). Returns the folders removed. */
    suspend fun sweepTombstoned(): Int = sweepTombstoned(forget.dao(), filesDir)

    override suspend fun onDeleteAll() {
        try { onWiped() } catch (e: Exception) { /* the persisted state is already cleared */ }
        trySend(collect.refresh()) // the persisted NEW epoch, collect off (the prefs were cleared): the glasses wipe what they hold
    }

    companion object {
        suspend fun sweepTombstoned(dao: com.rokid.context.memory.db.CanvasSlice3Dao, filesDir: File): Int {
            val root = File(filesDir, FencedFiles.TRACES_DIR)
            val dirs = root.listFiles()?.filter { it.isDirectory } ?: return 0
            return dirs.count { d -> (try { dao.trace(d.name)?.tombstoned == true } catch (e: Exception) { false }) && d.deleteRecursively() }
        }
    }

    /** Trace folders of [traceIds] under `canvas_traces`; a bad id or a missing folder is skipped. Returns the folders removed. */
    fun deleteTraceFiles(traceIds: List<String>): Int = traceIds.count { id ->
        try { File(File(filesDir, FencedFiles.TRACES_DIR), TraceFiles.dirName(id)).let { it.exists() && it.deleteRecursively() } } catch (e: Exception) { false }
    }

    private suspend fun trySend(c: TraceControl) {
        try { send(c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* the next session-ready control carries the epoch again */ }
    }
}
