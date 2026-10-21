package com.rokid.context.phone.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The "Open on glasses" notification's action and its swipe-away (a broadcast, not a service start: a swipe after the process died must not start the hub).
 * [notice] is set by [ContextHubService] while it runs; without it (a dead process) a dismiss has nothing to end and an open just reconnects.
 */
class ClosedNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notice = notice
        when (intent.action) {
            ContextHubService.ACTION_OPEN_GLASSES -> ContextHubService.handleOpenGlasses(context, notice ?: ClosedOnGlassesNotice(android.os.SystemClock::elapsedRealtime)) { reconnect(context) }
            ContextHubService.ACTION_CLOSED_DISMISSED -> notice?.onDismissed()
        }
    }

    companion object {
        @Volatile internal var notice: ClosedOnGlassesNotice? = null
        /** The same call as the banner's "Open on glasses" (StatusUi: `requestReconnect`); a test seam. */
        @Volatile internal var reconnect: (Context) -> Unit = { HubGraph.get(it).requestReconnect() }
    }
}
