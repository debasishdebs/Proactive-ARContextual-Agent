package com.rokid.context.orchestration.forget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.embed.LocalModelPower
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.AiIndexEmbedders
import com.rokid.context.orchestration.IndexMaintenance
import com.rokid.context.orchestration.PrefsIndexClock
import com.rokid.context.orchestration.PrefsReadAttempts
import com.rokid.context.work.budget.BackgroundWork
import com.rokid.context.work.frames.KeyframeGraph
import com.rokid.context.work.frames.KeyframePipeline
import kotlin.coroutines.cancellation.CancellationException

/**
 * The night job (spec §P6.1, Task 9): one unique periodic 24 h job that needs a charger and an idle device (`OrchestrationScheduler.ensureNightIndex`).
 * It makes the photos that are about to expire safe to delete: (1) every keyframe that becomes due within 24 h, is cited by no memory and is not an
 * ordinary skipped photo is queued for describe-before-delete, in deadline order; (2) the caption queue is drained with one cheap vision call per
 * batch, class BACKFILL; (3) `IndexMaintenance` runs once (the text and image backfill, under the bulk gate). It never deletes anything: the
 * retention job deletes a described photo at its deadline + 3 days, and an undescribed photo (failed describe, offline, no budget) is never
 * deleted: it is retried every night.
 */
class NightIndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!AppConfigStore.isPipelineEnabled(applicationContext)) return Result.success()
        return try {
            val keyframes = KeyframeGraph.get(applicationContext)
            val ai = AiGraph.get(applicationContext)
            val maintenance = IndexMaintenance(
                MemoryGraph.get(applicationContext).spaces, AiIndexEmbedders(ai.embedders), PrefsIndexClock(applicationContext),
                photos = keyframes::photoReads, readAttempts = PrefsReadAttempts(applicationContext), bulkAllowed = { bulkAllowed(keyframes) },
            )
            val night = NightIndex(
                db = MemoryGraph.get(applicationContext).db, pipeline = keyframes.pipeline,
                settings = { MemorySettingsStore.get(applicationContext).settings.value },
                bulkAllowed = { bulkAllowed(keyframes) },
                maintenance = { maintenance.run() },
                onDescribed = { PipelineStatsStore.addDescribedBeforeDelete(applicationContext, it) },
            )
            night.run()
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "night job failed: ${SafeLog.describe(e)}")
            Result.success() // the next night tries again; nothing here is lost by waiting
        }
    }

    /** G6 (charging or battery above 70 %) and a phone that is not too hot. The capture-burst check is [NightIndex]'s. */
    private fun bulkAllowed(keyframes: com.rokid.context.work.frames.KeyframeHandle): Boolean =
        LocalModelPower.bulkAllowed(LocalModelPower.read(applicationContext)) && keyframes.power.mayRunBackground(BackgroundWork.BACKFILL)

    companion object {
        private const val TAG = "NightIndexWorker"
        const val NAME = "night_index"
    }
}

/** The night job's logic, without WorkManager or the app graphs so that tests drive it with fakes. */
internal class NightIndex(
    private val db: ContextDatabase,
    private val pipeline: KeyframePipeline,
    private val settings: () -> MemorySettings,
    /** The bulk gate: charging or battery above 70 %, thermal below MODERATE. */
    private val bulkAllowed: () -> Boolean = { true },
    /** Runs [IndexMaintenance] once; its outcome's name is logged. */
    private val maintenance: suspend () -> IndexMaintenance.Outcome? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val onDescribed: (Int) -> Unit = {},
    private val drainMs: Long = DRAIN_MS,
    private val callClass: CallClass = CallClass.BACKFILL,
) {
    data class Result(val queued: Int, val described: Int, val left: Int, val backfill: String)

    suspend fun run(): Result {
        if (!bulkAllowed()) { Log.i(TAG, "PcNight gate closed: nothing described, nothing deleted"); return Result(0, 0, 0, "gate") }
        val queued = queue()
        var described = 0
        if (queued.isNotEmpty() || db.frameStateDao().describeQueued(1).isNotEmpty()) {
            // Not during an active capture burst: the live loop owns the budget then. A burst that starts mid-drain stops the drain.
            pipeline.drainCaptionQueue(drainMs, callClass) { bulkAllowed() && !captureBurst() }
            described = queued.chunked(CHUNK).sumOf { db.forgetDao().citedCount(it) }
            onDescribed(described)
        }
        val backfill = try {
            maintenance()?.name ?: "none"
        } catch (c: CancellationException) { throw c } catch (e: Exception) {
            Log.w(TAG, "backfill failed: ${SafeLog.describe(e)}"); "failed"
        }
        val left = queued.size - described
        Log.i(TAG, "PcNight described=$described left=$left backfilled=$backfill")
        return Result(queued.size, described, left, backfill)
    }

    /** Step 1: due-soon undescribed photos → CAPTION_QUEUED (attempts 0, lastError describe_before_delete), deadline order. Returns the ids that moved. */
    private suspend fun queue(): List<String> {
        val s = settings()
        if (s.photoDays == MemorySettings.FOREVER || s.perKindSince <= 0L) return emptyList() // nothing expires, or retention has not stamped its clock yet
        val now = clock()
        val ids = db.forgetDao().describeDue(s.perKindSince, s.photoDays * DAY, now, AHEAD_MS, PROTECT_MS, MAX_PER_NIGHT)
        val moved = ArrayList<String>(ids.size)
        // The queue order is the deadline order: each photo's updatedAt is now + its position.
        ids.chunked(CHUNK).forEachIndexed { c, chunk ->
            chunk.forEachIndexed { i, id -> if (db.frameStateDao().queueForDescribe(id, now + c * CHUNK + i) == 1) moved += id }
        }
        return moved
    }

    /** Whether keyframes are arriving right now (one in the last [BURST_MS]): the night job leaves the budget to the live path. */
    private suspend fun captureBurst(): Boolean = db.frameMetaDao().countSince(clock() - BURST_MS) > 0

    companion object {
        private const val TAG = "NightIndex"
        const val DRAIN_MS = 9 * 60_000L
        private const val DAY = 24 * 3_600_000L
        private const val AHEAD_MS = DAY
        private const val PROTECT_MS = 7 * DAY
        private const val CHUNK = 500
        private const val MAX_PER_NIGHT = 1_000
        private const val BURST_MS = 2 * 60_000L
    }
}
