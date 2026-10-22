package com.rokid.context.phone.context

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransitionResult

/**
 * Non-exported target of the Activity Recognition PendingIntent; forwards each result to the running [ActivitySource]. When no
 * source is running and the switch is off, the registration is a leftover of a dead process: it is removed so transitions stop
 * waking the app.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = ActivitySource.current
        if (source == null) {
            if (shouldUnregister(null, runCatching { ContextSourceSettings.of(context).enabled(ActivitySource.ID) }.getOrDefault(true))) unregister(context)
            return
        }
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        source.onResult(result.transitionEvents.map { ActivitySource.Transition(it.activityType, it.transitionType, it.elapsedRealTimeNanos / 1_000_000L) })
    }

    @android.annotation.SuppressLint("MissingPermission", "UnspecifiedImmutableFlag")
    private fun unregister(context: Context) {
        runCatching {
            val pi = PendingIntent.getBroadcast(
                context, ActivitySource.REQUEST_CODE, Intent(context, ActivityTransitionReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE,
            ) ?: return
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pi)
            pi.cancel()
        }
    }

    companion object {
        /** True for a result with no running source while the switch is off. */
        internal fun shouldUnregister(current: ActivitySource?, enabled: Boolean) = current == null && !enabled
    }
}
