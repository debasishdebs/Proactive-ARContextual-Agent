package com.rokid.context.canvas.trace

import android.content.Context
import com.rokid.context.memory.db.ContextDatabase

/** Slice 3a start-up recovery, run once per process off the main thread: orphan tmp files go, and a replay or sweep still RUNNING is ABORTED (S77, S58). */
object CanvasStartup {
    data class Result(val tmpFilesSwept: Int, val jobsAborted: Int)

    suspend fun run(context: Context, db: ContextDatabase, now: Long = System.currentTimeMillis()): Result {
        // First: a Delete all that was cut short is finished before anything reads the old traces again.
        try { com.rokid.context.orchestration.MemoryWipe.resumeIfPending(context, db) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* retried at the next start */ }
        // Spec 7.2: pre-v10 trace rows are UNMAPPED (selected by every range) until mapped to the phone clock from their stored manifests; idempotent, retried at the next start.
        try { TraceClockBackfill(db.canvasSlice3Dao()).run() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        com.rokid.context.canvas.plan.CanvasForgetCoordinator.sweepTombstoned(db.canvasSlice3Dao(), context.filesDir) // a crash between a forget's commit and its file delete
        return Result(FencedFiles.sweepTmp(context.filesDir), db.canvasSlice3Dao().markRunningAborted(now))
    }
}
