package com.rokid.context.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.data.runtime.MemoryRecordStore
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.data.runtime.SummaryTrace
import com.rokid.context.data.runtime.SummaryTraceStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.domain.summary.SummaryStatus
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptHash
import com.rokid.context.prompt.PromptTemplateId
import com.rokid.context.prompt.PromptTemplateRepository

class CriticWorkerJob(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val criticWorker = CriticWorker()

    override suspend fun doWork(): Result {
        val summaryId = inputData.getString(KEY_SUMMARY_ID)
        if (summaryId.isNullOrBlank()) {
            // Periodic run: scan review records and ensure reprocess queue exists.
            val reviews = MemoryRecordStore.recent(applicationContext, 80)
                .filter { it.memoryType.contains("review", ignoreCase = true) }
            if (reviews.isNotEmpty()) {
                val payload = QueuePayloads.reprocess(
                    reason = "Periodic critic retry for review memories",
                    sourceContext = reviews.take(5).joinToString("\n") { it.content },
                )
                QueueRuntimeStore.enqueue(
                    context = applicationContext,
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
        val sourceContext = MemoryRecordStore.recent(applicationContext, 20)
            .joinToString("\n") { it.content }
            .ifBlank { "no-context" }
        val prompt = PromptComposer(PromptTemplateRepository(applicationContext)).compose(
            templateId = PromptTemplateId.CRITIC_VALIDATION,
            variables = mapOf(
                "summary_id" to summaryId,
                "summary" to summaryId,
                "source_context" to sourceContext,
            ),
        )
        Log.i(TAG, "critic_validation promptHash=${PromptHash.sha256(prompt)}")
        val result = criticWorker.validate(summaryId, confidence)

        return when (result.status) {
            SummaryStatus.TRUSTED -> {
                SummaryTraceStore.upsert(
                    applicationContext,
                    SummaryTrace(
                        summaryId = summaryId,
                        state = "TRUSTED",
                        updatedAtEpochMs = System.currentTimeMillis(),
                        note = "Critic approved summary",
                    ),
                )
                Result.success()
            }

            SummaryStatus.NEEDS_REVIEW -> {
                SummaryTraceStore.upsert(
                    applicationContext,
                    SummaryTrace(
                        summaryId = summaryId,
                        state = "NEEDS_REVIEW",
                        updatedAtEpochMs = System.currentTimeMillis(),
                        note = "Critic flagged low confidence",
                    ),
                )
                PipelineStatsStore.incSummarizerFailed(applicationContext)
                QueueRuntimeStore.enqueue(
                    context = applicationContext,
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
        private const val TAG = "CriticWorkerJob"
        const val KEY_SUMMARY_ID = "summary_id"
        const val KEY_CONFIDENCE = "confidence"
    }
}
