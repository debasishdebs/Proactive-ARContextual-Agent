package com.rokid.context.orchestration.proactive

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rokid.context.core.log.PcLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * The loop's triggers, riding EXISTING hooks only (no new wake-up, alarm, timer, GPS request or foreground service): the 15 minute cadence worker ([tick]), a PlaceSampler match ([onPlaceArrival]) and a Moment
 * or Scene end ([onMomentEnd]). While the master switch is off a call returns at once and enqueues nothing. Each enqueues the unique work `proactive_loop_<trigger>` (KEEP: a burst is one run), only while the
 * battery is not low, never expedited, no wake lock. A hook never throws into its caller.
 */
object ProactiveTrigger {
    const val WORK_PREFIX = "proactive_loop_"
    const val KEY_TRIGGER = "trigger"
    private const val TAG = "PcProactive"

    fun tick(context: Context) = enqueue(context, TriggerKind.TICK)
    fun onPlaceArrival(context: Context) = enqueue(context, TriggerKind.PLACE_ARRIVAL)
    fun onMomentEnd(context: Context) = enqueue(context, TriggerKind.MOMENT_END)

    fun workName(kind: TriggerKind) = WORK_PREFIX + kind.token

    /** True when work was handed to WorkManager. */
    fun enqueue(context: Context, kind: TriggerKind, active: () -> Boolean = { ProactiveSettings.of(context).active() }, enqueuer: (Context, TriggerKind) -> Unit = ::enqueueWork): Boolean = try {
        if (!active()) false else { enqueuer(context.applicationContext, kind); true }
    } catch (c: CancellationException) { throw c } catch (e: Exception) {
        PcLog.i(TAG, "proactive trigger not scheduled (${e.javaClass.simpleName})")
        false
    }

    private fun enqueueWork(app: Context, kind: TriggerKind) {
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>().setInputData(workDataOf(KEY_TRIGGER to kind.token))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
        WorkManager.getInstance(app).enqueueUniqueWork(workName(kind), ExistingWorkPolicy.KEEP, request)
    }
}

/** One run, then (v3.1) the delivery stage for a would-show (never retried: success always). The engine's own gates decide everything; the worker only builds it. */
class ProactiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val kind = TriggerKind.of(inputData.getString(ProactiveTrigger.KEY_TRIGGER)) ?: return Result.success()
        try {
            val run = ProactiveGraph.engine(applicationContext).run(kind)
            // v3.1: delivery is its own stage AFTER the commit, tried once now (a gate that is closed drops it; there is no backlog). With every delivery switch off it does nothing at all.
            run.record?.let { ProactiveGraph.delivery(applicationContext).deliver(it.id) }
        } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i("PcProactive", "proactive worker failed (${e.javaClass.simpleName})") }
        return Result.success()
    }
}
