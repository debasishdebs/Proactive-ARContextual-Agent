package com.rokid.context.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigReader
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class OrchestrationScheduler(
    private val context: Context,
    /** Every enqueue may run on Main (hub callback, UI): only the plain-prefs reads, never the Keystore (Task 16 carry). */
    private val config: AppConfigReader = AppConfigStore,
) {
    data class HistoricalBackfillResult(
        val queuedJobs: Int,
        val selectedFrames: Int,
        val droppedFrames: Int,
        val reason: String,
    )

    private val workManager = WorkManager.getInstance(context)

    fun scheduleFrameIngest() {
        val request = PeriodicWorkRequestBuilder<FrameIngestWorker>(periodMinutes(), TimeUnit.MINUTES)
            .addTag(TAG_FRAME_INGEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(reduced())
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            FRAME_INGEST_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleQueueDrain() {
        val request = PeriodicWorkRequestBuilder<QueueDrainWorker>(periodMinutes(), TimeUnit.MINUTES)
            .addTag(TAG_QUEUE_DRAIN)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(reduced()).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            QUEUE_DRAIN_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueQueueDrainNow() {
        val request = OneTimeWorkRequestBuilder<QueueDrainWorker>()
            .addTag(TAG_QUEUE_DRAIN)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            "queue-drain-now",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun enqueueFrameIngestNow(sourceImagePath: String = "") {
        val request = OneTimeWorkRequestBuilder<FrameIngestWorker>()
            .addTag(TAG_FRAME_INGEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(FrameIngestWorker.KEY_SOURCE_IMAGE_PATH to sourceImagePath))
            .build()
        workManager.enqueueUniqueWork(
            "frame-ingest-now",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * G10 priority reprocess after an ask found nothing: the same one-off ingest as [enqueueFrameIngestNow], but KEEP, so
     * repeated no-match asks never cancel an ingest already running. [pausePipeline] cancels it like the others.
     */
    fun enqueueFrameIngestIfIdle() {
        val request = OneTimeWorkRequestBuilder<FrameIngestWorker>()
            .addTag(TAG_FRAME_INGEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(FrameIngestWorker.KEY_SOURCE_IMAGE_PATH to ""))
            .build()
        workManager.enqueueUniqueWork("frame-ingest-now", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun enqueueSummarizer(summaryId: String, confidence: Double, createdAt: Long): UUID {
        val config = this.config.getWithoutSecret(context)
        val payload = buildString {
            append("{")
            append("\"kind\":\"write_batched\",")
            append("\"source\":\"manual\",")
            append("\"manual\":true,")
            append("\"summary_id\":\"")
            append(summaryId)
            append("\",")
            append("\"confidence\":")
            append(confidence)
            append(",\"created_at\":")
            append(createdAt)
            append("}")
        }
        QueueRuntimeStore.enqueue(
            context = context,
            queue = QueuePriority.SUMMARIZER,
            payload = payload,
            personaSnapshot = config.persona,
            ttlHours = 24,
        )

        PipelineStatsStore.incSummarizerTriggered(context)
        PipelineRecords.upsertTrace(context, summaryId, "ENQUEUED", "Queued for summarization")
        enqueueQueueDrainNow()
        return UUID.fromString(summaryId)
    }

    fun enqueueExplicitWrite(rawInput: String, inputLanguage: String = "auto"): String {
        val config = this.config.getWithoutSecret(context)
        val row = QueueRuntimeStore.enqueue(
            context = context,
            queue = QueuePriority.EXPLICIT_WRITE,
            payload = QueuePayloads.explicitWrite(rawInput = rawInput, inputLanguage = inputLanguage, manual = true),
            personaSnapshot = config.persona,
            ttlHours = 24,
        )
        enqueueQueueDrainNow()
        return row.id
    }

    fun enqueueReadContext(query: String, inputLanguage: String = "auto"): String {
        val config = this.config.getWithoutSecret(context)
        val row = QueueRuntimeStore.enqueue(
            context = context,
            queue = QueuePriority.READ,
            payload = QueuePayloads.readContext(query = query, inputLanguage = inputLanguage, manual = true),
            personaSnapshot = config.persona,
            ttlHours = 24,
        )
        enqueueQueueDrainNow()
        return row.id
    }

    fun enqueueFrameIngestQueue(source: String = "scheduled"): String {
        val config = this.config.getWithoutSecret(context)
        val row = QueueRuntimeStore.enqueue(
            context = context,
            queue = QueuePriority.FRAME_INGEST,
            payload = QueuePayloads.frameIngest(source),
            personaSnapshot = config.persona,
            ttlHours = 24,
        )
        enqueueQueueDrainNow()
        return row.id
    }

    suspend fun enqueueHistoricalPhotoBackfill(
        maxFrames: Int = 60,
        chunkSize: Int = 6,
        /** Read before the frames are chosen: a Delete all meanwhile stops the enqueue (Phase 5 T20 carry). */
        generation: Long = MemoryWipe.generation.get(),
    ): HistoricalBackfillResult {
        // Fix round 1: a run that is already stale touches nothing, not even the purges.
        if (MemoryWipe.generation.get() != generation) return HistoricalBackfillResult(0, 0, 0, DELETED_MEANWHILE)
        val config = this.config.getWithoutSecret(context)
        val purgeResult = PipelineRecords.purgeNonPhotoAndSynthetic(context)
        val selected = PipelineRecords.allFrames(context)
            .filter(PipelineRecords::isRealPhotoFrame)
            .takeLast(maxFrames.coerceAtLeast(1))
        if (selected.isEmpty()) {
            return HistoricalBackfillResult(
                queuedJobs = 0,
                selectedFrames = 0,
                droppedFrames = purgeResult.dropped,
                reason = "No real non-synthetic photos found for historical backfill. Purged ${purgeResult.dropped} legacy frame(s).",
            )
        }

        PipelineRecords.purgeLowSignalMemories(context)
        // Final review I3: what the purge emptied is rebuilt. Frames left with no memory go back to PENDING (the keyframe pipeline's
        // states; the legacy jobs below also claim them, manual runs ignoring the grace), instead of staying EXTRACTED with nothing.
        val requeued = PipelineRecords.requeueFramesWithoutMemories(context, selected.map { it.id })
        if (requeued > 0) runCatching { com.rokid.context.work.frames.KeyframeGraph.get(context).signal() }

        val safeChunkSize = chunkSize.coerceIn(1, 12)
        val batches = selected.chunked(safeChunkSize)
        var queued = 0
        for ((index, batch) in batches.withIndex()) {
            val summaryId = UUID.randomUUID().toString()
            val payload = QueuePayloads.batchedWrite(
                source = "historical_backfill",
                importedFrames = batch.size,
                manual = true,
                frameIdsCsv = batch.joinToString(",") { it.id },
            )
            QueueRuntimeStore.enqueueIfCurrent(context, generation, QueuePriority.SUMMARIZER, payload, config.persona, ttlHours = 24) ?: break
            queued++
            PipelineStatsStore.incSummarizerTriggered(context)
            PipelineRecords.upsertTrace(context, summaryId, "ENQUEUED", "Historical backfill chunk ${index + 1}/${batches.size}")
        }
        if (queued == 0) {
            return HistoricalBackfillResult(0, selected.size, purgeResult.dropped, DELETED_MEANWHILE)
        }
        enqueueQueueDrainNow()
        return HistoricalBackfillResult(
            queuedJobs = queued,
            selectedFrames = selected.size,
            droppedFrames = purgeResult.dropped,
            reason = "Historical backfill queued. Purged ${purgeResult.dropped} legacy frame(s), deleted ${purgeResult.deletedFiles} stale image file(s).",
        )
    }

    /**
     * Retention (Phase 7): one daily unique job, KEEP. Called from [scheduleCritic], the existing ensure-periodic path
     * (app start and resume). [pausePipeline] never cancels it: retention only removes data.
     */
    fun ensureRetention() {
        val request = PeriodicWorkRequestBuilder<com.rokid.context.orchestration.forget.RetentionWorker>(24, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(com.rokid.context.orchestration.forget.RetentionWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** The Moment cadence: every 15 minutes (WorkManager's minimum) the current and the previous hour of stored Moments are rebuilt (see `MomentCadenceWorker`). Unique, KEEP; it only rebuilds a projection, so a pause does not cancel it. */
    fun ensureMomentCadence(policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
        val request = PeriodicWorkRequestBuilder<com.rokid.context.canvas.moment.MomentCadenceWorker>(periodMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(reduced()).build())
            .build()
        workManager.enqueueUniquePeriodicWork(com.rokid.context.canvas.moment.MomentCadenceWorker.NAME, policy, request)
    }

    // v1.1 T7: the reduced cadence. While the hub is stopped on purpose with nothing waiting, the three 15-minute jobs (frame ingest, queue drain, Moment cadence) run every
    // 60 minutes and only with the battery not low (user ruling 2026-11-16: background jobs keep running under battery guards, nothing is cancelled). The flag is persisted so a
    // process start (ensureBackgroundWork, MainActivity) does not put the 15-minute period back.
    private fun reduced(): Boolean = cadenceReduced(context)
    private fun periodMinutes(): Long = if (reduced()) REDUCED_PERIOD_MIN else NORMAL_PERIOD_MIN

    /** True while the periodic jobs run at the reduced cadence. */
    fun periodicReduced(): Boolean = reduced()

    /**
     * An EXPECTED stop of the hub (auto, manual or watchdog) with an empty inbox and an empty queue AND the Moments caught up: the periodic jobs fall back to 60 minutes, battery guarded.
     * Anything waiting keeps the normal cadence (it still has to drain). A LOST hub never calls this (its jobs are its recovery chances). Reads files and prefs: not on Main. True = reduced.
     *
     * Review hub-wp3 r1: [epoch] is [cadenceEpoch] as it was when the stop happened; any [restorePeriodicJobs] since (a hub start, a stored frame) bumps it, and the write below is under
     * the same lock as the restore, so a late reduce can never undo a restoration. [momentsCaughtUp] (default: [momentsCaughtUpNow]) is a durable catch-up, not just empty queues: a failed
     * Moment build is recovered only by the cadence's current-and-previous-hour rebuild, which a longer period could let expire.
     */
    fun reducePeriodicJobsIfIdle(epoch: Long = cadenceEpoch(), momentsCaughtUp: () -> Boolean = ::momentsCaughtUpNow): Boolean {
        if (reduced()) return true
        val inboxEmpty = RokidFrameInbox.inboxDir(context).listFiles().orEmpty().none { it.isFile && !it.name.endsWith(".part") }
        if (!inboxEmpty || QueueRuntimeStore.snapshot(context).totalPending > 0) return false
        if (!momentsCaughtUp()) return false
        synchronized(CADENCE_LOCK) {
            if (cadenceEpoch() != epoch || reduced()) return reduced() // restored (or reduced) meanwhile: this stale decision is void
            context.getSharedPreferences(CADENCE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REDUCED, true).commit()
            reschedulePeriodic()
        }
        return true
    }

    /** Drains the trigger's pending seconds and rebuilds the current and previous hour now; false when anything failed or does not finish in time (then the cadence stays normal). */
    private fun momentsCaughtUpNow(): Boolean = runCatching {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(CATCH_UP_TIMEOUT_MS) {
                val t = com.rokid.context.canvas.moment.MomentTriggers.get(context)
                val before = t.failures
                t.flush(); t.rebuildRecent(System.currentTimeMillis())
                t.failures == before
            } == true
        }
    }.getOrDefault(false)

    /** The hub started, or a frame was stored: the normal 15-minute cadence again. A no-op (one prefs read) when not reduced, but it always voids any reduce decided before it. */
    fun restorePeriodicJobs() {
        cadenceEpochCounter.incrementAndGet()
        synchronized(CADENCE_LOCK) {
            if (!reduced()) return
            context.getSharedPreferences(CADENCE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REDUCED, false).commit()
            reschedulePeriodic()
        }
    }

    /** Re-enqueues the three jobs with the period of the current mode; the ingest and the drain only while processing is on (paused = cancelled, stays so). */
    private fun reschedulePeriodic() {
        if (config.isPipelineEnabled(context)) { runCatching { scheduleFrameIngest() }; runCatching { scheduleQueueDrain() } }
        runCatching { ensureMomentCadence(ExistingPeriodicWorkPolicy.UPDATE) }
    }

    /**
     * The night job (Task 9): one unique periodic 24 h job, KEEP, that needs a charger and an idle device. It describes the photos about to
     * expire (BACKFILL calls), then runs the index backfill. Unlike retention it makes AI calls, so [pausePipeline] cancels it.
     */
    fun ensureNightIndex() {
        val request = PeriodicWorkRequestBuilder<com.rokid.context.orchestration.forget.NightIndexWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()) // WorkManager forbids a backoff on an idle-mode job
            .build()
        workManager.enqueueUniquePeriodicWork(com.rokid.context.orchestration.forget.NightIndexWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        // 2026-11-08: the catch-up for missed nights (charger + battery not low, no idle constraint); it no-ops unless photos wait and the night run is overdue.
        val catchUp = PeriodicWorkRequestBuilder<com.rokid.context.orchestration.forget.NightIndexWorker>(6, TimeUnit.HOURS)
            .setInputData(androidx.work.workDataOf(com.rokid.context.orchestration.forget.NightIndexWorker.KEY_CATCH_UP to true))
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(com.rokid.context.orchestration.forget.NightIndexWorker.CATCH_UP_NAME, ExistingPeriodicWorkPolicy.KEEP, catchUp)
    }

    /**
     * The jobs that must run whatever the pipeline state (they only remove data or rebuild a projection and never make an AI call): retention and the Moment cadence. Called from every start path
     * (the process start, every launch, a link-up) and from [scheduleCritic]; each is unique + KEEP, so repeated calls are free. (The cadence used to be reached only through [scheduleCritic],
     * which MainActivity calls only while processing is on: with processing off it was never scheduled and a mark recorded by an older build never got its Moment.)
     */
    fun ensureBackgroundWork() {
        runCatching { ensureRetention() }
        runCatching { ensureMomentCadence() }
        runCatching { com.rokid.context.work.frames.place.PlaceRecovery.ensureIfPending(context) } // a place memory left hidden by a failed take-back is deleted without waiting for a frame
    }

    fun scheduleCritic() {
        ensureBackgroundWork()
        ensureNightIndex()
        val request = PeriodicWorkRequestBuilder<CriticWorkerJob>(3, TimeUnit.HOURS)
            .addTag(TAG_CRITIC)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            CRITIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun pausePipeline() {
        workManager.cancelUniqueWork("frame-ingest-now")
        workManager.cancelUniqueWork(FRAME_INGEST_WORK)
        workManager.cancelUniqueWork(CRITIC_WORK)
        workManager.cancelUniqueWork(QUEUE_DRAIN_WORK)
        // Paused: no embedding calls either. A requested Rebuild is already persisted as pending (EmbeddingWorker.enqueue).
        workManager.cancelUniqueWork(com.rokid.context.orchestration.EmbeddingWorker.NAME)
        workManager.cancelUniqueWork(com.rokid.context.orchestration.forget.NightIndexWorker.NAME) // it makes AI calls; resume re-ensures it
        workManager.cancelUniqueWork(com.rokid.context.orchestration.forget.NightIndexWorker.CATCH_UP_NAME)
        workManager.cancelAllWorkByTag(TAG_SUMMARIZER)
        workManager.cancelAllWorkByTag(TAG_FRAME_INGEST)
        workManager.cancelAllWorkByTag(TAG_CRITIC)
        workManager.cancelAllWorkByTag(TAG_QUEUE_DRAIN)
    }

    /** Frames that waited in the inbox while paused are ingested by the FRAME_INGEST job, which also kicks the drain. */
    fun resumePipeline() {
        scheduleFrameIngest()
        scheduleQueueDrain()
        scheduleCritic()
        enqueueFrameIngestQueue(source = "resume")
        com.rokid.context.orchestration.EmbeddingWorker.enqueue(context) // index backlog and any Rebuild left pending by the pause
    }

    suspend fun runtimeSnapshot(focusSummarizerId: UUID?): RuntimeSnapshot = withContext(Dispatchers.IO) {
        val frameInfos = workManager.getWorkInfosByTag(TAG_FRAME_INGEST).get()
        val criticInfos = workManager.getWorkInfosByTag(TAG_CRITIC).get()
        val queueInfos = workManager.getWorkInfosByTag(TAG_QUEUE_DRAIN).get()
        val queueSnapshot = QueueRuntimeStore.snapshot(context)

        val traces = PipelineRecords.recentTraces(context, 300)
        val focusId = focusSummarizerId?.toString().orEmpty()
        val focusState = if (focusId.isNotBlank()) traces.firstOrNull { it.summaryId == focusId }?.state ?: "IDLE" else "IDLE"

        RuntimeSnapshot(
            summarizerEnqueued = queueSnapshot.pendingByQueue[QueuePriority.SUMMARIZER] ?: 0,
            summarizerRunning = queueSnapshot.runningByQueue[QueuePriority.SUMMARIZER] ?: 0,
            summarizerSucceeded = traces.count { it.state in setOf("TRUSTED", "REPROCESSED", "WRITE_CONTEXT_OK") },
            summarizerFailed = traces.count { it.state in setOf("FAILED", "NEEDS_REVIEW", "RETRY", "DEFERRED") },
            frameIngestRunning = frameInfos.any { it.state == WorkInfo.State.RUNNING } ||
                ((queueSnapshot.runningByQueue[QueuePriority.FRAME_INGEST] ?: 0) > 0),
            criticRunning = criticInfos.any { it.state == WorkInfo.State.RUNNING },
            queueDrainRunning = queueInfos.any { it.state == WorkInfo.State.RUNNING },
            focusSummarizerState = focusState,
            queueSnapshot = queueSnapshot,
        )
    }

    data class RuntimeSnapshot(
        val summarizerEnqueued: Int,
        val summarizerRunning: Int,
        val summarizerSucceeded: Int,
        val summarizerFailed: Int,
        val frameIngestRunning: Boolean,
        val criticRunning: Boolean,
        val queueDrainRunning: Boolean,
        val focusSummarizerState: String,
        val queueSnapshot: com.rokid.context.data.runtime.QueueRuntimeSnapshot,
    ) {
        val pipelineLive: Boolean
            get() = frameIngestRunning || criticRunning || queueDrainRunning || summarizerRunning > 0 || summarizerEnqueued > 0
    }

    companion object {
        /** The reduced-cadence flag (one cached prefs read): the cheap check before any restore work. */
        fun cadenceReduced(context: Context): Boolean = context.getSharedPreferences(CADENCE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REDUCED, false)
        const val CADENCE_PREFS = "periodic_cadence"
        private val CADENCE_LOCK = Any()
        private val cadenceEpochCounter = java.util.concurrent.atomic.AtomicLong()
        private const val CATCH_UP_TIMEOUT_MS = 60_000L
        /** Counts the restorations of the normal cadence (also those that found nothing to restore): a stop captures it, a reduce is valid only while it is unchanged. */
        fun cadenceEpoch(): Long = cadenceEpochCounter.get()
        /** A restoration was requested (the cheap, synchronous part of [OrchestrationScheduler.restorePeriodicJobs]). */
        fun noteCadenceRestore() { cadenceEpochCounter.incrementAndGet() }
        private const val KEY_REDUCED = "reduced"
        const val NORMAL_PERIOD_MIN = 15L
        const val REDUCED_PERIOD_MIN = 60L
        private const val DELETED_MEANWHILE = "Memory was deleted meanwhile; nothing was queued."
        const val FRAME_INGEST_WORK = "frame_ingest_work"
        const val QUEUE_DRAIN_WORK = "queue_drain_work"
        const val CRITIC_WORK = "critic_work"
        const val TAG_SUMMARIZER = "pca_summarizer"
        const val TAG_FRAME_INGEST = "pca_frame_ingest"
        const val TAG_QUEUE_DRAIN = "pca_queue_drain"
        const val TAG_CRITIC = "pca_critic"
    }
}
