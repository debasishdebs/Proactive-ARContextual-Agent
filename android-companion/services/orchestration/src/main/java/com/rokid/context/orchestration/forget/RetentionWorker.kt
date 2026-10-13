package com.rokid.context.orchestration.forget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.SafeLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.forget.ForgetService
import kotlin.coroutines.cancellation.CancellationException

/**
 * Retention (spec §2.4 windows; carry: shown but never enforced). Daily, unique [NAME] (KEEP), scheduled by
 * `OrchestrationScheduler.ensureRetention`. SHORT older than `shortHours`, MEDIUM older than `mediumDays`, LONG older
 * than `longDays` (by `createdAt`, any review state) go through the Forget-fact cascade in chunks of [CHUNK], with
 * redaction and the extraction lock (ruling). Frames older than `longDays` that no memory points at are forgotten as a
 * moment. It runs while capture is paused: it only removes data.
 */
class RetentionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val graph = MemoryGraph.get(applicationContext)
        val service = ForgetService(graph.db, graph.proposals)
        val r = Retention(graph, service).run(MemorySettingsStore.get(applicationContext).settings.value, System.currentTimeMillis())
        ForgetActions.apply(applicationContext, r)
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

/** One retention pass; returns the merged cascade result (files to delete, whether anything was invalidated). */
internal class Retention(private val graph: MemoryGraph, private val service: ForgetService) {
    suspend fun run(s: MemorySettings, nowMs: Long): ForgetService.Result {
        val dao = graph.db.forgetDao()
        val files = mutableListOf<String>()
        val invalidated = mutableSetOf<String>()
        val cutoffs = listOf(
            MemoryTier.SHORT to nowMs - s.shortHours * HOUR,
            MemoryTier.MEDIUM to nowMs - s.mediumDays * DAY,
            MemoryTier.LONG to nowMs - s.longDays * DAY,
        )
        for ((tier, before) in cutoffs) {
            for (round in 0 until MAX_ROUNDS) {
                val ids = dao.expiredIds(tier.name, before, CHUNK)
                if (ids.isEmpty()) break
                val r = service.forgetFacts(ids, lockFrames = true)
                files += r.framesToDelete; invalidated += r.invalidated
            }
        }
        for (round in 0 until MAX_ROUNDS) {
            val frames = dao.orphanFramesBefore(nowMs - s.longDays * DAY, CHUNK)
            if (frames.isEmpty()) break
            frames.forEach { files += service.forgetMoment(it).framesToDelete }
        }
        return ForgetService.Result(emptySet(), invalidated, 0, 0, files.distinct())
    }

    private companion object {
        const val CHUNK = 200
        const val MAX_ROUNDS = 50
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
