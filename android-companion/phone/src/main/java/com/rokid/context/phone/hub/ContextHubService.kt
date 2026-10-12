package com.rokid.context.phone.hub

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.UserFacingErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground (connectedDevice) service that keeps the hub — glasses link, frame ingest, `StateFrame` broadcast — alive
 * (spec §1.7). Adapted from Live Fit `LiveFitHubService`. Started from a resumed activity ([HubActivityHook]).
 */
class ContextHubService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Glasses link", NotificationManager.IMPORTANCE_LOW))
        runCatching { ServiceCompat.startForeground(this, ID, notification(READY), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            .onFailure { Log.w(TAG, "foreground refused: ${it.javaClass.simpleName}"); stopSelf(); return }
        running = true
        val hub = HubGraph.get(this)
        hub.start()
        watcher = CoroutineScope(Dispatchers.Main.immediate).launch {
            hub.status.map(::notificationText).distinctUntilChanged().collect { text -> lastText = text; nm.notify(ID, notification(text)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        watcher?.cancel()
        HubGraph.get(this).stop()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Proactive Context")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        const val TAG = "ContextHub"
        private const val CHANNEL = "hub"
        private const val ID = 31
        private const val READY = "Glasses link ready"
        @Volatile private var running = false
        @Volatile private var lastText = READY

        fun isRunning(): Boolean = running

        /** One line for the legacy Live screen (Task 12 shows it in place of the RFCOMM bridge message). */
        fun statusLine(): String = lastText

        /** Idempotent: called on every activity resume. */
        fun ensureRunning(context: Context) {
            val granted = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!shouldStart(running, canStart(Build.VERSION.SDK_INT, granted))) return
            runCatching { context.startForegroundService(Intent(context, ContextHubService::class.java)) }
                .onFailure { Log.w(TAG, "start refused: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, ContextHubService::class.java)) }

        internal fun canStart(sdk: Int, bluetoothGranted: Boolean) = sdk < 31 || bluetoothGranted
        internal fun shouldStart(running: Boolean, canStart: Boolean) = !running && canStart

        internal fun notificationText(s: GlassesStatus): String = UserFacingErrors.forLink(s.link)?.title ?: when (s.link) {
            LinkState.CONNECTED -> "Glasses connected"
            LinkState.PAUSED_BT -> "Reconnecting to glasses"
            else -> "Connecting to glasses"
        }
    }
}
