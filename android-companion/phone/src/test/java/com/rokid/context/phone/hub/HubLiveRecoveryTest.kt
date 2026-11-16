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

/** v1.1 T3: the hub-live flag and the recovery of a LOST hub (reducer cases over [AutoStopMachine], and the prefs record). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HubLiveRecoveryTest {
    private val min = 60_000L
    private val t0 = 10_000 * min
    private val ok = WakeInputs(exempt = true, minutes = 20, linkAllowed = true, serviceRunning = false)

    private fun state(snap: AutoStopSnapshot, w: WakeInputs = ok) = AutoStopState(saved = snap, wake = w)
    private fun start(s: AutoStopState, t: Long = t0, via: String = "process start") = AutoStopMachine.reduce(s, AutoStopEvent.ProcessStarted(via), t)
    private fun claim(r: Transition<AutoStopState, AutoStopEvent, AutoStopEffect>) = (r.reply as AutoStopClaimed).claim
    private fun hasStart(r: Transition<AutoStopState, AutoStopEvent, AutoStopEffect>) = r.effects.any { it is AutoStopEffect.StartWake }
    private fun lines(r: Transition<AutoStopState, AutoStopEvent, AutoStopEffect>) = r.effects.filterIsInstance<AutoStopEffect.Log>().map { it.line }

    @Test fun aLostHubIsRecoveredWithAProbeAndTheLadderRungIsConsumed() {
        val r = start(state(AutoStopSnapshot(hubLive = true)))
        assertTrue(hasStart(r)); assertEquals(listOf(AutoStopEffect.Line.HUB_RECOVER_ATTEMPT), lines(r))
        assertEquals("process start, exempt=true", r.effects.filterIsInstance<AutoStopEffect.Log>().single().via)
        assertTrue("a recovered hub with no glasses stops after the probe", r.state.saved.wakeProbe)
        assertEquals(1, r.state.saved.wakeStep); assertEquals(t0 + 120 * min, r.state.saved.nextWakeAtMs); assertTrue(r.state.saved.hubLive)
    }

    @Test fun theStartIsSettledOnTheEventAndLogsStarted() {
        val r = start(state(AutoStopSnapshot(hubLive = true)))
        val done = AutoStopMachine.reduce(r.state, AutoStopEvent.WakeResult(issued = true, serviceRunning = false), t0)
        assertEquals(listOf(AutoStopEffect.Line.HUB_RECOVER_STARTED), lines(done)); assertEquals(AutoStopWake.Claim.STARTED, claim(done)); assertEquals(t0, done.state.saved.wakeStartedAtMs)
        val live = AutoStopMachine.reduce(done.state, AutoStopEvent.HubForeground, t0 + 1_000)
        assertEquals("wake complete, still live", 0L, live.state.saved.wakeStartedAtMs); assertTrue(live.state.saved.hubLive)
    }

    @Test fun aRefusedStartIsLoggedWithTheExemptionAndTheProbeClears() {
        val r = start(state(AutoStopSnapshot(hubLive = true), ok.copy(exempt = false)))
        val done = AutoStopMachine.reduce(r.state, AutoStopEvent.WakeResult(issued = false, serviceRunning = false), t0)
        assertEquals(listOf(AutoStopEffect.Line.HUB_RECOVER_REFUSED), lines(done)); assertEquals("process start, exempt=false", done.effects.filterIsInstance<AutoStopEffect.Log>().single().via)
        assertEquals(AutoStopWake.Claim.REFUSED, claim(done)); assertFalse(done.state.saved.wakeProbe)
        assertTrue("the next rung is scheduled for the worker", done.effects.any { it is AutoStopEffect.ScheduleWake })
    }

    @Test fun aManualStopIsNeverRecovered() {
        val r = start(state(AutoStopSnapshot(hubLive = true, manualStop = true)))
        assertFalse(hasStart(r)); assertEquals(AutoStopWake.Claim.NONE, claim(r))
        val stopped = AutoStopMachine.reduce(state(AutoStopSnapshot(hubLive = true)), AutoStopEvent.ManualStopped, t0).state
        assertFalse("the manual stop clears the flag", stopped.saved.hubLive); assertFalse(hasStart(start(stopped)))
    }

    @Test fun anExpectedStopIsNotALostHub() {
        val stopped = AutoStopMachine.reduce(state(AutoStopSnapshot(hubLive = true)), AutoStopEvent.ExpectedStopped, t0).state
        assertFalse(stopped.saved.hubLive); assertFalse(hasStart(start(stopped)))
    }

    @Test fun anAutoStoppedHubKeepsItsOldWakeBehaviour() {
        // auto-stopped (hubLive false): the wake ladder, no hub_recover line
        val snap = AutoStopSnapshot(autoStoppedAtMs = t0 - 100 * min, reason = BackgroundPolicy.Away.DISCONNECTED, nextWakeAtMs = t0 - 1)
        val r = start(state(snap))
        assertTrue(hasStart(r)); assertEquals(listOf(AutoStopEffect.Line.WAKE_ATTEMPT), lines(r))
        // even a stale hubLive with an auto stop is the auto stop (the wake path, not a recovery)
        assertEquals(listOf(AutoStopEffect.Line.WAKE_ATTEMPT), lines(start(state(snap.copy(hubLive = true)))))
        // not exempt: the wake path refuses, as before
        assertFalse(hasStart(start(state(snap, ok.copy(exempt = false)))))
        // never live, never stopped: nothing
        assertEquals(AutoStopWake.Claim.NONE, claim(start(state(AutoStopSnapshot()))))
    }

    @Test fun noRecoveryWithoutLinkMachineryOrWhileTheServiceRuns() {
        assertFalse(hasStart(start(state(AutoStopSnapshot(hubLive = true), ok.copy(linkAllowed = false)))))
        assertFalse(hasStart(start(state(AutoStopSnapshot(hubLive = true), ok.copy(serviceRunning = true)))))
    }

    @Test fun recoveryIsRateLimitedByThePersistedLadder() {
        val first0 = start(state(AutoStopSnapshot(hubLive = true)))
        val first = AutoStopMachine.reduce(first0.state, AutoStopEvent.WakeResult(issued = true, serviceRunning = false), t0) // the start was issued, the foreground not yet reported
        // the process dies again at once: the next process start finds the snapshot (rung consumed, wake never reported), so it only waits for the start timeout
        val again = start(state(first.state.saved), t0 + 10_000)
        assertFalse(hasStart(again)); assertEquals(AutoStopWake.Claim.NONE, claim(again))
        // after the start timeout the interrupted wake is over; the rung still stands
        val timedOut = start(state(first.state.saved), t0 + 3 * min)
        assertFalse(hasStart(timedOut)); assertEquals(AutoStopWake.Claim.NOT_DUE, claim(timedOut))
        // a late process start, with the rung due: the second rung (120 min)
        val later = start(state(timedOut.state.saved), t0 + 121 * min)
        assertTrue(hasStart(later)); assertEquals(2, later.state.saved.wakeStep); assertEquals(t0 + 121 * min + 240 * min, later.state.saved.nextWakeAtMs)
        // a not-yet-due rung with no wake in progress schedules the remaining time
        val early = start(state(first.state.saved.copy(wakeStartedAtMs = 0L)), t0 + 5 * min)
        assertFalse(hasStart(early)); assertEquals(AutoStopWake.Claim.NOT_DUE, claim(early))
        assertTrue(early.effects.any { it is AutoStopEffect.ScheduleWake })
    }

    @Test fun hubForegroundSetsTheFlagAndAnAutoStopClearsItAndACancelledStopRestoresIt() {
        val live = AutoStopMachine.reduce(state(AutoStopSnapshot()), AutoStopEvent.HubForeground, t0).state
        assertTrue(live.saved.hubLive)
        val hub = HubSignals(hubRunning = true, link = com.rokid.context.core.model.LinkState.OFFLINE, worn = null, inFront = false, lastFrontMs = 0L, busy = false, runActive = false)
        val away = AutoStopMachine.reduce(live, AutoStopEvent.Evaluate(ok.copy(serviceRunning = true), hub), t0).state
        // the first look starts the away window; after N minutes the stop is marked
        val marked = AutoStopMachine.reduce(away, AutoStopEvent.Evaluate(ok.copy(serviceRunning = true), hub), t0 + 21 * min).state
        assertTrue("a stop is pending", marked.pendingStop != null); assertFalse("the mark clears the flag", marked.saved.hubLive)
        // the glasses come back before the confirm: the stop is void and the hub is live again
        val back = AutoStopMachine.reduce(marked, AutoStopEvent.Evaluate(ok.copy(serviceRunning = true), hub.copy(link = com.rokid.context.core.model.LinkState.CONNECTED, worn = true)), t0 + 21 * min).state
        assertEquals(null, back.pendingStop); assertTrue(back.saved.hubLive); assertEquals(0L, back.saved.autoStoppedAtMs)
    }

    @Test fun anOldStoredSnapshotLoadsAsNotLiveAndTheFlagRoundTrips() {
        val app: Context = ApplicationProvider.getApplicationContext()
        val prefs = app.getSharedPreferences("hub_auto_stop_hublive_test", Context.MODE_PRIVATE); prefs.edit().clear().commit()
        // a v1.0 record: every key but hub_live
        prefs.edit().putBoolean("manual_stop", false).putLong("auto_stopped_at", 5L).putString("reason", BackgroundPolicy.Away.DISCONNECTED.token).putLong("away_since", 4L)
            .putInt("wake_step", 1).putLong("wake_next_at", 9L).putBoolean("wake_probe", true).putLong("wake_started_at", 0L).commit()
        val old = PrefsAutoStopStore { prefs }.load()
        assertFalse(old.hubLive); assertEquals(5L, old.autoStoppedAtMs); assertEquals(1, old.wakeStep)
        val snap = old.copy(hubLive = true); PrefsAutoStopStore { prefs }.save(snap)
        assertEquals(snap, PrefsAutoStopStore { prefs }.load()); assertTrue(PrefsAutoStopStore { prefs }.hubLive())
    }
}
