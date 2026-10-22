package com.rokid.context.canvas.trace

import android.content.Context
import com.rokid.context.memory.db.ContextDatabase

/** Slice 3a start-up recovery, run once per process off the main thread: orphan tmp files go, and a replay or sweep still RUNNING is ABORTED (S77, S58). */
object CanvasStartup {
    data class Result(val tmpFilesSwept: Int, val jobsAborted: Int)

    suspend fun run(context: Context, db: ContextDatabase, now: Long = System.currentTimeMillis()): Result =
        Result(FencedFiles.sweepTmp(context.filesDir), db.canvasSlice3Dao().markRunningAborted(now))
}
