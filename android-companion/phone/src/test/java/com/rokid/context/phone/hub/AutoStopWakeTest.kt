package com.rokid.context.phone.hub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cold-process wake path (a 15 min job starts the process) and the stores' persistence and backup rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AutoStopWakeTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val min = 60_000L

    @Test fun aProcessStartDoesNothingWhenTheHubDidNotStopItself() {
        var starts = 0
        assertFalse(AutoStopWake.onProcessStart(app, now = 10_000 * min, store = InMemoryAutoStopStore()) { starts++; true }); assertEquals(0, starts)
    }

    @Test fun withoutTheExemptionAProcessStartNeverStartsTheHub() {
        val s = InMemoryAutoStopStore().apply { markAutoStopped(1L, BackgroundPolicy.Away.NOT_WORN, 0L, 0L) }
        var starts = 0
        assertFalse("Robolectric: not on the allowlist", AutoStopWake.exempt(app))
        assertFalse(AutoStopWake.onProcessStart(app, now = 10_000 * min, store = s) { starts++; true }); assertEquals(0, starts); assertEquals("still stopped, nothing consumed", 0, s.wakeStep())
    }

    @Test fun thePrefsStorePersistsEverythingAndALaterInstanceSeesIt() {
        val prefs = app.getSharedPreferences("hub_auto_stop_test", Context.MODE_PRIVATE); prefs.edit().clear().commit()
        val a = PrefsAutoStopStore { prefs }
        a.setManualStop(true); a.markAutoStopped(100L, BackgroundPolicy.Away.DISCONNECTED, 40L, 900L); a.wakeAttempted(1_000L)
        val b = PrefsAutoStopStore { prefs }
        assertTrue(b.manualStop()); assertEquals(100L, b.autoStoppedAtMs()); assertEquals(BackgroundPolicy.Away.DISCONNECTED, b.reason()); assertEquals(40L, b.awaySinceMs())
        assertEquals(1, b.wakeStep()); assertEquals(1_000L, b.nextWakeAtMs())
        b.userStarted(); assertFalse(a.manualStop()); assertEquals(0L, a.autoStoppedAtMs()); assertEquals(0, a.wakeStep())
        assertEquals("hub_auto_stop", PrefsAutoStopStore.PREFS)
    }

    @Test fun theAutoStopMinutesSettingIsHeldToItsRangeAndSurvivesAReload() {
        val prefs = app.getSharedPreferences("capture_timing_test", Context.MODE_PRIVATE); prefs.edit().clear().commit()
        val s = com.rokid.context.phone.ui.settings.capture.CaptureTimingStore(prefs)
        assertEquals("default", 20, s.autoStopMin.value)
        s.setAutoStopMin(45); assertEquals(45, com.rokid.context.phone.ui.settings.capture.CaptureTimingStore(prefs).autoStopMin.value)
        s.setAutoStopMin(1); assertEquals(5, s.autoStopMin.value); s.setAutoStopMin(500); assertEquals(120, s.autoStopMin.value)
        s.setAutoStopMin(0); assertEquals("Never", 0, com.rokid.context.phone.ui.settings.capture.CaptureTimingStore(prefs).autoStopMinNow())
        prefs.edit().putInt("auto_stop_min", 7_000).commit(); assertEquals(120, com.rokid.context.phone.ui.settings.capture.CaptureTimingStore(prefs).autoStopMin.value)
        prefs.edit().putString("auto_stop_min", "x").commit(); assertEquals(20, com.rokid.context.phone.ui.settings.capture.CaptureTimingStore(prefs).autoStopMin.value)
    }

    // ---- Review fixes 2026-11-07 ----
    private fun stopped(nextAt: Long) = InMemoryAutoStopStore().apply { markAutoStopped(1L, BackgroundPolicy.Away.DISCONNECTED, 0L, nextAt) }
    private fun wake(s: AutoStopStore, now: Long, scheduled: MutableList<Long>, issued: Boolean = true, starts: IntArray = IntArray(1)) =
        AutoStopWake.onProcessStart(app, now = now, store = s, exempt = { true }, minutes = { 20 }, allowed = { true }, running = { false },
            schedule = { scheduled += it }) { starts[0]++; issued }

    @Test fun anEarlyProcessStartSchedulesAWakeForTheRemainingTime() {
        val s = stopped(nextAt = 1_000 * min); val scheduled = mutableListOf<Long>(); val starts = IntArray(1)
        assertFalse(wake(s, 970 * min, scheduled, starts = starts)); assertEquals(0, starts[0])
        assertEquals("30 min left", listOf(30 * min), scheduled); assertEquals("nothing consumed", 0, s.wakeStep())
    }

    @Test fun aColdWakeSavesTheProbeSoTheNewControllerLooksForFiveMinutes() {
        val s = stopped(nextAt = 1_000 * min); val scheduled = mutableListOf<Long>()
        assertTrue(wake(s, 1_000 * min, scheduled)); assertTrue(s.wakeProbe()); assertEquals(0L, s.autoStoppedAtMs())
        var stops = 0
        val c = AutoStopController(
            now = { 1_000 * min }, minutes = { 20 }, store = s, hubRunning = { true }, link = { com.rokid.context.core.model.LinkState.OFFLINE }, worn = { null },
            inFront = { false }, lastFrontMs = { 0L }, busy = { false }, runActive = { false }, stopHub = { stops++ }, wakeHub = { true }, exempt = { true },
            linkAllowed = { true }, publishNote = {}, timeText = { "x" }, log = {},
        )
        assertEquals("the 5 min probe, not the full 20", BackgroundPolicy.WAKE_PROBE_MS, c.evaluate())
    }

    @Test fun aRefusedColdWakeSchedulesTheNextRung() {
        val s = stopped(nextAt = 1_000 * min); val scheduled = mutableListOf<Long>()
        assertFalse(wake(s, 1_000 * min, scheduled, issued = false)); assertEquals(listOf(120 * min), scheduled)
        assertTrue("still stopped", s.autoStoppedAtMs() != 0L); assertFalse(s.wakeProbe())
    }

    @Test fun aClockSetBackDoesNotKeepTheColdWakeWaiting() {
        val s = stopped(nextAt = 20_000 * min); val scheduled = mutableListOf<Long>()
        assertTrue("the deadline lies beyond any rung: the clock went back", wake(s, 1_000 * min, scheduled)); assertEquals(emptyList<Long>(), scheduled)
    }

    @Test fun theWakeIsScheduledAsOneUniqueWorkManagerJobAndTheProbePersists() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        AutoStopWake.schedule(app, 30 * min); AutoStopWake.schedule(app, 60 * min)
        val infos = androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork(AutoStopWake.WORK_NAME).get()
        assertEquals("replaced, not stacked", 1, infos.count { !it.state.isFinished })
        val prefs = app.getSharedPreferences("hub_auto_stop_probe_test", Context.MODE_PRIVATE); prefs.edit().clear().commit()
        PrefsAutoStopStore { prefs }.setWakeProbe(true); assertTrue(PrefsAutoStopStore { prefs }.wakeProbe())
        PrefsAutoStopStore { prefs }.userStarted(); assertFalse(PrefsAutoStopStore { prefs }.wakeProbe())
    }
}
