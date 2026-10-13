package com.rokid.context.connectors.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.forget.ForgetService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Alarms do not survive a reboot or an update: re-arm every EXECUTED reminder still in the future (DB on IO, via goAsync).
 * A reminder whose params Forget redacted is skipped.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                rearm(MemoryGraph.get(app).proposals, ReminderExecutor(app), System.currentTimeMillis())
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                try { Log.w("BootReceiver", "rearm failed: ${SafeLog.describe(t)}") } catch (_: RuntimeException) { }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Returns how many alarms were re-armed. */
        suspend fun rearm(repo: ProposalRepository, reminders: ReminderExecutor, nowMs: Long): Int =
            repo.inState(ProposalState.EXECUTED).filter { it.type == ActionType.REMINDER.name && it.paramsJson != ForgetService.REDACTED_PARAMS }.count { p ->
                val r = ActionParams.decode(ActionType.REMINDER, p.paramsJson) as? ActionParams.Reminder
                (r != null && r.dueAtMs > nowMs).also { if (it) reminders.arm(p.id, r!!) }
            }
    }
}
