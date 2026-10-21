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
import com.rokid.context.ai.embed.LocalModelPausedException
import com.rokid.context.ai.embed.LocalModelPower
import com.rokid.context.ai.embed.PowerState
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.work.budget.BackgroundWork
import com.rokid.context.work.frames.KeyframeGraph
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps the semantic index current (spec §4.6). Runs on battery-not-low; continues itself while work remains. With the
 * on-device model bound it also needs charging or battery > 70 % ([gated], Gaps G6).
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
            if (rebuild) { clock.setPendingRebuildToken(id.toString()); clock.setRebuildPending(true) }
            Log.i(TAG, "pipeline paused: index work left for resume")
            return Result.success()
        }
        val ai = AiGraph.get(applicationContext)
        val keyframes = KeyframeGraph.get(applicationContext)
        val maintenance = IndexMaintenance(
            MemoryGraph.get(applicationContext).spaces, AiIndexEmbedders(ai.embedders), clock,
            // Ruling 18: only the backfill is bulk (G6: charging or > 70 %; thermal MODERATE+ pauses); live per-frame embedding is not gated here.
            photos = keyframes::photoBytes,
            bulkAllowed = { LocalModelPower.bulkAllowed(LocalModelPower.read(applicationContext)) && keyframes.power.mayRunBackground(BackgroundWork.BACKFILL) },
        )
        return gated(applicationContext, rebuild, id.toString(), usesLocal = { ai.embedders.bindingIsLocal() }, power = { LocalModelPower.read(applicationContext) }) {
            execute(applicationContext, runAttemptCount) { maintenance.run(rebuild, rebuildToken = id.toString()) }
        }
    }

    companion object {
        private const val TAG = "EmbeddingWorker"
        /** Runs in total for one request (the first included) before a failure is final. */
        private const val MAX_RUNS = 5
        const val NAME = "embedding_index"
        const val KEY_REBUILD = "rebuild"

        /**
         * Gaps G6 (MVP spec 6): with the on-device model bound, index work runs only when charging or battery > 70 %.
         * Otherwise nothing is embedded: the run is re-queued to wait for a charger (below 70 % and not charging, the
         * battery only climbs back on a charger, so "requires charging" is exact) and the banner reason is set. A
         * Rebuild stays pending (as when paused) and is honoured by the run that follows.
         */
        internal suspend fun gated(
            context: Context,
            rebuild: Boolean,
            token: String,
            usesLocal: suspend () -> Boolean,
            power: () -> PowerState,
            run: suspend () -> Result,
        ): Result {
            val clock = PrefsIndexClock(context)
            val local = try { usesLocal() } catch (c: CancellationException) { throw c } catch (e: Exception) { false }
            if (local && !LocalModelPower.bulkAllowed(power())) {
                if (rebuild) { clock.setPendingRebuildToken(token); clock.setRebuildPending(true) }
                deferForBattery(context)
                return Result.success()
            }
            clock.setBatteryPaused(false)
            return run()
        }

        private fun deferForBattery(context: Context) {
            Log.i(TAG, "on-device index work waits for a charger (battery)")
            PrefsIndexClock(context).setBatteryPaused(true)
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(false, untilCharging = true))
        }

        /** One run's outcome as a WorkManager result. A config failure is recorded and stops; other failures retry a bounded number of times. */
        internal suspend fun execute(context: Context, attempt: Int, run: suspend () -> IndexMaintenance.Outcome): Result = try {
            when (run()) {
                IndexMaintenance.Outcome.MORE -> { continueLater(context); Result.success() }
                IndexMaintenance.Outcome.RETRY -> retryOrFail(attempt)
                else -> Result.success()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: LocalModelPausedException) {
            // The battery fell (or an on-device space was reached) mid-run: wait for a charger, never a failure.
            deferForBattery(context)
            Result.success()
        } catch (e: IndexConfigException) {
            Log.w(TAG, "index config failure; not retrying until the next trigger: ${SafeLog.describe(e)}")
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "index run failed: ${SafeLog.describe(e)}")
            retryOrFail(attempt)
        }

        private fun retryOrFail(attempt: Int): Result = if (attempt + 1 < MAX_RUNS) Result.retry() else Result.failure()
        private fun request(rebuild: Boolean, untilCharging: Boolean = false) = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setInputData(workDataOf(KEY_REBUILD to rebuild))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(untilCharging).build())
            .build()

        /** New memories, app start, binding change: KEEP (a run already queued will see them). Rebuild: REPLACE. */
        fun enqueue(context: Context, rebuild: Boolean = false) {
            // A Rebuild is durable from the moment it is asked for: a pause (which cancels this work) or a process
            // death before it runs leaves it pending, and the first run after resume honours it. It also resets the drift
            // streak now (as the run would), so "index unstable" clears at the user's request, even while paused.
            // The request exists first, so its id is the pending token (token, then flag: a reader of the flag finds the token).
            val req = request(rebuild)
            if (rebuild) PrefsIndexClock(context).apply { setPendingRebuildToken(req.id.toString()); setRebuildPending(true); recordDiscard(null) }
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (rebuild) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }

        private fun continueLater(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(false))
        }
    }
}
