package com.rokid.context.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class OrchestrationScheduler(
    private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun scheduleFrameIngest() {
        val request = PeriodicWorkRequestBuilder<FrameIngestWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
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

    fun enqueueSummarizer(summaryId: String, confidence: Double, createdAt: Long) {
        val request = OneTimeWorkRequestBuilder<SummarizerWorkerJob>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                androidx.work.workDataOf(
                    SummarizerWorkerJob.KEY_SUMMARY_ID to summaryId,
                    SummarizerWorkerJob.KEY_CONFIDENCE to confidence,
                    SummarizerWorkerJob.KEY_RAW_FRAME_CREATED_AT to createdAt,
                ),
            )
            .build()

        workManager.enqueueUniqueWork(
            "summarizer-$summaryId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleCritic() {
        val request = PeriodicWorkRequestBuilder<CriticWorkerJob>(3, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            CRITIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val FRAME_INGEST_WORK = "frame_ingest_work"
        const val CRITIC_WORK = "critic_work"
    }
}
