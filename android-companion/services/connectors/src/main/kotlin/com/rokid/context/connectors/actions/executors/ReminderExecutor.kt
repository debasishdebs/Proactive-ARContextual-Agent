package com.rokid.context.connectors.actions.executors

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.connectors.actions.ReminderReceiver
import com.rokid.context.memory.db.ProposalEntity

/**
 * A reminder is an alarm keyed by proposalId (no table: schema v1 is frozen), so re-arming replaces it. Exact when the
 * platform allows it, else a 10-minute window. Notifications off still counts as Done (the alarm is set).
 */
class ReminderExecutor(private val context: Context) : ActionExecutor {
    private val alarms: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    override suspend fun execute(p: ProposalEntity): ExecResult {
        val r = ActionParams.decode(ActionType.REMINDER, p.paramsJson) as? ActionParams.Reminder ?: return ExecResult.Failed(FailCode.UNKNOWN)
        arm(p.id, r)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) logCode(FailCode.NOTIFICATIONS_OFF)
        return ExecResult.Done("alarm:${p.id}")
    }

    override suspend fun undo(p: ProposalEntity): Boolean {
        cancelAlarm(context, p.id)
        return true
    }

    /** Sets (or replaces) the alarm for [proposalId]; also used by the boot re-arm. */
    fun arm(proposalId: String, r: ActionParams.Reminder) {
        val pi = pendingIntent(context, proposalId, r)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueAtMs, pi)
                return
            } catch (_: SecurityException) {
                // Exact-alarm access revoked between the check and the call: fall back to the window.
            }
        }
        alarms.setWindow(AlarmManager.RTC_WAKEUP, r.dueAtMs, WINDOW_MS, pi)
    }

    private fun logCode(code: FailCode) {
        try { Log.i(TAG, "reminder armed; code=${code.name}") } catch (_: RuntimeException) { /* plain JVM */ }
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        const val EXTRA_TITLE = "title"
        const val EXTRA_NOTE = "note"
        const val EXTRA_ID = "proposalId"
        private const val TAG = "ReminderExecutor"

        /** Cancels the alarm armed for [proposalId] (undo, and Forget redaction); a no-op when none is set. */
        fun cancelAlarm(context: Context, proposalId: String) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, proposalId, null))
        }

        /** Same request code and data URI per proposal, so set/cancel always address the same alarm. */
        fun pendingIntent(context: Context, proposalId: String, r: ActionParams.Reminder?): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java).setData(Uri.parse("pc-reminder://$proposalId")).putExtra(EXTRA_ID, proposalId)
            if (r != null) intent.putExtra(EXTRA_TITLE, r.title).putExtra(EXTRA_NOTE, r.note)
            return PendingIntent.getBroadcast(context, proposalId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
