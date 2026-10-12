package com.rokid.context.glasses.capture

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CaptureSchedulerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val am get() = ctx.getSystemService(AlarmManager::class.java)
    private fun alarmIntents() = shadowOf(am).scheduledAlarms.map { shadowOf(it.operation).savedIntent }

    @Test fun ensureScheduledArmsOneAlarmForCaptureService() {
        CaptureScheduler(ctx).ensureScheduled()
        assertEquals(CaptureService::class.java.name, alarmIntents().single().component!!.className)
        assertNotNull(CaptureScheduler(ctx).nextTriggerAtMs())
    }

    @Test fun ensureScheduledKeepsAPendingAlarm() {
        val s = CaptureScheduler(ctx)
        s.scheduleNext(60_000)
        val at = s.nextTriggerAtMs()
        s.ensureScheduled()
        assertEquals(at, s.nextTriggerAtMs())
        assertEquals(1, alarmIntents().size)
    }

    /**
     * A force-stop (Rokid's AssistServer closes apps that way; `am force-stop`) or a reboot removes the alarm but keeps
     * the stored trigger time, and on the RG-glasses (API 32) the PendingIntent survives too; seen on device, the
     * chain then stayed dead after relaunch. Re-arming keeps the stored time.
     */
    @Test fun ensureScheduledRearmsAnAlarmLostToAForceStop() {
        val s = CaptureScheduler(ctx)
        s.scheduleNext(60_000)
        val at = s.nextTriggerAtMs()
        val intent = Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_SCHEDULED)
        am.cancel(PendingIntent.getForegroundService(ctx, 30_101, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)!!)
        assertEquals(0, alarmIntents().size)

        s.ensureScheduled()
        val alarm = shadowOf(am).scheduledAlarms.single()
        assertEquals(CaptureService::class.java.name, alarmIntents().single().component!!.className)
        assertEquals(at, alarm.getTriggerAtMs())
        assertEquals(at, s.nextTriggerAtMs())
    }

    /** The pre-CXR app armed PulseKeepAliveService; that chain must not keep running beside CaptureService. */
    @Test fun theLegacyPulseAlarmIsRetired() {
        val legacy = Intent().setClassName(ctx, "com.rokid.context.glasses.PulseKeepAliveService").setAction("com.rokid.context.glasses.action.PULSE_SERVICE")
        am.set(AlarmManager.RTC_WAKEUP, 1_000_000, PendingIntent.getForegroundService(ctx, 30_071, legacy, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        CaptureScheduler(ctx).ensureScheduled()
        assertTrue(alarmIntents().none { it.action == "com.rokid.context.glasses.action.PULSE_SERVICE" })
    }

    private fun storeNext(at: Long) = ctx.getSharedPreferences("capture_scheduler", 0).edit().putLong("next_trigger_at_ms", at).commit()

    /** A stored time beyond any real delay (the glasses clock jumped back) would stall capture for that long. */
    @Test fun aStoredTimeBeyondTheLongestDelayIsStale() {
        val now = System.currentTimeMillis()
        storeNext(now + CaptureCadence.MAX_DELAY_MS + 60_000)
        CaptureScheduler(ctx).ensureScheduled()
        val at = CaptureScheduler(ctx).nextTriggerAtMs()!!
        assertTrue("re-armed at $at", at <= System.currentTimeMillis() + CaptureScheduler.FIRST_DELAY_MS)
        assertEquals(1, alarmIntents().size)
    }

    /** Due a moment ago: the alarm may have been lost to a force-stop just before it fired, so arm again soon. */
    @Test fun aTimeDueWithinTheGraceIsArmedSoon() {
        val now = System.currentTimeMillis()
        storeNext(now - 2_000)
        CaptureScheduler(ctx).ensureScheduled()
        val at = CaptureScheduler(ctx).nextTriggerAtMs()!!
        assertTrue("armed at $at", at in now..System.currentTimeMillis() + 1_000)
        assertEquals(1, alarmIntents().size)
    }
}
