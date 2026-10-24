package com.rokid.context.canvas.moment

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.canvas.outbox.OutboxTriggers
import kotlin.coroutines.cancellation.CancellationException

/**
 * The periodic cleanup-outbox drain (first: a deletion comes before any rebuild; see `OutboxDrainer`) and the Moment rebuild: the current and the previous hour of stored Moments, through [MomentTriggers] (so the store switch, the tombstones and the fences apply as for every
 * build), then the Scene rebuild when its own cadence is due ([SceneTriggers]), then the derive pass ([DeriveTriggers]: off until switched on, its own cadence). It catches what the event triggers cannot know about (a mark that was undone, a part that changed without a frame commit). Runs on WorkManager's background dispatcher.
 * Logs counts only (the drainer's); a rebuild failure is retried with backoff.
 */
class MomentCadenceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val now = System.currentTimeMillis()
        try { OutboxTriggers.get(applicationContext).drain() } catch (c: CancellationException) { throw c } catch (e: Exception) { /* the rows stay; the next run retries, and the rebuild below must not wait on it */ }
        MomentTriggers.get(applicationContext).rebuildRecent(now)
        SceneTriggers.get(applicationContext).runIfDue(now) // Scenes are cut from the Moments just rebuilt, at their own stored cadence
        try { DeriveTriggers.get(applicationContext).runIfDue(now) } catch (c: CancellationException) { throw c } catch (e: Exception) { /* the derive step is optional: its fields stay missing and the next due run retries */ }
        Result.success()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Result.retry()
    }

    companion object { const val NAME = "moment_cadence" }
}
