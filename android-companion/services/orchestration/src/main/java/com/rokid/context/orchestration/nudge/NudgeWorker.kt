package com.rokid.context.orchestration.nudge

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.log.PcLog
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.ActionsGraph
import com.rokid.context.connectors.sources.SourcesGraph
import com.rokid.context.memory.MemoryGraph
import kotlin.coroutines.cancellation.CancellationException

/**
 * The run gate around [NudgeEngine] for the periodic backstop only (the fast path's [NudgeEvaluator] has its own gap; showing
 * is throttled by [NudgeGates]): skips when the HUD settings are unknown (fail-closed, not stamped) or the last run
 * started < 30 min ago; otherwise stamps `nudge_last_run_ms` at run start and runs. Returns whether it ran.
 */
class NudgeRunGate(
    private val prefs: SharedPreferences,
    private val clock: () -> Long,
    private val hud: () -> NudgeHud?,
    private val body: suspend () -> NudgeRun,
) {
    suspend fun run(): Boolean {
        var ran = false
        try {
            if (hud() == null) return false
            val now = clock()
            val last = prefs.getLong(KEY_LAST_RUN, 0L)
            if (last in (now - MIN_GAP_MS + 1)..now) return false
            prefs.edit().putLong(KEY_LAST_RUN, now).commit()
            ran = true
            body()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            PcLog.i(TAG, "nudge run failed: ${SafeLog.describe(e)}") // the next summarizer pass tries again
        }
        return ran
    }

    companion object {
        private const val TAG = "NudgeWorker"
        const val PREFS = "nudge_engine"
        const val KEY_LAST_RUN = "nudge_last_run_ms"
        const val MIN_GAP_MS = 30 * 60_000L
    }
}

/** Unique work "nudge_engine" (KEEP), enqueued after each summarizer pass. Always succeeds: no retry storm. */
class NudgeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val trigger = inputData.getString(NudgeTrigger.KEY_TRIGGER)
        if (trigger == FlowTriggers.BUILTIN_FALLBACK) {
            // the plan lookup failed when this was queued: the built-in flow, behind the summarizer pass's own 30 minute gap
            val prefs = app.getSharedPreferences(NudgeRunGate.PREFS, Context.MODE_PRIVATE)
            NudgeRunGate(prefs, System::currentTimeMillis, { NudgeEnvironment.hud?.invoke() }) { NudgeEngineFactory.engine(app).runOnce(builtInOnly = true) }.run()
            return Result.success()
        }
        if (trigger != null && trigger != com.rokid.context.ai.canvas.NodeTypes.TRIGGER_SUMMARIZER) {
            // a slice C trigger: its own rate limit and the engine's gates replace the backstop's 30 minute gap
            try { NudgeEngineFactory.engine(app).runOnce(trigger, scope = inputData.getString(NudgeTrigger.KEY_SCOPE)) } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i("NudgeWorker", "trigger run failed: ${SafeLog.describe(e)}") }
            return Result.success()
        }
        val prefs = app.getSharedPreferences(NudgeRunGate.PREFS, Context.MODE_PRIVATE)
        NudgeRunGate(prefs, System::currentTimeMillis, { NudgeEnvironment.hud?.invoke() }) { NudgeEngineFactory.engine(app).runOnce() }.run()
        return Result.success()
    }
}
