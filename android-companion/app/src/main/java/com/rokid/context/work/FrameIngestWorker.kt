package com.rokid.context.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class FrameIngestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val pressureBlocked = inputData.getBoolean(KEY_PRESSURE_BLOCKED, false)
        return if (pressureBlocked) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_PRESSURE_BLOCKED = "pressure_blocked"
    }
}
