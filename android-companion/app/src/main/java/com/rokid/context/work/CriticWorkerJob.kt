package com.rokid.context.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.domain.summary.SummaryStatus

class CriticWorkerJob(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val criticWorker = CriticWorker()

    override suspend fun doWork(): Result {
        val summaryId = inputData.getString(KEY_SUMMARY_ID) ?: return Result.failure()
        val confidence = inputData.getDouble(KEY_CONFIDENCE, 0.0)
        val result = criticWorker.validate(summaryId, confidence)

        return when (result.status) {
            SummaryStatus.TRUSTED -> Result.success()
            SummaryStatus.NEEDS_REVIEW -> Result.retry()
        }
    }

    companion object {
        const val KEY_SUMMARY_ID = "summary_id"
        const val KEY_CONFIDENCE = "confidence"
    }
}
