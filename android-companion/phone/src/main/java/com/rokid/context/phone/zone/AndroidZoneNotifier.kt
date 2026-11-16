package com.rokid.context.phone.zone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** "Capture is paused: <zone name>": names only, no place, no coordinates. No notification permission = nothing, silently (the glasses and the Status screen still show the pause). */
class AndroidZoneNotifier(private val context: Context) : ZoneNotifier {
    override fun paused(zoneNames: List<String>, locationUnknown: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pause zones", NotificationManager.IMPORTANCE_DEFAULT))
        val names = zoneNames.joinToString(", ")
        val text = if (locationUnknown) "Capture is paused: $names (location unknown)" else "Capture is paused: $names"
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Pause zone")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Capture is paused").build())
            .build()
        try { nm.notify(NOTIFICATION_ID, n) } catch (_: SecurityException) { }
    }

    override fun cleared() {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL = "pause_zones"
        const val NOTIFICATION_ID = 4120
    }
}
