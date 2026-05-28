package com.rokid.context.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.domain.summary.SummarizerWorker

class SummarizerWorkerJob(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val criticWorker = CriticWorker()
    private val summarizerWorker = SummarizerWorker()

    override suspend fun doWork(): Result {
        val summaryId = inputData.getString(KEY_SUMMARY_ID) ?: return Result.failure()
        val confidence = inputData.getDouble(KEY_CONFIDENCE, 1.0)
        val createdAt = inputData.getLong(KEY_RAW_FRAME_CREATED_AT, 0L)

        val critic = criticWorker.validate(summaryId, confidence)
        val now = System.currentTimeMillis()
        val purgeRaw = summarizerWorker.shouldDeleteRawFrame(
            rawFrameCreatedAtMs = createdAt,
            nowMs = now,
            criticValidated = critic.status == com.rokid.context.domain.summary.SummaryStatus.TRUSTED,
        )

        return if (purgeRaw) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_SUMMARY_ID = "summary_id"
        const val KEY_CONFIDENCE = "confidence"
        const val KEY_RAW_FRAME_CREATED_AT = "raw_frame_created_at"
    }
}
