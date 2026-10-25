package com.rokid.context.phone.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rokid.context.core.log.PcLog
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
                // Lock screen: a generic line only, and answering needs an unlocked phone (fix round 1 I-3).
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_add).setContentTitle(PUBLIC_TITLE).build())
                .addAction(action("Add", answer(p.id, true)))
                .addAction(action("No thanks", answer(p.id, false)))
                .build()
            try { manager.notify(p.id, NOTIFICATION_ID, n) } catch (_: SecurityException) { return }
        }
    }

    /** setAuthenticationRequired takes effect on API 31+ (NotificationCompat ignores it below). */
    private fun action(title: String, pi: PendingIntent) = NotificationCompat.Action.Builder(0, title, pi).setAuthenticationRequired(true).build()

    private fun answer(id: String, yes: Boolean): PendingIntent {
        val i = Intent(context, ProposalActionReceiver::class.java)
            .setData(Uri.parse("pc-proposal://$id/${if (yes) "yes" else "no"}"))
            .putExtra(ProposalActionReceiver.EXTRA_ID, id).putExtra(ProposalActionReceiver.EXTRA_YES, yes)
        return PendingIntent.getBroadcast(context, (id.hashCode() * 31) + if (yes) 1 else 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL = "proposals"
        const val NOTIFICATION_ID = com.rokid.context.connectors.actions.ProposalNotifications.PROPOSAL_ID
        const val PUBLIC_TITLE = "Suggested action"

        /** Follows the PENDING set for the life of [scope]. */
        fun start(app: Context, repo: ProposalRepository, scope: CoroutineScope) {
            val notifier = ProposalNotifier(app)
            scope.launch {
                repo.pending().collect { rows ->
                    try { notifier.sync(rows) } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                        try { PcLog.w("ProposalNotifier", "sync failed: ${SafeLog.describe(t)}") } catch (_: RuntimeException) { }
                    }
                }
            }
        }
    }
}
