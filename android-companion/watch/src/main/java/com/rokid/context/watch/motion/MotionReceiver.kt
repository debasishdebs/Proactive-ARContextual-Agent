package com.rokid.context.watch.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Provenance: ported from live-fitness-tracker watch/.../motion/MotionReceiver.kt.
/** Re-registers passive monitoring after boot or an app update (it survives neither) and handles the 10-minute tick. */
class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val p = goAsync()
                val job = PassiveMotion.register(context)
                if (job == null) p.finish() else job.invokeOnCompletion { p.finish() }
            }
            ACTION_TICK -> {
                val p = goAsync()
                PassiveMotion.onEvents(context, listOf(MotionEvent.Tick)).invokeOnCompletion { p.finish() }
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.rokid.context.watch.MOTION_TICK"
    }
}
