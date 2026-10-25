package com.rokid.context.connectors.actions.executors

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderExecutorTest {
    @get:org.junit.Rule val pcLogShadow = com.rokid.context.connectors.PcLogToShadowRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val am = ctx.getSystemService(AlarmManager::class.java)
    private val exec = ReminderExecutor(ctx)
    private val due = System.currentTimeMillis() + 3_600_000

    @After fun reset() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
    }

    /** Final review I2: the alarm is still armed, but the result carries NOTIFICATIONS_OFF so the user is told. */
    @Test fun notificationsOffArmsButWarns() = runBlocking {
        shadowOf(ctx.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        assertEquals(ExecResult.Done("alarm:p1", FailCode.NOTIFICATIONS_OFF), exec.execute(reminder("p1", due)))
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
    }

    /** OBS-2 (device run 2026-10-14): tools/device-checklist.sh P7-6 needs a success marker; it carries no user text. */
    @Test fun successLogsReminderArmedWithoutTheTitle() = runBlocking {
        org.robolectric.shadows.ShadowLog.clear()
        assertEquals(ExecResult.Done("alarm:p1"), exec.execute(reminder("p1", due)))
        val lines = org.robolectric.shadows.ShadowLog.getLogsForTag("ReminderExecutor").map { it.msg }
        assertEquals(listOf("reminder armed; code=NONE"), lines)
    }

    @Test fun reExecuteDoesNotDuplicateAlarm() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val p = reminder("p1", due)
        assertEquals(ExecResult.Done("alarm:p1"), exec.execute(p))
        assertEquals(ExecResult.Done("alarm:p1"), exec.execute(p))
        val alarms = shadowOf(am).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(due, alarms[0].triggerAtMs)
        assertTrue(alarms[0].isAllowWhileIdle)
    }

    @Test fun inexactWhenExactNotAllowed() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(ExecResult.Done("alarm:p1"), exec.execute(reminder("p1", due)))
        val a = shadowOf(am).scheduledAlarms.single()
        assertEquals(ReminderExecutor.WINDOW_MS, a.windowLengthMs)
        assertEquals(due, a.triggerAtMs)
    }

    @Test fun undoCancelsAlarm() = runBlocking {
        val p = reminder("p1", due)
        exec.execute(p)
        exec.execute(reminder("p2", due))
        assertTrue(exec.undo(p))
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
    }
}
