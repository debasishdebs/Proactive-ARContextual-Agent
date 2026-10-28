package com.rokid.context.orchestration.nudge

import android.content.Context
import com.rokid.context.core.log.PcLog
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.canvas.DaoFlowPlans
import com.rokid.context.memory.MemoryGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Called after each summarizer pass (trusted summary or reprocess): enqueues the unique work "nudge_engine" (KEEP). */
object NudgeTrigger {
    const val WORK = "nudge_engine"

    fun afterSummarizerPass(context: Context) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<NudgeWorker>().build())
        } catch (e: Exception) {
            PcLog.i("NudgeTrigger", "nudge not scheduled: ${SafeLog.describe(e)}") // never fails the summarizer pass
        }
    }

    const val KEY_TRIGGER = "trigger"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var events: FlowTriggers? = null

    /**
     * Slice C: a Moment landed or a Scene was stored ([NodeTypes.TRIGGER_MOMENT], [NodeTypes.TRIGGER_SCENE]). Off the calling thread, never throws, and it enqueues nothing unless the
     * published Nudge flow is rooted at that trigger (see [FlowTriggers]).
     */
    fun onEvent(context: Context, trigger: String) {
        val app = context.applicationContext
        scope.launch {
            try { fire(app, trigger) } catch (e: Exception) { PcLog.i("NudgeTrigger", "trigger not scheduled: ${SafeLog.describe(e)}") }
        }
    }

    suspend fun fire(app: Context, trigger: String): Boolean {
        val t = events ?: synchronized(this) { events ?: FlowTriggers(DaoFlowPlans(MemoryGraph.get(app).db.canvasDao()), { enqueue(app, it) }).also { events = it } }
        return t.fire(trigger)
    }

    /** `trigger.schedule`, called by the existing 15 minute cadence worker: fires at most once when a cron occurrence has passed since the last check (see [ScheduleTick]). */
    suspend fun tickSchedule(app: Context): Boolean {
        val db = MemoryGraph.get(app).db
        return ScheduleTick(DaoFlowPlans(db.canvasDao()), DaoTriggerMeta(db.canvasSlice3Dao()), { enqueue(app, it) }).tick()
    }

    /** One unique run per trigger type (KEEP): a burst is one queued run. Not while the battery is low; no wake lock, no expedited work. */
    suspend fun enqueue(context: Context, trigger: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = OneTimeWorkRequestBuilder<NudgeWorker>().setInputData(workDataOf(KEY_TRIGGER to trigger))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
            // durable only once WorkManager has the work: wait for its result (a unique KEEP enqueue is idempotent)
            WorkManager.getInstance(context).enqueueUniqueWork(if (trigger == NodeTypes.TRIGGER_SUMMARIZER) WORK else "${WORK}_$trigger", ExistingWorkPolicy.KEEP, request).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            PcLog.i("NudgeTrigger", "nudge not scheduled: ${SafeLog.describe(e)}")
            false
        }
    }
}
