package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.orchestration.DataOpGate
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.SafeLog
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryMaintenance
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.memory.forget.ForgetRadius
import com.rokid.context.memory.forget.ForgetService
import kotlin.coroutines.cancellation.CancellationException

/**
 * Retention v3 (design 2026-11-24): retention NEVER deletes a memory row, an edge or text; only an explicit user Forget does. Daily, unique [NAME] (KEEP), scheduled by `OrchestrationScheduler.ensureRetention`. In order:
 *  1. the one-shot migration ([RetentionMigration]: purge the `lin:` lineage keys of older builds, mark the photos that expired before the marker existed);
 *  2. the file-retry sweep ([FrameSweep.sweep]): ALWAYS, also under Pause (it finishes deletes that already committed, including a user Forget's JPEGs);
 *  3. quota eviction ([Retention.runQuota]): ALWAYS, also under Pause (it is the storage bound): the oldest eligible photos once the cap is exceeded, never text, never a photo of explicit importance;
 *  4. when "Pause clean-up" (Developer) is ON: a counts-only line, and nothing below runs;
 *  5. the deleting steps ([Retention.run]): demotion of SHORT facts past the Quick recall window (their TEXT VECTOR only), skipped photos, photos by their own setting, image vectors, legacy orphan frames.
 * It runs while capture is paused.
 */
class RetentionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        // The exclusive side of the DataOpGate: an export in progress is waited for (30 s); then DataOpGate.ExportInProgress ends up in the retry below (never skipped).
        DataOpGate.shared.delete { retain() }
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        PcLog.w(TAG, "retention failed: ${SafeLog.describe(e)}")
        Result.retry()
    }

    private suspend fun retain(): Result {
        val graph = MemoryGraph.get(applicationContext)
        val service = ForgetService(graph.db, graph.proposals)
        val now = System.currentTimeMillis()
        // Ruling 13: the first run of this build stamps the per-kind clock; photo deadlines count from max(capture time, stamp).
        val settings = MemorySettingsStore.get(applicationContext).stampPerKindSince(now)
        var removed = false
        val onChunk: suspend (ForgetService.Result) -> Unit = { r -> removed = true; ForgetActions.apply(applicationContext, r) }
        runCatching { RetentionMigration.runOnce(graph.db, now) }.onFailure { PcLog.w(TAG, "retention migration failed (next run retries): ${SafeLog.describe(it)}") }
        // r2-7: the file-retry sweep always runs: Pause must never strand a user's Forget JPEGs.
        FrameSweep.sweep(applicationContext)
        val retention = Retention(graph, service, usedBytes = { StorageMeter.usedBytes(applicationContext) }, protectedFrames = { QuotaProtection.frames(applicationContext) })
        // K6 / r2-7: quota eviction is the storage bound and runs under Pause as well.
        retention.runQuota(settings, now, onChunk)
        if (DeveloperFlags.pauseRetention(applicationContext)) {
            // P0: the Developer "Pause clean-up" switch skips the DELETING steps only (default ON for the retention candidate). One counts-only line says what would be due.
            val p = runCatching { retention.plan(settings, now) }.getOrNull()
            if (p == null) { PcLog.milestone(TAG, "retention paused: deleting steps skipped") } else {
                val demoteDue = p.demoteDue; val photosDue = p.photosDue + p.skippedPhotosDue; val vectorsDue = p.vectorsDue
                PcLog.milestone(TAG, "retention paused: deleting steps skipped, would be due demoteDue=$demoteDue photosDue=$photosDue vectorsDue=$vectorsDue")
            }
        } else {
            val summary = retention.run(settings, now, onChunk)
            PipelineStatsStore.addRetentionDeletes(applicationContext, summary.skippedDeleted, summary.deletedUndescribed)
        }
        if (removed) {
            ForgetActions.scrubQueue(applicationContext)
            runCatching { MemoryMaintenance.checkpoint(graph.db) }
            runCatching { ForgetActions.dataRemoved() }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "RetentionWorker"
        const val NAME = "retention"
    }
}

/**
 * One retention pass (spec §P6; retention v3). Photos and vectors only; text is never deleted. In [run], in order: (1) the per-kind clock [MemorySettings.perKindSince] (0 = unstamped: this pass counts as the first run, so nothing is
 * deleted by a per-kind deadline); (2) DEMOTION: a SHORT fact older than the Quick recall window loses its TEXT VECTOR (its row, text and edges stay: provenance, Moments, cards, nudges and answers keep it); (3) a key-moment photo still
 * waiting for its caption after 14 days becomes a skipped photo; (4) ordinary skipped photos (shed, semantic near-dup, non-representative cluster members; never a representative or a key moment) after `skippedPhotoDays`; (5) every other
 * keyframe photo after `photoDays` while a memory cites it (a described-before-delete one after a further [GRACE_DAYS]; one no memory cites never); (6) image vectors after `imageVectorDays`; (7) the legacy orphan pass for frames that have
 * no `frame_meta` ([ForgetService.expireOrphanFrames]: tombstone, expiry marker, `frame` row and JPEG only). A photo goes through [ForgetService.expirePhotos] (tombstone, expiry marker, frame row, JPEG and sidecar in ONE transaction; the
 * tombstone lets the sweep retry a failed delete). [runQuota] is the separate, bounded storage cap. [onChunk] gets each committed chunk.
 */
internal class Retention(
    private val graph: MemoryGraph,
    private val service: ForgetService,
    private val describeBeforeDeleteReady: Boolean = DESCRIBE_BEFORE_DELETE_READY,
    /** Test seam: runs after the skipped-photo selection and before its expiry (a describe can commit in between). */
    private val beforeSkippedExpiry: suspend (List<String>) -> Unit = {},
    /** Bytes the app's memory uses now (database plus photo folders). Measured again after every quota chunk. */
    private val usedBytes: suspend () -> Long = { 0L },
    /** The photos of explicit importance that live outside `context.db` (place cards, pins). Null = unreadable: quota eviction then does NOTHING (fail closed). */
    private val protectedFrames: suspend () -> Set<String>? = { emptySet() },
    /** Quota eviction takes at most this many photos per round (and stops inside a round as soon as their files cover what is over). */
    private val quotaChunk: Int = QUOTA_CHUNK,
) {
    /** [deletedUndescribed] is 0 by construction (expiry requires a citing memory); it is kept as the Diagnostics counter that proves it. */
    data class Summary(val skippedDeleted: Int = 0, val deletedUndescribed: Int = 0, val demoted: Int = 0, val photosExpired: Int = 0)

    /** P4: what one pass would do, counts only (never an id or a text). The `plan` line prints it; a test pins the numbers. */
    data class Plan(
        val demoteDue: Int, val skippedPhotosDue: Int, val photosDue: Int, val vectorsDue: Int, val legacyDue: Int, val quotaDue: Int,
    ) {
        fun line() = "plan demoteDue=$demoteDue skippedPhotosDue=$skippedPhotosDue photosDue=$photosDue vectorsDue=$vectorsDue legacyDue=$legacyDue quotaDue=$quotaDue"
    }

    /** Read-only. Mirrors the selection of each step in [run] and [runQuota] (the capped selects are counted up to [COUNT_CAP]). */
    suspend fun plan(s: MemorySettings, nowMs: Long): Plan {
        val dao = graph.db.forgetDao()
        val since = if (s.perKindSince > 0L) s.perKindSince else nowMs
        fun forever(d: Int) = d == MemorySettings.FOREVER
        val photos = if (forever(s.photoDays)) 0 else dao.linkedPhotosDue(since, s.photoDays * DAY, nowMs, PROTECT_MS, if (describeBeforeDeleteReady) 1 else 0, GRACE_DAYS * DAY, COUNT_CAP).size
        val over = runCatching { s.quotaBytes > 0 && usedBytes() > s.quotaBytes }.getOrDefault(false)
        val protect = if (over) runCatching { protectedFrames() }.getOrNull() else null
        return Plan(
            demoteDue = dao.countDemotable(nowMs - s.recallHours * HOUR),
            skippedPhotosDue = dao.skippedPhotosDue(since, s.skippedPhotoDays * DAY, nowMs, PROTECT_MS, COUNT_CAP).size,
            photosDue = photos,
            vectorsDue = if (forever(s.imageVectorDays)) 0 else dao.vectorFramesBefore(nowMs - s.imageVectorDays * DAY, COUNT_CAP).size,
            legacyDue = if (forever(s.photoDays)) 0 else dao.orphanFramesBefore(nowMs - s.photoDays * DAY, COUNT_CAP).size,
            quotaDue = if (protect == null) 0 else dao.quotaPhotosDue(nowMs - QUOTA_MIN_AGE_DAYS * DAY, ForgetRadius.ms(), protectList(protect), COUNT_CAP).size,
        )
    }

    suspend fun run(s: MemorySettings, nowMs: Long, onChunk: suspend (ForgetService.Result) -> Unit = {}): Summary {
        // P4: a counts-only plan line before anything is removed, a result line after (literal templates, numbers only: no id, no text).
        runCatching {
            val p = plan(s, nowMs)
            val demoteDue = p.demoteDue; val skippedPhotosDue = p.skippedPhotosDue; val photosDue = p.photosDue; val vectorsDue = p.vectorsDue; val legacyDue = p.legacyDue; val quotaDue = p.quotaDue
            PcLog.milestone(LOG_TAG, "plan demoteDue=$demoteDue skippedPhotosDue=$skippedPhotosDue photosDue=$photosDue vectorsDue=$vectorsDue legacyDue=$legacyDue quotaDue=$quotaDue")
        }
        var photosExpired = 0
        val track: suspend (ForgetService.Result) -> Unit = { r -> photosExpired += r.expiredFrames; onChunk(r) }
        val started = System.currentTimeMillis()
        val summary = runPass(s, nowMs, since = if (s.perKindSince > 0L) s.perKindSince else nowMs, onChunk = track)
        val secs = (System.currentTimeMillis() - started) / 1000
        val skippedPhotosExpired = summary.skippedDeleted
        val demoted = summary.demoted
        val seconds = secs
        PcLog.milestone(LOG_TAG, "done demoted=$demoted photosExpired=$photosExpired skippedPhotosExpired=$skippedPhotosExpired seconds=$seconds")
        return summary.copy(photosExpired = photosExpired)
    }

    /**
     * K6 quota eviction. Over the cap ([MemorySettings.quotaBytes], at most 2000 MB), the OLDEST eligible photos go until the usage is at 90% of the cap: described keyframes captured at least [QUOTA_MIN_AGE_DAYS] days ago, never a
     * photo of explicit importance (a place memory, a mark within the radius, an accepted proposal's source, a place card, a pin), never text. Each photo goes through [ForgetService.expirePhotos] (tombstone, expiry marker, row, JPEG),
     * so a Moment keeps its FRAME part and "Photo expired". Bounded: rounds of at most [quotaChunk] photos, at most [QUOTA_ROUNDS] rounds, stops when nothing is eligible. Returns the number of photos evicted. Counts-only log lines.
     */
    suspend fun runQuota(s: MemorySettings, nowMs: Long, onChunk: suspend (ForgetService.Result) -> Unit = {}): Int {
        val cap = s.quotaBytes
        if (cap <= 0) return 0
        var used = runCatching { usedBytes() }.getOrElse { return 0 }
        if (used <= cap) return 0
        val protect = runCatching { protectedFrames() }.getOrNull()
        if (protect == null) { PcLog.milestone(LOG_TAG, "quota over, protection unreadable: nothing evicted"); return 0 }
        val target = cap / 10 * 9
        val dao = graph.db.forgetDao()
        var evicted = 0
        for (round in 0 until QUOTA_ROUNDS) {
            if (used <= target) break
            val ids = dao.quotaPhotosDue(nowMs - QUOTA_MIN_AGE_DAYS * DAY, ForgetRadius.ms(), protectList(protect), quotaChunk)
            if (ids.isEmpty()) break
            // oldest first, and no more than the files of these photos need to cover what is over the target (a photo whose size is unknown counts 0: the round is then bounded by [quotaChunk])
            val need = used - target
            var covered = 0L
            val take = ArrayList<String>()
            for (id in ids) { take += id; covered += photoBytes(dao.frame(id)?.path); if (covered >= need) break }
            val r = service.expirePhotos(take, requireLinked = true) // a photo no memory describes any more is skipped inside the transaction
            evicted += r.expiredFrames
            val freed = r.framesToDelete.sumOf { photoBytes(it) } // read before the caller deletes the files
            onChunk(r)
            if (r.expiredFrames == 0) break
            used = if (freed > 0) used - freed else runCatching { usedBytes() }.getOrNull() ?: break
        }
        val quotaEvicted = evicted
        if (used > cap) PcLog.milestone(LOG_TAG, "quota over, nothing evictable quotaEvicted=$quotaEvicted") else PcLog.milestone(LOG_TAG, "quota evicted quotaEvicted=$quotaEvicted")
        return evicted
    }

    private fun photoBytes(path: String?): Long = if (path.isNullOrBlank()) 0L else runCatching { java.io.File(path).let { if (it.isFile) it.length() else 0L } }.getOrDefault(0L)

    /** An `IN (...)` list is never empty (SQLite) and never above the bind limit. */
    private fun protectList(ids: Set<String>): List<String> = ids.toList().take(MAX_PROTECT).ifEmpty { listOf("") }

    private suspend fun runPass(s: MemorySettings, nowMs: Long, since: Long, onChunk: suspend (ForgetService.Result) -> Unit): Summary {
        val dao = graph.db.forgetDao()
        // 1. An unstamped clock means "this is the first run of the build": every per-kind deadline counts from now (`since`, computed by [run]).
        // 2. DEMOTION (retention v3): SHORT facts past the Quick recall window lose their TEXT VECTOR only; row, text, edges and every citation stay. A caption or a cluster summary is never demoted.
        var demoted = 0
        for (round in 0 until MAX_ROUNDS) {
            val n = service.demoteFacts(nowMs - s.recallHours * HOUR, CHUNK)
            if (n == 0) break
            demoted += n
        }
        // 3. A key-moment photo still waiting for its caption after 14 days becomes a skipped photo.
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
        // 7. Legacy frames (no frame_meta): expired once older than the Photos setting and uncited.
        for (round in 0 until MAX_ROUNDS) {
            if (s.photoDays == MemorySettings.FOREVER) break // legacy photos follow the Photos setting
            val frames = dao.orphanFramesBefore(nowMs - s.photoDays * DAY, CHUNK)
            if (frames.isEmpty()) break
            onChunk(service.expireOrphanFrames(frames)) // its own path, no cascade of any kind; the expiry marker keeps it "expired", not "forgotten"
        }
        return Summary(skipped, 0, demoted)
    }

    companion object {
        /**
         * Describe-before-delete ships (Task 9): the night job describes every due, undescribed, non-skipped photo, and a described photo is
         * deleted at its deadline + [GRACE_DAYS]. An undescribed photo is never deleted, whether or not this is on.
         */
        const val DESCRIBE_BEFORE_DELETE_READY = true
        private const val CHUNK = 200
        /** One quota round evicts at most this many photos (about 1 MB of JPEGs), so the usage never overshoots the target by more than that. */
        private const val QUOTA_CHUNK = 25
        /** The plan line counts the capped selects up to here. */
        private const val COUNT_CAP = 100_000
        const val LOG_TAG = "PcRetention"
        private const val MAX_ROUNDS = 50
        /** At most this many quota rounds per run (a bound: 400 x 25 = 10,000 photos). */
        private const val QUOTA_ROUNDS = 400
        /** SQLite binds at most 32,766 variables: the protected list is capped well below it. */
        private const val MAX_PROTECT = 20_000
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        /** Spec §P6.4: a photo described for describe-before-delete is kept this long past its deadline. */
        private const val GRACE_DAYS = 3L
        /** Photos that existed when per-kind retention started are kept this long after the stamp, whatever their kind (I2). */
        private const val PROTECT_MS = 7 * 24 * HOUR
        private const val CAPTION_QUEUE_DAYS = 14L
        /** K6: quota eviction never touches a photo younger than this. */
        const val QUOTA_MIN_AGE_DAYS = 14L
    }
}
