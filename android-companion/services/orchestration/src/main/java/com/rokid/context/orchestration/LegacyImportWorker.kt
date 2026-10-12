package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.legacy.LegacyImporter

class LegacyImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val importer = LegacyImporter(applicationContext, MemoryGraph.get(applicationContext))
        if (importer.isDone()) return Result.success()
        val report = importer.importAll()
        Log.i(TAG, "legacy import $report")
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
