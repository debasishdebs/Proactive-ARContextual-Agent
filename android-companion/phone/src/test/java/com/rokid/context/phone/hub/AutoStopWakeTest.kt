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

    // ---- Review fixes 2 (2026-11-07, Codex): the WorkManager wake and the live controller race for one rung ----
    // Deterministic interleaving: the winner parks inside its start (latch), the loser is let in only once it is BLOCKED on the claim lock, the clock is fixed.

    private fun awaitBlocked(t: Thread) {
        val end = System.nanoTime() + 5_000_000_000L
        while (t.state != Thread.State.BLOCKED) { check(t.isAlive && System.nanoTime() < end) { "loser never reached the claim lock (${t.state})" }; Thread.sleep(1) }
    }
    private class Race {
        @Volatile var serviceUp = false; val starts = java.util.concurrent.atomic.AtomicInteger()
        val inStart = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        // the controller loser parks at its first linkAllowed() read, i.e. after it read the old deadline outside the lock
        val ctlReady = java.util.concurrent.CountDownLatch(1); val ctlGo = java.util.concurrent.CountDownLatch(1); val parked = java.util.concurrent.atomic.AtomicBoolean()
        fun parkStart(issued: Boolean, up: Boolean): () -> Boolean = { inStart.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); starts.incrementAndGet(); if (up) serviceUp = true; issued }
    }
    private fun controller(s: AutoStopStore, race: Race, scheduled: MutableList<Long>, park: Boolean = false, start: () -> Boolean) = AutoStopController(
        now = { 1_000 * min }, minutes = { 20 }, store = s, hubRunning = { race.serviceUp }, link = { com.rokid.context.core.model.LinkState.OFFLINE }, worn = { null },
        inFront = { false }, lastFrontMs = { 0L }, busy = { false }, runActive = { false }, stopHub = {}, wakeHub = start, exempt = { true },
        linkAllowed = { if (park && race.parked.compareAndSet(false, true)) { race.ctlReady.countDown(); race.ctlGo.await(5, java.util.concurrent.TimeUnit.SECONDS) }; true },
        publishNote = {}, timeText = { "x" }, log = {}, scheduleWake = { synchronized(scheduled) { scheduled += it } },
        io = kotlin.coroutines.EmptyCoroutineContext, // the claim runs on the calling thread, so that thread is the one seen BLOCKED
    )
    private fun worker(s: AutoStopStore, race: Race, scheduled: MutableList<Long>, start: () -> Boolean) =
        AutoStopWake.onProcessStart(app, now = 1_000 * min, store = s, exempt = { true }, minutes = { 20 }, allowed = { true }, running = { race.serviceUp },
            schedule = { synchronized(scheduled) { scheduled += it } }, start = start)
    private fun sec(l: java.util.concurrent.CountDownLatch) = assertTrue(l.await(5, java.util.concurrent.TimeUnit.SECONDS))

    @Test fun aControllerWakeThatWinsLeavesTheRacingWorkerANoOpThatKeepsTheProbe() {
        val s = stopped(nextAt = 1_000 * min); val race = Race(); val scheduled = mutableListOf<Long>()
        val c = controller(s, race, scheduled, start = race.parkStart(issued = true, up = true))
        var ctl: Long? = -1L; var wk: Boolean? = null
        val a = Thread { ctl = c.evaluate() }.apply { start() }
        try {
            sec(race.inStart)
            val b = Thread { wk = worker(s, race, scheduled) { race.starts.incrementAndGet(); race.serviceUp = true; true } }.apply { start() }
            awaitBlocked(b); race.release.countDown(); b.join(5_000)
        } finally { race.release.countDown(); a.join(5_000) }
        assertEquals("one start, never two", 1, race.starts.get()); assertEquals("one rung consumed", 1, s.wakeStep())
        assertEquals(BackgroundPolicy.BUSY_RECHECK_MS, ctl); assertEquals(false, wk); assertEquals(emptyList<Long>(), scheduled)
        assertTrue("the loser did not clear the winner's probe", s.wakeProbe()); assertEquals(0L, s.autoStoppedAtMs())
    }

    /** The controller reads the deadline, the worker claims the rung and parks in its start, then the controller reaches the lock. */
    private fun workerWinsControllerRaces(issued: Boolean, up: Boolean): Triple<AutoStopStore, Race, Pair<Long?, List<Long>>> {
        val s = stopped(nextAt = 1_000 * min); val race = Race(); val scheduled = mutableListOf<Long>()
        val c = controller(s, race, scheduled, park = true) { race.starts.incrementAndGet(); race.serviceUp = true; true }
        var ctl: Long? = -1L
        val b = Thread { ctl = c.evaluate() }.apply { start() }
        val a = Thread { worker(s, race, scheduled, race.parkStart(issued, up)) }
        try {
            sec(race.ctlReady); a.start(); sec(race.inStart)
            race.ctlGo.countDown(); awaitBlocked(b)
        } finally { race.ctlGo.countDown(); race.release.countDown(); a.join(5_000); b.join(5_000) }
        return Triple(s, race, ctl to scheduled.toList())
    }

    @Test fun aWorkerWakeThatWinsLeavesTheRacingControllerANoOpThatKeepsTheProbe() {
        val (s, race, r) = workerWinsControllerRaces(issued = true, up = true)
        assertEquals("one start, never two", 1, race.starts.get()); assertEquals(1, s.wakeStep())
        assertTrue("the loser did not treat the running hub as a refusal", s.wakeProbe()); assertEquals(emptyList<Long>(), r.second)
        assertEquals("the loser just looks again soon", BackgroundPolicy.BUSY_RECHECK_MS, r.first)
    }

    @Test fun aRefusedWinnerMovesTheDeadlineSoTheLoserConsumesNothing() {
        val (s, race, r) = workerWinsControllerRaces(issued = false, up = false)
        assertEquals(1, race.starts.get()); assertEquals("one rung consumed", 1, s.wakeStep()); assertFalse(s.wakeProbe())
        assertEquals("the loser waits for the second rung", 120 * min, r.first); assertEquals("only the refused winner scheduled", listOf(120 * min), r.second)
        assertTrue("still stopped", s.autoStoppedAtMs() != 0L)
    }

    @Test fun aStartThatFindsTheServiceAlreadyRunningIsNotARefusal() {
        val s = stopped(nextAt = 1_000 * min); val race = Race(); val scheduled = mutableListOf<Long>()
        // ensureRunning returns false because the service came up meanwhile (it only starts a stopped one)
        assertFalse(worker(s, race, scheduled) { race.serviceUp = true; false })
        assertTrue("probe kept", s.wakeProbe()); assertEquals(emptyList<Long>(), scheduled); assertEquals(0L, s.autoStoppedAtMs())
        assertEquals(AutoStopWake.Claim.NONE, AutoStopWake.claim(1_000 * min, s, { true }, { 20 }, { true }, { race.serviceUp }, { true }, via = "t", log = {}, warn = {}))
    }
}
