package com.rokid.context.canvas.moment

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException

/**
 * The periodic Moment rebuild: the current and the previous hour of stored Moments, through [MomentTriggers] (so the store switch, the tombstones and the fences apply as for every
 * build). It catches what the event triggers cannot know about (a mark that was undone, a part that changed without a frame commit). Runs on WorkManager's background dispatcher.
 * Logs nothing; a failure is retried with backoff.
 */
class MomentCadenceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        MomentTriggers.get(applicationContext).rebuildRecent(System.currentTimeMillis())
        Result.success()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Result.retry()
    }

    companion object { const val NAME = "moment_cadence" }
}
