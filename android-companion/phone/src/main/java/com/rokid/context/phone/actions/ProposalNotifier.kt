package com.rokid.context.phone.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.ActionCopy
import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.ProposalEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * One notification per PENDING proposal (channel `proposals`), with Add / No thanks → [ProposalActionReceiver]. Every
 * notification whose proposal is no longer PENDING is cancelled. No notification permission → nothing, silently.
 */
class ProposalNotifier(private val context: Context, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val nm: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    fun sync(pending: List<ProposalEntity>) {
        val manager = nm ?: return
        val ids = pending.map { it.id }.toSet()
        manager.activeNotifications.filter { it.id == NOTIFICATION_ID && it.tag != null && it.tag !in ids }.forEach { manager.cancel(it.tag, NOTIFICATION_ID) }
        if (pending.isEmpty() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Suggested actions", NotificationManager.IMPORTANCE_DEFAULT))
        val shown = manager.activeNotifications.mapNotNull { it.tag }.toSet()
        for (p in pending) {
            if (p.id in shown) continue
            val type = ActionParams.typeOf(p.type) ?: continue
            val detail = ActionParams.decode(type, p.paramsJson)?.let { ActionCopy.clip(ActionCopy.summary(it, zone())) } ?: continue
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_add)
                .setContentTitle(ActionCopy.confirmTitle(type))
                .setContentText(detail)
                .setOnlyAlertOnce(true)
                .addAction(0, "Add", answer(p.id, true))
                .addAction(0, "No thanks", answer(p.id, false))
                .build()
            try { manager.notify(p.id, NOTIFICATION_ID, n) } catch (_: SecurityException) { return }
        }
    }

    private fun answer(id: String, yes: Boolean): PendingIntent {
        val i = Intent(context, ProposalActionReceiver::class.java)
            .setData(Uri.parse("pc-proposal://$id/${if (yes) "yes" else "no"}"))
            .putExtra(ProposalActionReceiver.EXTRA_ID, id).putExtra(ProposalActionReceiver.EXTRA_YES, yes)
        return PendingIntent.getBroadcast(context, (id.hashCode() * 31) + if (yes) 1 else 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL = "proposals"
        const val NOTIFICATION_ID = 7_301

        /** Follows the PENDING set for the life of [scope]. */
        fun start(app: Context, repo: ProposalRepository, scope: CoroutineScope) {
            val notifier = ProposalNotifier(app)
            scope.launch {
                repo.pending().collect { rows ->
                    try { notifier.sync(rows) } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                        try { Log.w("ProposalNotifier", "sync failed: ${SafeLog.describe(t)}") } catch (_: RuntimeException) { }
                    }
                }
            }
        }
    }
}
