package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.SafeLog
import com.rokid.context.data.runtime.PipelineStatsStore
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
 * than `longDays` (by `createdAt`, any review state) expire in chunks of [CHUNK] (ForgetService.expire: redaction, PENDING
 * proposals expire but accepted ones keep their params and alarms (final review C1), the
 * extraction lock, but no descendant invalidation). JPEGs are deleted per chunk and swept (FrameSweep). Frames older than `longDays` that no memory points at are forgotten as a
 * moment. It runs while capture is paused: it only removes data.
 */
class RetentionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val graph = MemoryGraph.get(applicationContext)
        val service = ForgetService(graph.db, graph.proposals)
        var removed = false
        // Fix round 1 I3: each chunk's photos are deleted right after its transaction, not at the end of the pass.
        val now = System.currentTimeMillis()
        // Ruling 13: the first run of this build stamps the per-kind clock; photo deadlines count from max(capture time, stamp).
        val settings = MemorySettingsStore.get(applicationContext).stampPerKindSince(now)
        val summary = Retention(graph, service).run(settings, now) { r ->
            removed = true
            ForgetActions.apply(applicationContext, r)
        }
        PipelineStatsStore.addRetentionDeletes(applicationContext, summary.skippedDeleted, summary.deletedUndescribed)
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
        PcLog.w(TAG, "retention failed: ${SafeLog.describe(e)}")
        Result.retry()
    }

    companion object {
        private const val TAG = "RetentionWorker"
        const val NAME = "retention"
    }
}

/**
 * One retention pass (spec §P6; Rulings 8, 11-14), in order: (1) the per-kind clock [MemorySettings.perKindSince] (0 = unstamped: this pass
 * counts as the first run, so nothing is deleted by a per-kind deadline); (2) the SHORT/MEDIUM/LONG tier windows, which leave captions and
 * cluster summaries alone; (3) captions and cluster summaries by their own windows (FOREVER = never); (4) ordinary skipped photos (shed,
 * semantic near-dup, non-representative cluster members; never a representative or a key moment) after `skippedPhotoDays`; (5) every other
 * keyframe photo after `photoDays` while a memory cites it (a described-before-delete one after a further [GRACE_DAYS]; one no memory cites never); (6) image vectors after `imageVectorDays`; (7) the
 * legacy orphan pass for frames that have no `frame_meta`. Expiry deletes the rows with their edges and vectors and never invalidates
 * descendants: each derived memory expires on its own window. A photo goes through [ForgetService.expirePhotos] (frame row, JPEG and
 * sidecar; the tombstone lets the sweep retry a failed delete). [onChunk] gets each committed chunk.
 */
internal class Retention(
    private val graph: MemoryGraph,
    private val service: ForgetService,
    private val describeBeforeDeleteReady: Boolean = DESCRIBE_BEFORE_DELETE_READY,
    /** Test seam: runs after the skipped-photo selection and before its expiry (a describe can commit in between). */
    private val beforeSkippedExpiry: suspend (List<String>) -> Unit = {},
) {
    /** [deletedUndescribed] is 0 by construction (expiry requires a citing memory); it is kept as the Diagnostics counter that proves it. */
    data class Summary(val skippedDeleted: Int = 0, val deletedUndescribed: Int = 0)

    suspend fun run(s: MemorySettings, nowMs: Long, onChunk: suspend (ForgetService.Result) -> Unit = {}): Summary {
        val dao = graph.db.forgetDao()
        // 1. An unstamped clock means "this is the first run of the build": every per-kind deadline counts from now.
        val since = if (s.perKindSince > 0L) s.perKindSince else nowMs
        // 2. Tier windows (captions and cluster summaries are not tiered any more).
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
        // 3. Captions and cluster summaries.
        for ((type, days) in listOf(CAPTION to s.captionDays, CLUSTER_SUMMARY to s.clusterSummaryDays)) {
            if (days == MemorySettings.FOREVER) continue
            for (round in 0 until MAX_ROUNDS) {
                val ids = dao.expiredIdsOfType(type, nowMs - days * DAY, CHUNK)
                if (ids.isEmpty()) break
                onChunk(service.expire(ids))
            }
        }
        // 3b. A key-moment photo still waiting for its caption after 14 days becomes a skipped photo.
        dao.expireCaptionQueued(since, CAPTION_QUEUE_DAYS * DAY, nowMs)
        // 4. Ordinary skipped photos.
        var skipped = 0
        for (round in 0 until MAX_ROUNDS) {
            val ids = dao.skippedPhotosDue(since, s.skippedPhotoDays * DAY, nowMs, PROTECT_MS, CHUNK)
            if (ids.isEmpty()) break
            beforeSkippedExpiry(ids)
            val r = service.expirePhotos(ids, recheckSkipped = true) // S4: a photo described since the selection is kept
            skipped += r.expiredFrames
            onChunk(r)
        }
        // 5. Other keyframe photos, deleted only while a memory cites them. An undescribed photo is never deleted: the night job (NightIndexWorker)
        // describes it first, and the photo then waits [GRACE_DAYS] past its deadline (describe-before-delete). A failed describe or an
        // offline phone leaves the photo alone, however long that lasts. [describeBeforeDeleteReady] gates the deletion of described ones.
        if (s.photoDays != MemorySettings.FOREVER) {
            val window = s.photoDays * DAY
            val ready = if (describeBeforeDeleteReady) 1 else 0
            for (round in 0 until MAX_ROUNDS) {
                val ids = dao.linkedPhotosDue(since, window, nowMs, PROTECT_MS, ready, GRACE_DAYS * DAY, CHUNK)
                if (ids.isEmpty()) break
                onChunk(service.expirePhotos(ids, requireLinked = true))
            }
        }
        // 6. Image vectors (all spaces); the photo stays. The marker keeps the backfill from re-embedding what was removed.
        if (s.imageVectorDays != MemorySettings.FOREVER) {
            for (round in 0 until MAX_ROUNDS) {
                val ids = dao.vectorFramesBefore(nowMs - s.imageVectorDays * DAY, CHUNK)
                if (ids.isEmpty()) break
                graph.db.withTransaction { dao.deleteFrameVectorsFor(ids); dao.markVectorsExpired(ids) }
            }
        }
        // 7. Legacy frames (no frame_meta): forgotten as a moment once older than longDays and uncited.
        for (round in 0 until MAX_ROUNDS) {
            val frames = dao.orphanFramesBefore(nowMs - s.longDays * DAY, CHUNK)
            if (frames.isEmpty()) break
            frames.forEach { onChunk(service.forgetMoment(it, redactCites = false, phoneFilesOnly = true)) }
        }
        return Summary(skipped, 0)
    }

    companion object {
        /**
         * Describe-before-delete ships (Task 9): the night job describes every due, undescribed, non-skipped photo, and a described photo is
         * deleted at its deadline + [GRACE_DAYS]. An undescribed photo is never deleted, whether or not this is on.
         */
        const val DESCRIBE_BEFORE_DELETE_READY = true
        private const val CHUNK = 200
        private const val MAX_ROUNDS = 50
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        /** Spec §P6.4: a photo described for describe-before-delete is kept this long past its deadline. */
        private const val GRACE_DAYS = 3L
        /** Photos that existed when per-kind retention started are kept this long after the stamp, whatever their kind (I2). */
        private const val PROTECT_MS = 7 * 24 * HOUR
        private const val CAPTION_QUEUE_DAYS = 14L
        private const val CAPTION = "caption"
        private const val CLUSTER_SUMMARY = "cluster_summary"
    }
}
