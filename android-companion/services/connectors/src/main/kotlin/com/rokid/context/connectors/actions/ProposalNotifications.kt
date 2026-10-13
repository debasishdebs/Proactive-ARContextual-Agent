package com.rokid.context.connectors.actions

import android.app.NotificationManager
import android.content.Context

/** Notification ids of a proposal: the Add / No thanks prompt (tag = proposalId) and a fired reminder. */
object ProposalNotifications {
    const val PROPOSAL_ID = 7_301

    /** Cancels every notification posted for [proposalIds] (Forget redaction, integration carry 3). */
    fun cancel(context: Context, proposalIds: Collection<String>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        proposalIds.forEach { id ->
            nm.cancel(id, PROPOSAL_ID)
            nm.cancel(reminderId(id))
        }
    }

    /** The fired reminder's notification id (ReminderReceiver). */
    fun reminderId(proposalId: String): Int = proposalId.hashCode()
}
