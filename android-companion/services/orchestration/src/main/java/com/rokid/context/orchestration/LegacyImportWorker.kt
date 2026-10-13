package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rokid.context.memory.MemoryGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LegacyImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    /**
     * P8 D5: a failed import retries ([Result.retry]) until the [MAX_RUNS]th run, then fails; only the class is logged.
     * The embedding pass is enqueued on success only.
     */
    override suspend fun doWork(): Result {
        val report = try {
            withContext(Dispatchers.IO) { MemoryGraph.importLegacyForWorker(applicationContext) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "legacy import failed (run ${runAttemptCount + 1}/$MAX_RUNS): ${e.javaClass.simpleName}")
            return if (runAttemptCount < MAX_RUNS - 1) Result.retry() else Result.failure()
        }
        if (report != null) Log.i(TAG, "legacy import $report")
        EmbeddingWorker.enqueue(applicationContext) // imported memories are embedded in the background (spec §6.2)
        return Result.success()
    }

    companion object {
        private const val TAG = "LegacyImportWorker"
        const val NAME = "legacy_import_v1"
        const val MAX_RUNS = 3
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<LegacyImportWorker>().build())
        }
    }
}
