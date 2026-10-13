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
        val request = PeriodicWorkRequestBuilder<FrameIngestWorker>(15, TimeUnit.MINUTES)
            .addTag(TAG_FRAME_INGEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
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
        val request = PeriodicWorkRequestBuilder<QueueDrainWorker>(15, TimeUnit.MINUTES)
            .addTag(TAG_QUEUE_DRAIN)
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

    fun scheduleCritic() {
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
