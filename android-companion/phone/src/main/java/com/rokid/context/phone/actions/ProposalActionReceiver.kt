package com.rokid.context.phone.actions

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.ActionsGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A notification's Add / No thanks: answers through the engine's CAS (a late answer is a no-op), off Main via goAsync. */
class ProposalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val yes = intent.getBooleanExtra(EXTRA_YES, false)
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                ActionsGraph.get(app).engine.respond(id, yes, BY)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                try { Log.w("ProposalAction", "respond failed: ${SafeLog.describe(t)}") } catch (_: RuntimeException) { }
            } finally {
                app.getSystemService(NotificationManager::class.java)?.cancel(id, ProposalNotifier.NOTIFICATION_ID)
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "proposalId"
        const val EXTRA_YES = "yes"
        const val BY = "notification"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
