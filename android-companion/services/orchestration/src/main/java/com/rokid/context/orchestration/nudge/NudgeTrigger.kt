package com.rokid.context.orchestration.nudge

import android.content.Context
import com.rokid.context.core.log.PcLog
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rokid.context.ai.SafeLog

/** Called after each summarizer pass (trusted summary or reprocess): enqueues the unique work "nudge_engine" (KEEP). */
object NudgeTrigger {
    const val WORK = "nudge_engine"

    fun afterSummarizerPass(context: Context) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<NudgeWorker>().build())
        } catch (e: Exception) {
            PcLog.i("NudgeTrigger", "nudge not scheduled: ${SafeLog.describe(e)}") // never fails the summarizer pass
        }
    }
}
