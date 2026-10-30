package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone-side watchdog: the periodic trigger the glasses cannot supply once Rokid force-stops the app. */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassesWatchdogTest {
    private class Rig(var nowMs: Long = 10 * 60_000L, store: HubRestartStore = InMemoryHubRestartStore()) {
        var enabled = true; var capture = true; var running = true
        var paired = true; var hubNeeded = true; var stops = 0
        var link = LinkState.CONNECTED
        var heard: Long? = null
        var standing = false
        var relaunchResult: String? = null
        var starts = 0; var reconnects = 0; var probes = 0; var relaunches = 0
        val notes = ArrayList<String?>()
        val logs = ArrayList<String>(); val warns = ArrayList<String>()
        val w = GlassesWatchdog(
            now = { nowMs }, enabled = { enabled }, captureActive = { capture }, hubRunning = { running }, link = { link },
            lastHeardAtMs = { heard }, standingDown = { standing },
            startHub = { starts++ }, reconnect = { reconnects++ }, probeAlive = { probes++ }, relaunch = { relaunches++; relaunchResult },
            publish = { notes += it },
            log = { logs += it }, warn = { warns += it },
            paired = { paired }, hubNeeded = { hubNeeded }, stopHub = { stops++; running = false }, hubStore = store,
        )
        fun silentFor(min: Long) { heard = nowMs - min * 60_000L }
    }

    @Test fun recentHeartbeatDoesNothing() {
        val r = Rig().apply { silentFor(1) }
        assertEquals(GlassesWatchdog.Verdict.Ok, r.w.tick())
        assertEquals(0, r.relaunches + r.reconnects + r.probes); assertNull(r.notes.last())
    }

    @Test fun silentWhileLinkedRelaunchesThroughTheRelauncher() {
        val r = Rig().apply { silentFor(4) }
        assertEquals(GlassesWatchdog.Verdict.Reopening(4 * 60_000L), r.w.tick())
        assertEquals(1, r.relaunches); assertEquals(0, r.reconnects)
        assertEquals("Glasses app not running, reopening… · last heard 4 min ago", r.notes.last())
    }

    @Test fun belowThresholdIsQuiet() {
        val r = Rig().apply { silentFor(2) }
        r.w.tick(); assertEquals(0, r.relaunches)
    }

    @Test fun linkDownReconnectsAndNeverRelaunches() {
        val r = Rig().apply { silentFor(5); link = LinkState.OFFLINE }
        assertTrue(r.w.tick() is GlassesWatchdog.Verdict.LinkDown)
        assertEquals(1, r.reconnects); assertEquals(0, r.relaunches)
        assertTrue(r.notes.last()!!.startsWith("Glasses not connected"))
    }

    @Test fun standDownNeverLaunchesOrReconnects() {
        val r = Rig().apply { silentFor(30); standing = true; link = LinkState.CLOSED_ON_GLASSES }
        assertTrue(r.w.tick() is GlassesWatchdog.Verdict.StoppedByYou)
        assertEquals(0, r.relaunches + r.reconnects + r.probes)
        assertEquals("Glasses app stopped by you · last heard 30 min ago", r.notes.last())
    }

    @Test fun relauncherRefusalKeepsTheReasonAndCountsAsAnAttempt() {
        val r = Rig().apply { silentFor(10); relaunchResult = "call active" }
        assertEquals(GlassesWatchdog.Verdict.Waiting("call active", 10 * 60_000L), r.w.tick())
        assertEquals(1, r.relaunches) // the relauncher owns the call guard; the watchdog never launches by itself
    }

    @Test fun hourlyCapIsReportedAndNotLaunched() {
        val r = Rig().apply { silentFor(10); relaunchResult = "hourly limit" }
        r.w.tick()
        assertTrue(r.notes.last()!!.contains("limit"))
    }

    @Test fun settingOffOrCaptureInactiveDoesNothing() {
        val off = Rig().apply { silentFor(30); enabled = false }
        assertEquals(GlassesWatchdog.Verdict.Off, off.w.tick()); assertEquals(0, off.relaunches + off.reconnects + off.starts)
        val idle = Rig().apply { silentFor(30); capture = false }
        assertEquals(GlassesWatchdog.Verdict.Off, idle.w.tick()); assertEquals(0, idle.relaunches + idle.reconnects)
        assertNull(off.notes.last()); assertNull(idle.notes.last())
    }

    @Test fun deadHubServiceIsRestarted() {
        val r = Rig().apply { running = false }
        assertEquals(GlassesWatchdog.Verdict.HubDown, r.w.tick())
        assertEquals(1, r.starts); assertEquals(0, r.relaunches)
    }

    @Test fun minimisedSilenceProbesInsteadOfLaunching() {
        val r = Rig().apply { silentFor(5); link = LinkState.MINIMISED }
        r.w.tick(); assertEquals(1, r.probes); assertEquals(0, r.relaunches)
    }

    @Test fun neverHeardCountsFromTheWatchdogsFirstTick() {
        val r = Rig(); r.w.tick(); assertEquals(0, r.relaunches)
        r.nowMs += 3 * 60_000L; r.w.tick(); assertEquals(1, r.relaunches)
    }

    @Test fun tickLoopRunsEveryPeriodAndStops() = runTest {
        val r = Rig().apply { silentFor(10) }
        val job = r.w.start(backgroundScope, periodMs = 150_000L)
        runCurrent(); assertEquals(1, r.relaunches)
        advanceTimeBy(150_100); assertEquals(2, r.relaunches)
        job.cancel(); advanceTimeBy(600_000); assertEquals(2, r.relaunches)
    }

    // ---- 2026-10-25: the phone log had no watchdog line, so a 21 min dead gap could not be explained. Every decision is a counts-only line now. ----

    @Test fun offNamesItsCause() {
        val r = Rig().apply { enabled = false }
        r.w.tick(); assertTrue(r.logs.toString(), r.logs.last().contains("verdict=Off why=setting_off"))
        r.enabled = true; r.capture = false; r.w.tick()
        assertTrue(r.logs.toString(), r.logs.last().contains("verdict=Off why=capture_inactive"))
    }

    @Test fun aSilentGlassesAppLogsTheReopenResultAndTheLinkState() {
        val r = Rig().apply { silentFor(4); relaunchResult = "hourly limit" }
        r.w.tick()
        val l = r.logs.last()
        assertTrue(l, l.contains("verdict=Waiting") && l.contains("relaunch=hourly limit") && l.contains("link=CONNECTED") && l.contains("silent_s=240"))
        r.relaunchResult = null; r.nowMs += 150_000; r.silentFor(4); r.w.tick()
        assertTrue(r.logs.toString(), r.logs.any { it.contains("verdict=Reopening") && it.contains("relaunch=reopened") })
    }

    @Test fun anUnchangedVerdictIsNotLoggedEveryTickButEveryTenth() {
        val r = Rig().apply { silentFor(1) }
        repeat(25) { r.nowMs += 150_000; r.heard = r.nowMs - 60_000; r.w.tick() }
        assertTrue("first + 3 repeats at most: ${r.logs.size}", r.logs.size in 2..4)
    }

    /** The phone process can be suspended (Doze): a tick far later than the period is the proof, and it is a warning so it reaches the file. */
    @Test fun aLateTickIsAWarningWithTheGap() {
        val r = Rig().apply { silentFor(1) }
        r.w.tick(); assertTrue(r.warns.isEmpty())
        r.nowMs += 21 * 60_000L; r.heard = r.nowMs - 60_000; r.w.tick()
        assertTrue(r.warns.toString(), r.warns.single().contains("late tick gap_s=1260"))
    }

    @Test fun hubDownIsAWarning() {
        val r = Rig().apply { running = false }
        r.w.tick(); assertTrue(r.warns.toString(), r.warns.any { it.contains("hub down") })
    }

    // ---- 2026-10-25 ruling: the silence threshold and the tick are stored settings; the defaults are the old constants ----

    @Test fun theDefaultsAreTheOldConstants() {
        assertEquals(3 * 60_000L, GlassesWatchdog.SILENT_MS); assertEquals(150_000L, GlassesWatchdog.TICK_MS)
        // a watchdog built without the settings acts at exactly 3 minutes of silence, as before
        val r = Rig().apply { silentFor(3) }; assertEquals(GlassesWatchdog.Verdict.Reopening(3 * 60_000L), r.w.tick())
        val q = Rig().apply { heard = nowMs - (3 * 60_000L - 1) }; assertEquals(GlassesWatchdog.Verdict.Ok, q.w.tick())
    }

    private class Cfg(var silent: Long, var period: Long = 150_000L) {
        var now = 10 * 60_000L; var heard: Long? = null; var relaunches = 0
        val w = GlassesWatchdog(
            now = { now }, enabled = { true }, captureActive = { true }, hubRunning = { true }, link = { LinkState.CONNECTED }, lastHeardAtMs = { heard }, standingDown = { false },
            startHub = {}, reconnect = {}, probeAlive = {}, relaunch = { relaunches++; null }, publish = {}, log = {}, warn = {},
            silentMs = { silent }, periodMs = { period },
        )
    }

    @Test fun aChangedThresholdAppliesOnTheNextTick() {
        val c = Cfg(silent = 10 * 60_000L); c.heard = c.now - 4 * 60_000L
        assertEquals("10 min: 4 min of silence is fine", GlassesWatchdog.Verdict.Ok, c.w.tick()); assertEquals(0, c.relaunches)
        c.silent = 2 * 60_000L // the user picks 2 min
        assertEquals(GlassesWatchdog.Verdict.Reopening(4 * 60_000L), c.w.tick()); assertEquals(1, c.relaunches)
        c.silent = 5 * 60_000L
        assertEquals("back to 5 min: quiet again", GlassesWatchdog.Verdict.Ok, c.w.tick())
    }

    @Test fun aChangedTickAppliesOnTheNextSleepAndPauseStillSilencesIt() = runTest {
        val c = Cfg(silent = 60_000L, period = 60_000L); c.heard = c.now - 10 * 60_000L
        val job = c.w.start(backgroundScope)
        runCurrent(); assertEquals(1, c.relaunches)
        advanceTimeBy(60_100); assertEquals("60 s tick", 2, c.relaunches)
        c.period = 300_000L // the user picks 300 s: the sleep that is running now was 60 s, the next one is 300 s
        advanceTimeBy(60_000); assertEquals(3, c.relaunches)
        advanceTimeBy(299_000); assertEquals("no tick inside the new 300 s", 3, c.relaunches)
        advanceTimeBy(2_000); assertEquals(4, c.relaunches)
        job.cancel()
    }

    @Test fun offWhileAnExplicitPauseIsActiveWhateverTheSettings() {
        val c = Cfg(silent = 60_000L); c.heard = c.now - 60 * 60_000L
        val paused = GlassesWatchdog(now = { c.now }, enabled = { true }, captureActive = { false }, hubRunning = { true }, link = { LinkState.CONNECTED }, lastHeardAtMs = { c.heard },
            standingDown = { false }, startHub = {}, reconnect = {}, probeAlive = {}, relaunch = { c.relaunches++; null }, publish = {}, log = {}, warn = {}, silentMs = { 60_000L })
        assertEquals(GlassesWatchdog.Verdict.Off, paused.tick()); assertEquals(0, c.relaunches)
    }

    // ---- v0.9.9 battery: nothing without a pair or with the setting off; away it backs off 5/15/30/60 min; a positive signal resets it ----

    @Test fun noPairDoesNothingNotEvenAHubStart() {
        val r = Rig().apply { paired = false; hubNeeded = false; running = false; link = LinkState.OFFLINE; silentFor(60) }
        assertEquals(GlassesWatchdog.Verdict.Off, r.w.tick())
        assertEquals(0, r.starts + r.reconnects + r.relaunches + r.probes + r.stops); assertNull(r.notes.last())
        assertTrue(r.logs.last(), r.logs.last().contains("why=no_pair"))
    }

    @Test fun settingOffNeverReconnectsOrRestartsTheHubEvenWhenItIsDown() {
        val r = Rig().apply { enabled = false; hubNeeded = false; running = false; link = LinkState.OFFLINE; silentFor(60) }
        r.w.tick(); r.nowMs += 3_600_000; r.silentFor(120); r.w.tick()
        assertEquals(0, r.starts + r.reconnects + r.relaunches)
    }

    @Test fun aHubNothingNeedsIsStoppedNotRestarted() {
        val r = Rig().apply { enabled = false; hubNeeded = false; running = true }
        assertEquals(GlassesWatchdog.Verdict.Off, r.w.tick()); assertEquals(1, r.stops); assertEquals(0, r.starts)
        r.w.tick(); assertEquals("already stopped: nothing more", 1, r.stops); assertEquals(0, r.starts)
    }

    @Test fun aContextSourceKeepsTheHubButTheGlassesMachineryStaysOff() {
        val r = Rig().apply { enabled = false; hubNeeded = true; running = true; link = LinkState.OFFLINE; silentFor(60) }
        r.w.tick(); assertEquals(0, r.stops + r.reconnects + r.relaunches)
    }

    @Test fun awayReconnectsOnceThenBacksOffFiveFifteenThirtySixtyAndTheNoteStays() {
        val r = Rig().apply { link = LinkState.OFFLINE; silentFor(5) }
        r.w.tick(); assertEquals(1, r.reconnects)
        val waits = ArrayList<Long>()
        var last = r.nowMs
        repeat(4) {
            // ticks every 150 s until the next reconnect
            val before = r.reconnects
            while (r.reconnects == before) {
                r.nowMs += 150_000L; r.heard = r.nowMs - 10 * 60_000L; r.w.tick()
                assertTrue("never silent", r.notes.last()!!.startsWith("Glasses not connected \u00b7 last heard"))
            }
            waits += r.nowMs - last; last = r.nowMs
        }
        assertEquals(listOf(5L, 15L, 30L, 60L).map { it * 60_000L }, waits)
        // capped at 60 min
        val before = r.reconnects
        r.nowMs += 59 * 60_000L; r.heard = r.nowMs - 10 * 60_000L; r.w.tick(); assertEquals(before, r.reconnects)
        r.nowMs += 60_000L; r.heard = r.nowMs - 10 * 60_000L; r.w.tick(); assertEquals(before + 1, r.reconnects)
        assertTrue(r.logs.any { it.contains("action=backoff") }); assertTrue(r.logs.any { it.contains("action=reconnect") })
    }

    @Test fun aPositiveSignalResetsTheBackoff() {
        val r = Rig().apply { link = LinkState.OFFLINE; silentFor(5) }
        r.w.tick(); r.nowMs += 150_000L; r.heard = r.nowMs - 10 * 60_000L; r.w.tick()
        assertEquals("backed off", 1, r.reconnects)
        r.w.onPositiveSignal(); r.w.tick(); assertEquals("due again at once", 2, r.reconnects)
        r.nowMs += 150_000L; r.heard = r.nowMs - 10 * 60_000L; r.w.tick(); assertEquals("and the ladder starts at 5 min again", 2, r.reconnects)
    }

    @Test fun theGlassesComingBackResetsTheLadder() {
        val r = Rig().apply { link = LinkState.OFFLINE; silentFor(5) }
        r.w.tick(); r.link = LinkState.CONNECTED; r.heard = r.nowMs; r.w.tick()
        r.link = LinkState.OFFLINE; r.nowMs += 150_000L; r.heard = r.nowMs - 10 * 60_000L; r.w.tick()
        assertEquals("a fresh loss reconnects at once", 2, r.reconnects)
    }

    @Test fun awayAHubDownRestartFollowsTheBackoffToo() {
        val r = Rig().apply { link = LinkState.OFFLINE; running = false }
        r.w.tick(); assertEquals(1, r.starts)
        r.nowMs += 150_000L; r.w.tick(); assertEquals("backed off", 1, r.starts)
        assertTrue("the status line is not silent", r.notes.last()!!.startsWith("Glasses not connected"))
        r.nowMs += 120_000L; r.w.tick(); assertEquals("270 s in: still backed off", 1, r.starts)
        r.nowMs += 60_000L; r.w.tick(); assertEquals("5 min passed", 2, r.starts)
    }

    @Test fun theLinkBeingThereRestartsADeadHubAtOnce() {
        val r = Rig().apply { running = false; link = LinkState.CLOSED_ON_GLASSES }
        r.w.tick(); r.nowMs += 150_000L; r.w.tick(); assertEquals("a start is attempted, then backs off (it may be refused)", 1, r.starts)
    }

    @Test fun aRefusedHubStartRepeatsAtTheBackoffCadenceAndResetsOnSignals() {
        val r = Rig().apply { running = false; link = LinkState.OFFLINE } // startHub never brings the hub up
        val times = ArrayList<Long>(); var last = r.starts; val t0 = r.nowMs
        while (times.size < 5) { r.w.tick(); if (r.starts != last) { last = r.starts; times += r.nowMs - t0 }; r.nowMs += 60_000L }
        assertEquals(listOf(0L, 5L, 20L, 50L, 110L).map { it * 60_000L }, times)
        val n = r.starts
        r.w.onPositiveSignal(); r.w.tick(); assertEquals("app foreground / Reconnect", n + 1, r.starts)
        r.nowMs += 60_000L
        r.w.onHubWanted(); r.w.tick(); assertEquals("a context source switched on", n + 2, r.starts)
        r.w.tick(); assertEquals(n + 2, r.starts)
        r.nowMs += 60_000L
        r.running = true; r.w.tick(); r.running = false; r.w.tick(); assertEquals("the hub running resets it", n + 3, r.starts)
    }

    @Test fun resetSignalsNeverBypassTheMinimumIntervalBetweenHubStarts() {
        val r = Rig().apply { running = false; link = LinkState.OFFLINE }
        r.w.tick(); assertEquals(1, r.starts)
        r.w.onPositiveSignal(); r.w.tick(); r.w.onHubWanted(); r.w.tick(); assertEquals("reset signals within 60 s", 1, r.starts)
        r.nowMs += 59_000L; r.w.onPositiveSignal(); r.w.tick(); assertEquals(1, r.starts)
        r.nowMs += 1_000L; r.w.onPositiveSignal(); r.w.tick(); assertEquals("60 s passed and the step was reset", 2, r.starts)
    }

    @Test fun theHubRestartLadderSurvivesProcessDeath() {
        val store = InMemoryHubRestartStore()
        fun rig() = Rig(store = store).apply { running = false; link = LinkState.OFFLINE }
        val a = rig(); a.w.tick(); assertEquals(1, a.starts)
        // the process dies and a new watchdog starts with the same persisted state, 2 min later: still inside the 5 min step
        val b = rig(); b.nowMs = a.nowMs + 120_000L; b.w.tick(); assertEquals("not reset by a restart", 0, b.starts)
        b.nowMs = a.nowMs + 5 * 60_000L; b.w.tick(); assertEquals(1, b.starts)
    }

    @Test fun anUnneededHubIsNeverStarted() {
        val r = Rig().apply { running = false; hubNeeded = false }
        repeat(5) { r.w.tick(); r.nowMs += 3_600_000L }; assertEquals(0, r.starts)
    }

    @Test fun theLoopSleepsLongWithNoPairAndAPositiveSignalWakesIt() = runTest {
        val r = Rig().apply { paired = false; hubNeeded = false; running = false }
        val job = r.w.start(backgroundScope)
        runCurrent(); val ticks = r.notes.size; assertEquals(1, ticks)
        advanceTimeBy(20 * 60_000L); assertEquals("no ticks for 20 min", ticks, r.notes.size)
        r.paired = true; r.hubNeeded = true; r.running = true; r.silentFor(10); r.w.onPositiveSignal(); runCurrent()
        assertEquals("woken at once", 1, r.relaunches)
        job.cancel()
    }

    @Test fun awayTheLoopDoesNotTickBetweenBackoffAttempts() = runTest {
        val r = Rig().apply { link = LinkState.OFFLINE; silentFor(10) }
        val job = r.w.start(backgroundScope)
        runCurrent(); assertEquals(1, r.reconnects)
        assertEquals(1, r.notes.size)
        advanceTimeBy(4 * 60_000L); assertEquals("no tick inside the 5 min back-off", 1, r.notes.size)
        advanceTimeBy(61_000L); assertEquals("then one", 2, r.notes.size)
        job.cancel()
    }

    /** Review fix: a needed hub (watch_app or another phone source) is restarted whatever the glasses gate says; the glasses reconnect stays gated. */
    @Test fun aNeededHubIsRestartedWhateverTheGlassesGate() {
        data class Case(val paired: Boolean, val enabled: Boolean, val capture: Boolean)
        for (c in listOf(Case(false, true, true), Case(true, false, true), Case(true, true, false), Case(false, false, true))) {
            val r = Rig().apply { paired = c.paired; enabled = c.enabled; capture = c.capture; hubNeeded = true; running = false; link = LinkState.OFFLINE; silentFor(60) }
            r.w.tick()
            assertEquals("$c restarts the hub", 1, r.starts); assertEquals("$c: glasses machinery stays off", 0, r.reconnects + r.relaunches + r.probes)
        }
        val unneeded = Rig().apply { paired = false; hubNeeded = false; running = false }
        unneeded.w.tick(); assertEquals(0, unneeded.starts)
    }
}
