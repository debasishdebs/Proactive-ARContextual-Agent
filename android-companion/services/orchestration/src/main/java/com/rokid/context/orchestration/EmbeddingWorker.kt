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
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps the semantic index current (spec §4.6). Runs on battery-not-low; continues itself while work remains.
 *
 * No network constraint on purpose: the EMBED binding may be a localhost (Ollama on the device) or on-device llama.cpp
 * endpoint that works offline. An unreachable remote endpoint fails the run, which retries with backoff, capped.
 *
 * Paused (phone ⏸): nothing goes to the AI provider, not even the binding probe. A Rebuild asked for while paused is
 * kept pending and honoured after resume (`OrchestrationScheduler.resumePipeline` enqueues this worker).
 */
class EmbeddingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val rebuild = inputData.getBoolean(KEY_REBUILD, false)
        val clock = PrefsIndexClock(applicationContext)
        if (!AppConfigStore.isPipelineEnabled(applicationContext)) {
            if (rebuild) clock.setRebuildPending(true)
            Log.i(TAG, "pipeline paused: index work left for resume")
            return Result.success()
        }
        val maintenance = IndexMaintenance(MemoryGraph.get(applicationContext).spaces, AiIndexEmbedders(AiGraph.get(applicationContext).embedders), clock)
        return execute(applicationContext, runAttemptCount) { maintenance.run(rebuild, rebuildToken = id.toString()) }
    }

    companion object {
        private const val TAG = "EmbeddingWorker"
        /** Runs in total for one request (the first included) before a failure is final. */
        private const val MAX_RUNS = 5
        const val NAME = "embedding_index"
        const val KEY_REBUILD = "rebuild"

        /** One run's outcome as a WorkManager result. A config failure is recorded and stops; other failures retry a bounded number of times. */
        internal suspend fun execute(context: Context, attempt: Int, run: suspend () -> IndexMaintenance.Outcome): Result = try {
            when (run()) {
                IndexMaintenance.Outcome.MORE -> { continueLater(context); Result.success() }
                IndexMaintenance.Outcome.RETRY -> retryOrFail(attempt)
                else -> Result.success()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: IndexConfigException) {
            Log.w(TAG, "index config failure; not retrying until the next trigger: ${SafeLog.describe(e)}")
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "index run failed: ${SafeLog.describe(e)}")
            retryOrFail(attempt)
        }

        private fun retryOrFail(attempt: Int): Result = if (attempt + 1 < MAX_RUNS) Result.retry() else Result.failure()
        private fun request(rebuild: Boolean) = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setInputData(workDataOf(KEY_REBUILD to rebuild))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()

        /** New memories, app start, binding change: KEEP (a run already queued will see them). Rebuild: REPLACE. */
        fun enqueue(context: Context, rebuild: Boolean = false) {
            // A Rebuild is durable from the moment it is asked for: a pause (which cancels this work) or a process
            // death before it runs leaves it pending, and the first run after resume honours it. It also resets the drift
            // streak now (as the run would), so "index unstable" clears at the user's request, even while paused.
            if (rebuild) PrefsIndexClock(context).apply { setRebuildPending(true); recordDiscard(null) }
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (rebuild) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(rebuild))
        }

        private fun continueLater(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(false))
        }
    }
}
