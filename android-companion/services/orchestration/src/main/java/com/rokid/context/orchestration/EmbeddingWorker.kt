package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.core.log.PcLog
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
            PcLog.i(TAG, "pipeline paused: index work left for resume")
            if (IndexLogShared.state.pause("pipeline", "phone pipeline is paused")) PcLog.milestone(TAG, "index paused (pipeline): the phone pause is on; index work waits for resume (rebuild requested=$rebuild)")
            return Result.success()
        }
        IndexLogShared.state.resume("pipeline")?.let { (_, ms) ->
            val waited = IndexLogFormat.duration(ms)
            PcLog.milestone(TAG, "index resumed (pipeline): the phone pause is off; was paused for $waited")
        }
        val ai = AiGraph.get(applicationContext)
        val keyframes = KeyframeGraph.get(applicationContext)
        val gate = BulkGate({ LocalModelPower.read(applicationContext) }, { keyframes.power.mayRunBackground(BackgroundWork.BACKFILL) })
        val maintenance = IndexMaintenance(
            MemoryGraph.get(applicationContext).spaces, AiIndexEmbedders(ai.embedders), clock,
            // Ruling 18: only the backfill is bulk (G6: charging or > 70 %; thermal MODERATE+ pauses); live per-frame embedding is not gated here.
            photos = keyframes::photoReads,
            readAttempts = PrefsReadAttempts(applicationContext),
            bulkAllowed = gate::allowed,
            bulkReason = gate::reason, // log only: what `allowed` just saw, never a second read
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
        /** The battery wait lives under its own unique name: saves keep coalescing under [NAME] and a queued Rebuild is never replaced by a wait. */
        const val NAME_WAIT = "embedding_index_battery_wait"
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
            if (local) {
                val p = power() // read once: the decision and its logged reason come from the same value
                if (!LocalModelPower.bulkAllowed(p)) {
                    if (rebuild) { clock.setPendingRebuildToken(token); clock.setRebuildPending(true) }
                    deferForBattery(context, BulkGateReason.of(p, null))
                    return Result.success()
                }
            }
            logBatteryResume(clock, local)
            clock.setBatteryPaused(false)
            return run()
        }

        /** Log only: a failure here must never reach the control flow. */
        private fun logBatteryResume(clock: PrefsIndexClock, local: Boolean) {
            try {
                if (!clock.batteryPaused()) return
                val gate = if (local) "charging or battery above ${LocalModelPower.MIN_BATTERY_PCT}%" else "the on-device model is no longer bound"
                PcLog.milestone(TAG, "index resumed (battery): $gate; the run proceeds")
            } catch (_: Exception) { }
        }

        private fun deferForBattery(context: Context, reason: String) {
            PcLog.i(TAG, "on-device index work waits for a charger (battery)")
            val clock = PrefsIndexClock(context)
            try {
                if (!clock.batteryPaused()) {
                    val pendingRebuild = clock.rebuildPending()
                    PcLog.milestone(TAG, "index paused (battery): $reason; the run waits for a charger and resumes by itself (pending rebuild=$pendingRebuild)")
                }
            } catch (_: Exception) { }
            clock.setBatteryPaused(true)
            // 2026-11-16: re-check after a delay instead of waiting for the system CHARGING constraint. Some phones never report "stable power"
            // on a charger (the job then never ran although the battery service said charging), and the battery level can also rise above the
            // user's setting without a charger. The next run reads the power again and either proceeds or defers once more.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME_WAIT, ExistingWorkPolicy.APPEND_OR_REPLACE, request(false, recheckAfterMs = BATTERY_RECHECK_MS))
        }

        /** One run's outcome as a WorkManager result. A config failure is recorded and stops; other failures retry a bounded number of times. */
        internal suspend fun execute(context: Context, attempt: Int, run: suspend () -> IndexMaintenance.Outcome): Result = try {
            val outcome = run()
            if (outcome != IndexMaintenance.Outcome.BACKFILL) PrefsIndexClock(context).setMoreStreak(0)
            when (outcome) {
                // A long text re-index keeps going at once; only image-vector backfill (paused, deferred photos) waits with a growing delay.
                IndexMaintenance.Outcome.MORE -> { continueLater(context, delayMs = 0L); Result.success() }
                IndexMaintenance.Outcome.BACKFILL -> {
                    val clock = PrefsIndexClock(context)
                    val streak = clock.moreStreak(); clock.setMoreStreak(streak + 1)
                    val delayMs = moreDelayMs(streak); val waitS = delayMs / 1000; val consecutive = streak + 1
                    PcLog.i(TAG, "index run: photo backfill left (paused, deferred or waiting to swap); next run in $waitS s (consecutive=$consecutive)")
                    continueLater(context, delayMs); Result.success()
                }
                IndexMaintenance.Outcome.RETRY -> { val attemptNo = attempt + 1; PcLog.i(TAG, "index run: pre-swap check could not run; attempt $attemptNo of $MAX_RUNS"); retryOrFail(attempt) }
                else -> Result.success()
            }
        } catch (c: CancellationException) {
            PcLog.milestone(TAG, "index run stopped before it finished (cancelled: replaced by a newer request, constraints lost or the app stopped); the next run continues")
            throw c
        } catch (e: LocalModelPausedException) {
            // The battery fell (or an on-device space was reached) mid-run: wait for a charger, never a failure.
            deferForBattery(context, "battery fell below the gate during the run (on-device model refused)")
            Result.success()
        } catch (e: IndexConfigException) {
            PcLog.w(TAG, "index config failure; not retrying until the next trigger: ${SafeLog.describe(e)}")
            Result.failure()
        } catch (e: Exception) {
            PcLog.w(TAG, "index run failed: ${SafeLog.describe(e)}")
            retryOrFail(attempt)
        }

        private fun retryOrFail(attempt: Int): Result = if (attempt + 1 < MAX_RUNS) Result.retry() else Result.failure()
        /** How long a battery-paused run waits before it reads the power again. */
        const val BATTERY_RECHECK_MS = 15 * 60_000L

        private fun request(rebuild: Boolean, recheckAfterMs: Long = 0L) = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setInputData(workDataOf(KEY_REBUILD to rebuild))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .apply { if (recheckAfterMs > 0) setInitialDelay(recheckAfterMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .build()

        /** New memories, app start, binding change: KEEP (a run already queued will see them). Rebuild: REPLACE. */
        fun enqueue(context: Context, rebuild: Boolean = false) {
            // A Rebuild is durable from the moment it is asked for: a pause (which cancels this work) or a process
            // death before it runs leaves it pending, and the first run after resume honours it. It also resets the drift
            // streak now (as the run would), so "index unstable" clears at the user's request, even while paused.
            // The request exists first, so its id is the pending token (token, then flag: a reader of the flag finds the token).
            val req = request(rebuild)
            if (rebuild) PrefsIndexClock(context).apply { setPendingRebuildToken(req.id.toString()); setRebuildPending(true); recordDiscard(null) }
            // 2026-11-16 (Codex idxbatt r1): while the index is battery-paused an ordinary request only makes sure a wait exists (KEEP: one
            // re-check per BATTERY_RECHECK_MS however many memories arrive); only a Rebuild runs now (it re-defers when the power is still low).
            // The wait is its own unique work, so this never replaces a queued Rebuild or a run that is already going.
            val paused = !rebuild && try { PrefsIndexClock(context).batteryPaused() } catch (_: Exception) { false }
            if (paused) {
                WorkManager.getInstance(context).enqueueUniqueWork(NAME_WAIT, ExistingWorkPolicy.KEEP, request(false, recheckAfterMs = BATTERY_RECHECK_MS))
                return
            }
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (rebuild) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        }

        /**
         * An explicit resume trigger (the user changed the indexing battery level): while battery-paused, replace the wait with a run that reads the
         * power now. Does nothing when not paused. Safe off the main thread only.
         */
        fun recheckBatteryNow(context: Context) {
            val paused = try { PrefsIndexClock(context).batteryPaused() } catch (_: Exception) { false }
            if (paused) WorkManager.getInstance(context).enqueueUniqueWork(NAME_WAIT, ExistingWorkPolicy.REPLACE, request(false))
        }

        /** The wait before the next image-backfill run: 30 s, doubling per consecutive BACKFILL, at most 15 min (never a tight loop). */
        internal fun moreDelayMs(streak: Int): Long = minOf(MORE_MAX_DELAY_MS, MORE_BASE_DELAY_MS shl streak.coerceIn(0, 10))

        private fun continueLater(context: Context, delayMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<EmbeddingWorker>()
                    .setInputData(workDataOf(KEY_REBUILD to false))
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .apply { if (delayMs > 0) setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .build(),
            )
        }

        private const val MORE_BASE_DELAY_MS = 30_000L
        private const val MORE_MAX_DELAY_MS = 15 * 60_000L
    }
}
