package com.rokid.context.glasses.capture

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Exact-ish AlarmManager chain that wakes [CaptureService] (logic from PulseCaptureScheduler). */
class CaptureScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    fun scheduleNext(delayMs: Long) {
        val at = System.currentTimeMillis() + delayMs.coerceAtLeast(1_000L)
        val am = alarms ?: return
        val pi = captureIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
        prefs.edit().putLong(KEY_NEXT, at).apply()
    }

    /** Glasses clock; drives the Glance countdown and `Hello.nextCaptureAtMs`. */
    fun nextTriggerAtMs(): Long? = prefs.getLong(KEY_NEXT, -1L).takeIf { it > 0 }

    /** Arms the chain when nothing is pending (app start, camera grant) and retires the RFCOMM-era pulse alarm. */
    fun ensureScheduled() {
        cancelLegacyPulse()
        val next = nextTriggerAtMs()
        if (next == null || next < System.currentTimeMillis() - STALE_GRACE_MS) scheduleNext(FIRST_DELAY_MS)
    }

    fun cancel() {
        alarms?.cancel(captureIntent())
        prefs.edit().remove(KEY_NEXT).apply()
    }

    private fun captureIntent(): PendingIntent = PendingIntent.getForegroundService(
        context, REQUEST_CODE, Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_SCHEDULED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Named by string so this keeps working after Task 12 deletes the old service class. */
    private fun cancelLegacyPulse() {
        val legacy = Intent().setClassName(context, LEGACY_SERVICE).setAction(LEGACY_ACTION)
        val pi = PendingIntent.getForegroundService(context, LEGACY_REQUEST_CODE, legacy, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarms?.cancel(pi)
        pi.cancel()
    }

    companion object {
        private const val PREFS = "capture_scheduler"
        private const val KEY_NEXT = "next_trigger_at_ms"
        private const val REQUEST_CODE = 30_101
        private const val LEGACY_REQUEST_CODE = 30_071
        private const val LEGACY_SERVICE = "com.rokid.context.glasses.PulseKeepAliveService"
        private const val LEGACY_ACTION = "com.rokid.context.glasses.action.PULSE_SERVICE"
        const val FIRST_DELAY_MS = 30_000L
        private const val STALE_GRACE_MS = 5_000L
    }
}
