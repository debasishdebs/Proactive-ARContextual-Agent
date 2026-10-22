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
) : ForgetListener {
    /** The user forgot the time range. Returns what was tombstoned. */
    suspend fun forgetRange(fromMs: Long, toMs: Long): CanvasSlice3Forget.Result {
        val r = forget.forgetRange(fromMs, toMs)
        deleteTraceFiles(r.tombstonedTraceIds)
        ForgetListeners.rangeForgotten(fromMs, toMs, r.tombstonedTraceIds)
        trySend(collect.control(tombstones = r.tombstonedTraceIds, forgetFromMs = fromMs, forgetToMs = toMs))
        return r
    }

    override suspend fun onDeleteAll() {
        try { onWiped() } catch (e: Exception) { /* the persisted state is already cleared */ }
        trySend(collect.refresh()) // the persisted NEW epoch, collect off (the prefs were cleared): the glasses wipe what they hold
    }

    /** Trace folders of [traceIds] under `canvas_traces`; a bad id or a missing folder is skipped. Returns the folders removed. */
    fun deleteTraceFiles(traceIds: List<String>): Int = traceIds.count { id ->
        try { File(File(filesDir, FencedFiles.TRACES_DIR), TraceFiles.dirName(id)).let { it.exists() && it.deleteRecursively() } } catch (e: Exception) { false }
    }

    private suspend fun trySend(c: TraceControl) {
        try { send(c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* the next session-ready control carries the epoch again */ }
    }
}
