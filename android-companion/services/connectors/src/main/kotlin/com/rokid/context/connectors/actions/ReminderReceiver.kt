package com.rokid.context.connectors.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rokid.context.core.log.PcLog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
 * A reminder alarm fired: post it on channel `reminders`. The proposal is re-read first (on IO, via goAsync): a reminder
 * that Forget redacted, that was undone, or that is gone posts nothing, even if its alarm survived (privacy carry).
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra(ReminderExecutor.EXTRA_ID) == null) return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                show(app, intent, MemoryGraph.get(app).proposals)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                try { PcLog.w("ReminderReceiver", "reminder not shown: ${SafeLog.describe(t)}") } catch (_: RuntimeException) { }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val CHANNEL = "reminders"
        const val PUBLIC_TITLE = "Reminder"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Posts the fired reminder [intent] when its proposal in [repo] is still live (see the class doc). */
        suspend fun show(context: Context, intent: Intent, repo: ProposalRepository) {
            val id = intent.getStringExtra(ReminderExecutor.EXTRA_ID) ?: return
            val p = repo.byId(id) ?: return
            if (p.state != ProposalState.EXECUTED.name || p.paramsJson == ForgetService.REDACTED_PARAMS) return
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(intent.getStringExtra(ReminderExecutor.EXTRA_TITLE) ?: "Reminder")
                .setContentText(intent.getStringExtra(ReminderExecutor.EXTRA_NOTE)?.takeIf { it.isNotBlank() } ?: "Reminder")
                .setAutoCancel(true)
                // Lock screen: a generic "Reminder", never the title or note (fix round 1 I-3).
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(PUBLIC_TITLE).build())
                .build()
            try { nm.notify(ProposalNotifications.reminderId(id), n) } catch (_: SecurityException) { /* POST_NOTIFICATIONS revoked */ }
        }
    }
}
