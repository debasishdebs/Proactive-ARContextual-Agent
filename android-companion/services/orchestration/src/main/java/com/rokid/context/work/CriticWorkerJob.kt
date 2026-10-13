package com.rokid.context.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.domain.summary.SummaryStatus
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords

class CriticWorkerJob(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val criticWorker = CriticWorker()

    override suspend fun doWork(): Result {
        val summaryId = inputData.getString(KEY_SUMMARY_ID)
        if (summaryId.isNullOrBlank()) {
            // Periodic run: scan facts awaiting review (preflight F10: by reviewState; facts keep their own type) and queue a reprocess.
            // Fix M4: skip facts a still-pending reprocess already covers (same text, or a frame line for the same frame).
            // Fix round 2: this stays a substring match on purpose. A PRIORITY_REPROCESS payload (QueuePayloads.reprocess)
            // carries only `reason` and `source_context` text, never memory or frame ids, and every producer (this job,
            // QueueDrainWorker, AskActions) writes it that way, so the text is the only key a pending job exposes.
            val generation = MemoryWipe.generation.get() // before the memory read (fix round 2 N2)
            val pending = QueueRuntimeStore.live(applicationContext, QueuePriority.PRIORITY_REPROCESS)
                .map { QueuePayloads.string(QueuePayloads.parse(it.payload), "source_context") }
            val reviews = PipelineRecords.recentMemories(applicationContext, 80)
                .filter { it.reviewState == ReviewState.NEEDS_REVIEW }
                .filter { m -> pending.none { c -> m.text in c || (m.frameId != null && "id=${m.frameId} ·" in c) } }
            if (reviews.isNotEmpty()) {
                val payload = QueuePayloads.reprocess(
                    reason = "Periodic critic retry for review memories",
                    sourceContext = reviews.take(5).joinToString("\n") { it.text },
                )
                QueueRuntimeStore.enqueueIfCurrent(
                    context = applicationContext,
                    generation = generation,
                    queue = QueuePriority.PRIORITY_REPROCESS,
                    payload = payload,
                    personaSnapshot = "Global Default",
                    ttlHours = 24,
                )
                OrchestrationScheduler(applicationContext).enqueueQueueDrainNow()
            }
            return Result.success()
        }

        val confidence = inputData.getDouble(KEY_CONFIDENCE, 0.0)
        val generation = MemoryWipe.generation.get() // before the memory read (fix round 2 N2)
        val sourceContext = PipelineRecords.recentMemories(applicationContext, 20).joinToString("\n") { it.text }.ifBlank { "no-context" }
        val result = criticWorker.validate(summaryId, confidence)

        return when (result.status) {
            SummaryStatus.TRUSTED -> {
                PipelineRecords.upsertTrace(applicationContext, summaryId, "TRUSTED", "Critic approved summary")
                Result.success()
            }

            SummaryStatus.NEEDS_REVIEW -> {
                PipelineRecords.upsertTrace(applicationContext, summaryId, "NEEDS_REVIEW", "Critic flagged low confidence")
                PipelineStatsStore.incSummarizerFailed(applicationContext)
                QueueRuntimeStore.enqueueIfCurrent(
                    context = applicationContext,
                    generation = generation,
                    queue = QueuePriority.PRIORITY_REPROCESS,
                    payload = QueuePayloads.reprocess(
                        reason = "critic low confidence",
                        sourceContext = sourceContext,
                    ),
                    personaSnapshot = "Global Default",
                    ttlHours = 24,
                )
                OrchestrationScheduler(applicationContext).enqueueQueueDrainNow()
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_SUMMARY_ID = "summary_id"
        const val KEY_CONFIDENCE = "confidence"
    }
}
