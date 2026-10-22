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
    private class Rig(var nowMs: Long = 10 * 60_000L) {
        var enabled = true; var capture = true; var running = true
        var link = LinkState.CONNECTED
        var heard: Long? = null
        var standing = false
        var relaunchResult: String? = null
        var starts = 0; var reconnects = 0; var probes = 0; var relaunches = 0
        val notes = ArrayList<String?>()
        val w = GlassesWatchdog(
            now = { nowMs }, enabled = { enabled }, captureActive = { capture }, hubRunning = { running }, link = { link },
            lastHeardAtMs = { heard }, standingDown = { standing },
            startHub = { starts++ }, reconnect = { reconnects++ }, probeAlive = { probes++ }, relaunch = { relaunches++; relaunchResult },
            publish = { notes += it },
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
}
