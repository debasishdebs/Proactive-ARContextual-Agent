package com.rokid.context.orchestration.forget

import android.content.Context
import android.util.Log
import com.rokid.context.ai.SafeLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryMaintenance
import com.rokid.context.memory.forget.ForgetService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Memory detail's Forget fact / Forget moment (spec §2.4, §6.1.1, R-16, R-77). On IO and not cancellable once started
 * (a privacy action: leaving the screen must not stop it part-way). The cascade is one transaction
 * ([ForgetService]); then the listed JPEGs are deleted, the WAL is truncated (deleted pages don't linger), the
 * re-summarise worker is queued when anything was invalidated, and [onForgotten] runs (the hub refresh, so the next
 * StateFrame drops the memory from `today`). False on any failure; nothing is logged but the error's class.
 *
 * In-flight work is fenced elsewhere: a queued job carries ids and re-reads them (forgotten ones dropped), a fact for a
 * tombstoned frame is never written, and a derived write whose source vanished is dropped (PipelineRecords.appendFact).
 */
object ForgetActions {
    private const val TAG = "ForgetActions"

    suspend fun forgetFact(context: Context, memoryId: String, onForgotten: () -> Unit = {}): Boolean =
        run(context, onForgotten) { it.forgetFact(memoryId) }

    suspend fun forgetMoment(context: Context, frameId: String, onForgotten: () -> Unit = {}): Boolean =
        run(context, onForgotten) { it.forgetMoment(frameId) }

    internal suspend fun apply(context: Context, r: ForgetService.Result) {
        r.framesToDelete.forEach { runCatching { File(it).delete() } }
        if (r.invalidated.isNotEmpty()) ResummarizeWorker.enqueue(context)
    }

    private suspend fun run(context: Context, onForgotten: () -> Unit, op: suspend (ForgetService) -> ForgetService.Result): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                val graph = MemoryGraph.get(context)
                val r = op(ForgetService(graph.db, graph.proposals))
                apply(context, r)
                runCatching { MemoryMaintenance.checkpoint(graph.db) }
                runCatching(onForgotten)
                true
            } catch (e: Exception) {
                Log.w(TAG, "forget failed: ${SafeLog.describe(e)}")
                false
            }
        }
}
