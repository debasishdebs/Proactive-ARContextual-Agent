package com.rokid.context.phone.canvas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that carries the canvas server while it is needed (R8): the canvas screen is open,
 * LAN is on, or a run is going. It stops itself, and the server, after 30 min with no authenticated request (and no run,
 * no open canvas screen), on the notification's Stop action, or when Developer mode turns off.
 */
class CanvasService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var watcher: Job? = null
    private lateinit var graph: CanvasGraph

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = CanvasGraph.get(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Experiment canvas", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !DeveloperModeStore.get(this).enabled.value) {
            // A started foreground service must still enter the foreground before it may stop.
            enterForeground("Stopping")
            shutDown()
            return START_NOT_STICKY
        }
        enterForeground(text(graph.status.value))
        if (!graph.status.value.on) graph.start(false)
        if (watcher == null) watcher = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        var lastText = text(graph.status.value)
        val updates = launchStatus { s -> text(s).let { if (it != lastText) { lastText = it; notify(it) } } }
        try {
            while (scope.isActive) {
                delay(TICK_MS)
                if (!DeveloperModeStore.get(this@CanvasService).enabled.value || graph.shouldIdleStop()) { shutDown(); return }
            }
        } finally { updates.cancel() }
    }

    private fun launchStatus(onChange: (CanvasStatus) -> Unit): Job = scope.launch {
        graph.status.drop(1).collect { s ->
            if (!s.on) shutDown() else onChange(s)
        }
    }

    private fun shutDown() {
        graph.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun text(s: CanvasStatus) = "Experiment canvas is on \u00b7 " + (s.lanUrl ?: s.localUrl ?: "starting")

    private fun enterForeground(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
    }

    private fun notify(text: String) = getSystemService(NotificationManager::class.java).notify(ID, notification(text))

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CanvasService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Experiment canvas")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val CHANNEL = "canvas"
        const val ID = 4107
        const val ACTION_STOP = "com.rokid.context.phone.canvas.STOP"
        private const val TICK_MS = 60_000L

        /** Starts the service (and so the server) when Developer mode is on. */
        fun start(context: Context) {
            if (!DeveloperModeStore.get(context).enabled.value) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CanvasService::class.java)) }
        }

        fun stop(context: Context) { context.stopService(Intent(context, CanvasService::class.java)) }
    }
}
