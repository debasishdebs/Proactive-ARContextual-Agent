package com.rokid.context.phone.hub

import android.content.Context

/**
 * v1.1 hub auto-start (trip gap 2): the start triggers other than the glasses' Bluetooth ACL connect ([GlassesAclReceiver]). They only SUBMIT [AutoStopEvent.GlassesNearby];
 * the reducer decides (manual stop, an explicit phone pause, debounce and the hourly cap all deny; a start goes through the guarded wake path, never as a user start) and logs
 * every decision with literal tokens (kind and reason; no addresses, no ids).
 * Not a trigger here: a glasses link CONNECTED/hello. The link lives only inside the hub service, so a stopped hub can never observe it; its Bluetooth ACL connect is the signal.
 */
object HubAutoStart {
    /** A watch message arrives about once a second: at most one submit per this long in this process (the reducer's own debounce is per start). */
    const val WATCH_SUBMIT_EVERY_MS = 30_000L

    @Volatile private var lastWatchSubmitMs = Long.MIN_VALUE
    @Volatile internal var clock: () -> Long = System::currentTimeMillis
    @Volatile internal var running: () -> Boolean = { ContextHubService.isRunning() }
    /** The event goes to the process-wide engine; a test seam. */
    @Volatile internal var submit: (Context, AutoStopEvent) -> Unit = { app, e -> ReceiverWork.await(AutoStopWake.engine(app), e) }

    /** Entry-point gate for a watch message that found the hub off. True = submitted. Runs on the caller's (a Data Layer worker) thread; never throws. */
    fun onWatchMessage(context: Context): Boolean {
        val app = context.applicationContext
        val t = clock()
        synchronized(this) {
            if (running()) return false
            if (lastWatchSubmitMs != Long.MIN_VALUE && t - lastWatchSubmitMs in 0 until WATCH_SUBMIT_EVERY_MS) return false
            lastWatchSubmitMs = t
        }
        return runCatching { submit(app, AutoStopEvent.GlassesNearby("watch")); true }.getOrDefault(false)
    }

    internal fun resetForTest() { lastWatchSubmitMs = Long.MIN_VALUE }
}
