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
    const val KEY_SCOPE = "publication_scope"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var events: FlowTriggers? = null

    /**
     * Slice C: a Moment landed or a Scene was stored ([NodeTypes.TRIGGER_MOMENT], [NodeTypes.TRIGGER_SCENE]). Off the calling thread, never throws, and it enqueues nothing unless the
     * published Nudge flow is rooted at that trigger (see [FlowTriggers]).
     */
    fun onEvent(context: Context, trigger: String) {
        val app = context.applicationContext
        scope.launch {
            if (trigger == NodeTypes.TRIGGER_MOMENT || trigger == NodeTypes.TRIGGER_SCENE) com.rokid.context.orchestration.proactive.ProactiveTrigger.onMomentEnd(app) // v3.0 shadow loop: no-op while its switch is off
            try { fire(app, trigger) } catch (e: Exception) { PcLog.i("NudgeTrigger", "trigger not scheduled: ${SafeLog.describe(e)}") }
        }
    }

    suspend fun fire(app: Context, trigger: String): Boolean {
        val t = events ?: synchronized(this) { events ?: FlowTriggers(DaoFlowPlans(MemoryGraph.get(app).db.canvasDao()), { t, k -> enqueue(app, t, k) }).also { events = it } }
        return t.fire(trigger)
    }

    /** `trigger.schedule`, called by the existing 15 minute cadence worker: fires at most once when a cron occurrence has passed since the last check (see [ScheduleTick]). */
    suspend fun tickSchedule(app: Context): Boolean {
        val db = MemoryGraph.get(app).db
        return ScheduleTick(DaoFlowPlans(db.canvasDao()), DaoTriggerMeta(db.canvasSlice3Dao()), { t, key -> enqueue(app, t, key) }).tick()
    }

    /** A schedule occurrence's key is `<scope>_<occurrence ms>` (see [ScheduleTick]): the publication it was queued for. Other triggers carry none. */
    fun scopeOf(trigger: String, key: String?): String? = if (trigger == NodeTypes.TRIGGER_SCHEDULE && key != null) key.substringBeforeLast('_') else null

    /** One unique run per trigger type (KEEP): a burst is one queued run. Not while the battery is low; no wake lock, no expedited work. */
    suspend fun enqueue(context: Context, trigger: String, key: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = OneTimeWorkRequestBuilder<NudgeWorker>().setInputData(workDataOf(*listOfNotNull(KEY_TRIGGER to trigger, scopeOf(trigger, key)?.let { KEY_SCOPE to it }).toTypedArray()))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
            // durable only once WorkManager has the work: wait for its result (a unique KEEP enqueue is idempotent)
            WorkManager.getInstance(context).enqueueUniqueWork(if (trigger == NodeTypes.TRIGGER_SUMMARIZER) WORK else "${WORK}_$trigger" + (key?.let { "_$it" } ?: ""), ExistingWorkPolicy.KEEP, request).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            PcLog.i("NudgeTrigger", "nudge not scheduled: ${SafeLog.describe(e)}")
            false
        }
    }
}
