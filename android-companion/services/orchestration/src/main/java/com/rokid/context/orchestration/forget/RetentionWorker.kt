package com.rokid.context.orchestration.forget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.SafeLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryMaintenance
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.forget.ForgetService
import kotlin.coroutines.cancellation.CancellationException

/**
 * Retention (spec §2.4 windows; carry: shown but never enforced). Daily, unique [NAME] (KEEP), scheduled by
 * `OrchestrationScheduler.ensureRetention`. SHORT older than `shortHours`, MEDIUM older than `mediumDays`, LONG older
 * than `longDays` (by `createdAt`, any review state) expire in chunks of [CHUNK] (ForgetService.expire: redaction and the
 * extraction lock, but no descendant invalidation). JPEGs are deleted per chunk and swept (FrameSweep). Frames older than `longDays` that no memory points at are forgotten as a
 * moment. It runs while capture is paused: it only removes data.
 */
class RetentionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val graph = MemoryGraph.get(applicationContext)
        val service = ForgetService(graph.db, graph.proposals)
        var removed = false
        // Fix round 1 I3: each chunk's photos are deleted right after its transaction, not at the end of the pass.
        Retention(graph, service).run(MemorySettingsStore.get(applicationContext).settings.value, System.currentTimeMillis()) { r ->
            removed = true
            ForgetActions.apply(applicationContext, r)
        }
        FrameSweep.sweep(applicationContext)
        if (removed) {
            ForgetActions.scrubQueue(applicationContext)
            runCatching { MemoryMaintenance.checkpoint(graph.db) }
            runCatching { ForgetActions.dataRemoved() }
        }
        Result.success()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.w(TAG, "retention failed: ${SafeLog.describe(e)}")
        Result.retry()
    }

    companion object {
        private const val TAG = "RetentionWorker"
        const val NAME = "retention"
    }
}

/**
 * One retention pass. Expiry (fix round 1 C1 ruling) deletes the expired rows with their edges and vectors and never
 * invalidates descendants: each derived memory expires on its own window. [onChunk] gets each committed chunk.
 */
internal class Retention(private val graph: MemoryGraph, private val service: ForgetService) {
    suspend fun run(s: MemorySettings, nowMs: Long, onChunk: suspend (ForgetService.Result) -> Unit = {}) {
        val dao = graph.db.forgetDao()
        val cutoffs = listOf(
            MemoryTier.SHORT to nowMs - s.shortHours * HOUR,
            MemoryTier.MEDIUM to nowMs - s.mediumDays * DAY,
            MemoryTier.LONG to nowMs - s.longDays * DAY,
        )
        for ((tier, before) in cutoffs) {
            for (round in 0 until MAX_ROUNDS) {
                val ids = dao.expiredIds(tier.name, before, CHUNK)
                if (ids.isEmpty()) break
                onChunk(service.expire(ids))
            }
        }
        for (round in 0 until MAX_ROUNDS) {
            val frames = dao.orphanFramesBefore(nowMs - s.longDays * DAY, CHUNK)
            if (frames.isEmpty()) break
            frames.forEach { onChunk(service.forgetMoment(it)) }
        }
    }

    private companion object {
        const val CHUNK = 200
        const val MAX_ROUNDS = 50
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
