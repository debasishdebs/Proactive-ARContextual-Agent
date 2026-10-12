package com.rokid.context.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.data.runtime.SummaryTrace
import com.rokid.context.data.runtime.SummaryTraceStore
import com.rokid.context.domain.queue.QueuePriority

/**
 * Legacy entrypoint kept for compatibility.
 * Real processing is delegated to QueueDrainWorker so queue state remains authoritative.
 */
class SummarizerWorkerJob(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val summaryId = inputData.getString(KEY_SUMMARY_ID) ?: return Result.failure()
        val confidence = inputData.getDouble(KEY_CONFIDENCE, 0.8)

        SummaryTraceStore.upsert(
            applicationContext,
            SummaryTrace(
                summaryId = summaryId,
                state = "ENQUEUED",
                updatedAtEpochMs = System.currentTimeMillis(),
                note = "Queued for queue-drain summarization",
            ),
        )

        val config = AppConfigStore.get(applicationContext)
        QueueRuntimeStore.enqueue(
            context = applicationContext,
            queue = QueuePriority.SUMMARIZER,
            payload = buildString {
                append("{")
                append("\"kind\":\"write_batched\",")
                append("\"source\":\"legacy_worker\",")
                append("\"manual\":true,")
                append("\"summary_id\":\"")
                append(summaryId)
                append("\",")
                append("\"confidence\":")
                append(confidence)
                append("}")
            },
            personaSnapshot = config.persona,
            ttlHours = 24,
        )

        PipelineStatsStore.incSummarizerTriggered(applicationContext)
        OrchestrationScheduler(applicationContext).enqueueQueueDrainNow()
        return Result.success()
    }

    companion object {
        const val KEY_SUMMARY_ID = "summary_id"
        const val KEY_CONFIDENCE = "confidence"
        const val KEY_RAW_FRAME_CREATED_AT = "raw_frame_created_at"
    }
}
