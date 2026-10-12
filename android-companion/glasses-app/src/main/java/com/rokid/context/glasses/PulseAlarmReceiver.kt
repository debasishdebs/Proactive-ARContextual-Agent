package com.rokid.context.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PulseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE_PULSE) return
        Log.i(TAG, "Alarm received. Starting pulse foreground service")
        runCatching {
            PulseKeepAliveService.startPulse(context)
        }.onFailure { error ->
            Log.e(TAG, "Pulse start failed: ${error.message}. Re-arming alarm.")
            PulseCaptureScheduler(context).scheduleNext(RETRY_DELAY_MS)
        }
    }

    companion object {
        private const val TAG = "RokidPulseAlarm"
        private const val RETRY_DELAY_MS = 15_000L
        const val ACTION_FIRE_PULSE = "com.rokid.context.glasses.action.FIRE_PULSE"
    }
}
