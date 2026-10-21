package com.rokid.context.phone.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult

/** Non-exported target of the Activity Recognition PendingIntent; forwards each event to the running [ActivitySource] (no-op when none). */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = ActivitySource.current ?: return
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (e in result.transitionEvents) source.onTransition(e.activityType, e.transitionType, e.elapsedRealTimeNanos / 1_000_000L)
    }
}
