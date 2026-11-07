package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
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
        // Codex r9 P2-3: the night job and the catch-up are two unique works; ONE process-wide run lock keeps their drains and index backfills from
        // overlapping, and the overdue check and the last_run_at write happen under it. A run that finds it held does nothing (the other one is working).
        return NightRunLock.tryRun { locked() } ?: run { PcLog.i(TAG, "PcNight skipped: a run is in progress"); Result.success() }
    }

    private suspend fun locked(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (inputData.getBoolean(KEY_CATCH_UP, false)) {
            val last = if (prefs.contains(K_LAST_RUN)) prefs.getLong(K_LAST_RUN, 0L) else null
            val left = try { MemoryGraph.get(applicationContext).db.frameStateDao().describeWaiting() } catch (c: CancellationException) { throw c } catch (e: Exception) { 0 }
            if (!NightCatchUp.due(last, left, now)) return Result.success()
            PcLog.i(TAG, "PcNight catchUp left=$left")
        }
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
            // A run whose gate was open counts as a night run: the catch-up only fills in for nights the idle-mode job missed.
            if (night.run().backfill != "gate") prefs.edit().putLong(K_LAST_RUN, now).commit() // under the run lock: the next run reads it
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            PcLog.w(TAG, "night job failed: ${SafeLog.describe(e)}")
            Result.success() // the next night tries again; nothing here is lost by waiting
        }
    }

    /** G6 (charging or battery above 70 %) and a phone that is not too hot. The capture-burst check is [NightIndex]'s. */
    private fun bulkAllowed(keyframes: com.rokid.context.work.frames.KeyframeHandle): Boolean =
        LocalModelPower.bulkAllowed(LocalModelPower.read(applicationContext)) && keyframes.power.mayRunBackground(BackgroundWork.BACKFILL)

    companion object {
        private const val TAG = "NightIndexWorker"
        const val NAME = "night_index"
        /** The daytime catch-up (2026-11-08): the same job on a charger without the device-idle constraint, only when the night run is overdue. */
        const val CATCH_UP_NAME = "night_index_catch_up"
        const val KEY_CATCH_UP = "catch_up"
        private const val PREFS = "night_index"
        private const val K_LAST_RUN = "last_run_at"
    }
}

/**
 * Codex r9 P2-3: the one run lock shared by the night job and its catch-up (both run in the app process, WorkManager's only one). Try-lock only: a
 * second run while one holds it returns null at once. A Mutex rather than a persisted lease: it cannot outlive a killed process, so no timeout is needed.
 */
internal object NightRunLock {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    val held: Boolean get() = mutex.isLocked

    suspend fun <T> tryRun(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        try { return block() } finally { mutex.unlock() }
    }
}

/**
 * 2026-11-08 quality fix: the night job needs a charger AND an idle device, and on the real phone it ran on 2 of 8 nights, so photos queued
 * for describe-before-delete (the night job's alone) waited for weeks. The catch-up runs the same bounded [NightIndex] (bulk gate, capture-burst
 * stop, 9 min, 60 calls) on a charger whenever photos wait and no run completed for [OVERDUE_MS].
 */
internal object NightCatchUp {
    const val OVERDUE_MS = 30 * 3_600_000L

    fun due(lastRunAt: Long?, waiting: Int, nowMs: Long): Boolean =
        waiting > 0 && (lastRunAt == null || nowMs - lastRunAt >= OVERDUE_MS || lastRunAt > nowMs)
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
    /** At most this many model calls a night: ~1,000 trip photos drain over several nights, not one. */
    private val maxCalls: Int = MAX_CALLS,
    private val callClass: CallClass = CallClass.BACKFILL,
) {
    data class Result(val queued: Int, val described: Int, val left: Int, val backfill: String)

    suspend fun run(): Result {
        if (!bulkAllowed()) { PcLog.i(TAG, "PcNight gate closed: nothing described, nothing deleted"); return Result(0, 0, 0, "gate") }
        val queued = queue()
        var described = 0
        if (queued.isNotEmpty() || db.frameStateDao().describeQueued(clock(), 1).isNotEmpty()) {
            // Not during an active capture burst: the live loop owns the budget then. A burst that starts mid-drain stops the drain.
            val before = db.frameStateDao().describedCount()
            pipeline.drainCaptionQueue(drainMs, callClass, { bulkAllowed() && !captureBurst() }, maxCalls)
            described = (db.frameStateDao().describedCount() - before).coerceAtLeast(0)
            onDescribed(described)
            // A describe that saved no memory is a failed attempt too: cooldown 1, 2, 4, 8 days, then parked as describe_failed (never deleted).
            db.frameStateDao().noteUncitedDescribed(clock())
        }
        val backfill = try {
            maintenance()?.name ?: "none"
        } catch (c: CancellationException) { throw c } catch (e: Exception) {
            PcLog.w(TAG, "backfill failed: ${SafeLog.describe(e)}"); "failed"
        }
        val left = db.frameStateDao().describeWaiting()
        PcLog.i(TAG, "PcNight described=$described left=$left backfilled=$backfill")
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
        const val MAX_CALLS = 60
        private const val DAY = 24 * 3_600_000L
        private const val AHEAD_MS = DAY
        private const val PROTECT_MS = 7 * DAY
        private const val CHUNK = 500
        private const val MAX_PER_NIGHT = 1_000
        private const val BURST_MS = 2 * 60_000L
    }
}
