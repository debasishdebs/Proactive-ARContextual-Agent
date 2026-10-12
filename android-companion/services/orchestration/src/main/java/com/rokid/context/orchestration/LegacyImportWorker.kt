package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rokid.context.memory.MemoryGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LegacyImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val report = withContext(Dispatchers.IO) { MemoryGraph.ensureLegacyImported(applicationContext) }
        if (report != null) Log.i(TAG, "legacy import $report")
        return Result.success()
    }

    companion object {
        private const val TAG = "LegacyImportWorker"
        const val NAME = "legacy_import_v1"
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<LegacyImportWorker>().build())
        }
    }
}
