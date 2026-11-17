package com.rokid.context.work.frames.place

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rokid.context.core.log.PcLog
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Independent recovery of place memories whose take-back failed (Codex pg-r5 P1 #3). Such a memory is written HIDDEN ([PlaceMemory.save] `pending`) and its durable mark stays in the
 * ledger until it is deleted, so it is never readable meanwhile; this deletes it without waiting for another sampler poke: at every start ([ensureIfPending], from the background-work
 * start paths), after every Forget / Delete all ([kick]), and through a unique periodic job every 15 min that cancels itself once no mark is left. Deleting a row needs no network,
 * no model and no wake lock; the job only asks for a battery that is not low, and WorkManager's own doze handling applies.
 */
object PlaceRecovery {
    const val NAME = "place_recovery"
    const val NOW_NAME = "place_recovery_now"
    internal const val PERIOD_MIN = 15L
    private const val TAG = "PcPlace"

    /**
     * Deletes every marked memory unless an attempt is running (it owns the marks of its own commit). True = nothing is marked any more. A failed delete leaves its mark for the next run.
     */
    internal suspend fun sweep(ledger: PlaceMemoryLedger, flight: PlaceFlight, delete: suspend (String) -> Unit): Boolean {
        if (ledger.pending().isEmpty()) return true
        if (!flight.busy.compareAndSet(false, true)) return false
        try {
            for (id in ledger.pending()) {
                try { delete(id); ledger.clear(id) } catch (c: CancellationException) { throw c } catch (_: Exception) { }
            }
        } finally { flight.busy.set(false) }
        return ledger.pending().isEmpty()
    }

    suspend fun sweep(app: Context): Boolean = sweep(PlaceMemory.ledger(app), PlaceSamplers.flight) { PlaceMemory.delete(app, it) }

    /** Schedules the periodic job (unique, KEEP) and a first run now. */
    fun ensure(app: Context) {
        runCatching {
            val wm = WorkManager.getInstance(app.applicationContext)
            val battery = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            wm.enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PlaceRecoveryWorker>(PERIOD_MIN, TimeUnit.MINUTES).setConstraints(battery).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PERIOD_MIN, TimeUnit.MINUTES).build(),
            )
            kick(app)
        }
    }

    /** One run soon (after a Forget / Delete all, or at start). Only when a mark exists. */
    fun kick(app: Context) {
        runCatching {
            if (PlaceMemory.ledger(app).pending().isEmpty()) return
            WorkManager.getInstance(app.applicationContext).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<PlaceRecoveryWorker>().build())
        }
    }

    /** Start paths: schedule only when a mark survived the last process. */
    fun ensureIfPending(app: Context) {
        runCatching { if (PlaceMemory.ledger(app).pending().isNotEmpty()) ensure(app) }
    }

    fun cancel(app: Context) { runCatching { WorkManager.getInstance(app.applicationContext).cancelUniqueWork(NAME) } }
}

class PlaceRecoveryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val clear = try { PlaceRecovery.sweep(applicationContext) } catch (c: CancellationException) { throw c } catch (_: Exception) { false }
        if (clear) { PlaceRecovery.cancel(applicationContext); PcLog.i("PcPlace", "place recovery done") } else PcLog.i("PcPlace", "place recovery pending")
        return if (clear) Result.success() else Result.retry()
    }
}
