package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.SafeLog
import com.rokid.context.memory.MemoryGraph
import kotlin.coroutines.cancellation.CancellationException

/** Keeps the semantic index current (spec §4.6). Runs on battery-not-low; continues itself while work remains. */
class EmbeddingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val maintenance = IndexMaintenance(MemoryGraph.get(applicationContext).spaces, AiIndexEmbedders(AiGraph.get(applicationContext).embedders), PrefsIndexClock(applicationContext))
        val rebuild = inputData.getBoolean(KEY_REBUILD, false)
        return execute(applicationContext, runAttemptCount) { maintenance.run(rebuild) }
    }

    companion object {
        private const val TAG = "EmbeddingWorker"
        private const val MAX_ATTEMPTS = 5
        const val NAME = "embedding_index"
        const val KEY_REBUILD = "rebuild"

        /** One run's outcome as a WorkManager result. A config failure is recorded and stops; other failures retry a bounded number of times. */
        internal suspend fun execute(context: Context, attempt: Int, run: suspend () -> IndexMaintenance.Outcome): Result = try {
            when (run()) {
                IndexMaintenance.Outcome.MORE -> { continueLater(context); Result.success() }
                else -> Result.success()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: IndexConfigException) {
            Log.w(TAG, "index config failure; not retrying until the next trigger: ${SafeLog.describe(e)}")
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "index run failed: ${SafeLog.describe(e)}")
            if (attempt < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }

        private fun request(rebuild: Boolean) = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setInputData(workDataOf(KEY_REBUILD to rebuild))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()

        /** New memories, app start, binding change: KEEP (a run already queued will see them). Rebuild: REPLACE. */
        fun enqueue(context: Context, rebuild: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (rebuild) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(rebuild))
        }

        private fun continueLater(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(false))
        }
    }
}
