package com.rokid.context.glasses.capture

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** What [CaptureScheduler.ensureScheduled] found: nothing stored, a live chain, or a chain a force-stop/reboot lost. */
enum class Rearm { FRESH, ARMED, MISSED }

/** Exact-ish AlarmManager chain that wakes [CaptureService] (logic from PulseCaptureScheduler). */
class CaptureScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    fun scheduleNext(delayMs: Long) = armAt(System.currentTimeMillis() + delayMs.coerceAtLeast(1_000L))

    private fun armAt(at: Long) {
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

    /**
     * Arms the chain when nothing is pending (app start, camera grant) and retires the RFCOMM-era pulse alarm. A
     * force-stop (Rokid's AssistServer closes apps that way) or a reboot drops the alarm but keeps the stored time, and
     * the app cannot ask AlarmManager what is armed, so a future stored time is re-armed as is (same PendingIntent:
     * idempotent). A stored time more than [STALE_GRACE_MS] past means the alarm was lost and captures were missed while
     * the app was stopped: it re-arms soon and reports [Rearm.MISSED] so the HUD can say so (Phase 8 C3). Nothing can
     * capture while the app is force-stopped; the platform blocks every restart until the user opens it.
     */
    fun ensureScheduled(): Rearm {
        cancelLegacyPulse()
        cancelLegacyServiceAlarm()
        val next = nextTriggerAtMs()
        val now = System.currentTimeMillis()
        return when {
            next == null -> { scheduleNext(FIRST_DELAY_MS); Rearm.FRESH }
            next < now - STALE_GRACE_MS -> { scheduleNext(1_000L); Rearm.MISSED }
            // Beyond any real delay: the glasses clock moved back.
            next > now + CaptureCadence.MAX_DELAY_MS -> { scheduleNext(FIRST_DELAY_MS); Rearm.ARMED }
            next > now -> { armAt(next); Rearm.ARMED }
            // Due a moment ago: maybe firing now (inFlight drops the extra start), maybe lost to a force-stop.
            else -> { scheduleNext(1_000L); Rearm.ARMED }
        }
    }

    fun cancel() {
        alarms?.cancel(captureIntent())
        cancelLegacyServiceAlarm()
        prefs.edit().remove(KEY_NEXT).apply()
    }

    /** A broadcast, so a running keep-alive [CaptureService] takes the capture in-process ([CaptureAlarmReceiver]). */
    private fun captureIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, CaptureAlarmReceiver::class.java).setAction(CaptureService.ACTION_SCHEDULED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Before the keep-alive fix the alarm started CaptureService directly; retire it so no duplicate fires. */
    private fun cancelLegacyServiceAlarm() {
        val legacy = Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_SCHEDULED)
        val pi = PendingIntent.getForegroundService(context, REQUEST_CODE, legacy, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarms?.cancel(pi)
        pi.cancel()
    }

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
