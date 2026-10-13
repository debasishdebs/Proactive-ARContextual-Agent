package com.rokid.context.connectors.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rokid.context.connectors.actions.executors.ReminderExecutor

/** A reminder alarm fired: post it on channel `reminders`. No DB work (title/note travel in the alarm's intent). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderExecutor.EXTRA_ID) ?: return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(intent.getStringExtra(ReminderExecutor.EXTRA_TITLE) ?: "Reminder")
            .setContentText(intent.getStringExtra(ReminderExecutor.EXTRA_NOTE)?.takeIf { it.isNotBlank() } ?: "Reminder")
            .setAutoCancel(true)
            .build()
        try { nm.notify(id.hashCode(), n) } catch (_: SecurityException) { /* POST_NOTIFICATIONS revoked */ }
    }

    companion object { const val CHANNEL = "reminders" }
}
