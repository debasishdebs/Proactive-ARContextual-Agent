package com.rokid.context.canvas.moment

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.canvas.outbox.OutboxTriggers
import com.rokid.context.core.log.PcLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * The periodic cleanup-outbox drain (first: a deletion comes before any rebuild; see `OutboxDrainer`) and the Moment rebuild: the current and the previous hour of stored Moments, through [MomentTriggers] (so the store switch, the tombstones and the fences apply as for every
 * build), then the Scene rebuild when its own cadence is due ([SceneTriggers]), then the derive pass ([DeriveTriggers]: off until switched on, its own cadence). It catches what the event triggers cannot know about (a mark that was undone, a part that changed without a frame commit). Runs on WorkManager's background dispatcher.
 * Logs counts and state names only (start, the rebuild counts, end state, a duration bucket; the drainer's own); a rebuild failure is retried with backoff.
 */
class MomentCadenceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val attempt = runAttemptCount
        PcLog.i(TAG, "cadence start: attempt=$attempt")
        var state = "success"
        try {
            try { OutboxTriggers.get(applicationContext).drain() } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i(TAG, "cadence outbox drain failed (${e.javaClass.simpleName})") /* the rows stay; the next run retries, and the rebuild below must not wait on it */ }
            val report = MomentTriggers.get(applicationContext).rebuildRecent(now)
            val stored = report.stored; val deleted = report.removed; val rejected = report.refused
            PcLog.i(TAG, "cadence rebuilt: stored=$stored deleted=$deleted rejected=$rejected")
            SceneTriggers.get(applicationContext).runIfDue(now) // Scenes are cut from the Moments just rebuilt, at their own stored cadence
            try { DeriveTriggers.get(applicationContext).runIfDue(now) } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i(TAG, "cadence derive failed (${e.javaClass.simpleName})") /* the derive step is optional: its fields stay missing and the next due run retries */ }
            return Result.success()
        } catch (c: CancellationException) {
            state = "cancelled"; throw c
        } catch (e: Exception) {
            state = "retry"; PcLog.i(TAG, "cadence failed (${e.javaClass.simpleName})")
            return Result.retry()
        } finally {
            val took = System.currentTimeMillis() - now
            val how = if (took < 1_000) "under 1 s" else if (took < 10_000) "under 10 s" else if (took < 60_000) "under 60 s" else "over 60 s"
            PcLog.i(TAG, "cadence end: $state")
            PcLog.i(TAG, "cadence duration: $how")
        }
    }

    companion object { const val NAME = "moment_cadence"; private const val TAG = "PcMoments" }
}
