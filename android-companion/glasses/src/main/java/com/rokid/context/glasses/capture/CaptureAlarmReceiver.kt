package com.rokid.context.glasses.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokid.context.glasses.GlassesRuntime

/**
 * The capture alarm. With the keep-alive [CaptureService] running in this process, the capture is handed to it
 * in-process: no service start, so Android never re-evaluates its while-in-use camera access from the background.
 * Otherwise it starts the service as before ([CaptureService.ACTION_SCHEDULED]); [ForegroundFallback] covers a
 * background start that lacks the camera.
 */
class CaptureAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val live = CaptureService.liveInstance
        if (live != null) {
            if (!live.captureNow(skipDedupe = false)) Log.i(CaptureService.TAG, "alarm: capture already in flight")
            return
        }
        runCatching {
            context.startForegroundService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_SCHEDULED))
        }.onFailure {
            Log.w(CaptureService.TAG, "alarm: service start refused: ${it.javaClass.simpleName}")
            CaptureScheduler(context).scheduleNext(CaptureCadence.RETRY_MS) // keep the chain alive
            GlassesRuntime.get(context).onCaptureScheduled()
        }
    }
}
