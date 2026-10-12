package com.rokid.context.glasses

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class PulseCaptureScheduler(
    private val context: Context,
) {
    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun scheduleNext(delayMs: Long) {
        val triggerAtMs = System.currentTimeMillis() + delayMs.coerceAtLeast(1_000L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancelLegacyBroadcastAlarm(alarmManager)
        val pendingIntent = pulseServicePendingIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } else {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
            }.getOrElse {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
            }
        }
        prefs.edit()
            .putLong(KEY_NEXT_TRIGGER_AT_MS, triggerAtMs)
            .apply()
    }

    fun cancel() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancelLegacyBroadcastAlarm(alarmManager)
        val pendingIntent = pulseServicePendingIntent()
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        prefs.edit().remove(KEY_NEXT_TRIGGER_AT_MS).apply()
    }

    fun nextTriggerAtMs(): Long {
        return prefs.getLong(KEY_NEXT_TRIGGER_AT_MS, -1L)
    }

    private fun pulseServicePendingIntent(): PendingIntent {
        val intent = Intent(context, PulseKeepAliveService::class.java).apply {
            action = PulseKeepAliveService.ACTION_PULSE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(context, REQUEST_CODE_PULSE_SERVICE, intent, flags)
    }

    private fun cancelLegacyBroadcastAlarm(alarmManager: AlarmManager) {
        val legacyIntent = Intent(context, PulseAlarmReceiver::class.java).apply {
            action = PulseAlarmReceiver.ACTION_FIRE_PULSE
        }
        val legacyFlags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val legacyPendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_CODE_PULSE_BROADCAST, legacyIntent, legacyFlags)
            ?: return
        alarmManager.cancel(legacyPendingIntent)
        legacyPendingIntent.cancel()
    }

    companion object {
        private const val PREF_NAME = "rokid_pulse_scheduler"
        private const val KEY_NEXT_TRIGGER_AT_MS = "next_trigger_at_ms"
        private const val REQUEST_CODE_PULSE_SERVICE = 30071
        private const val REQUEST_CODE_PULSE_BROADCAST = 30071
    }
}
