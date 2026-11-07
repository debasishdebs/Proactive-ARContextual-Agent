package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Codex review 6: a delayed persistence retry never overrides a newer action; the watchdog's unsaved start keeps its minimum interval after recovery. */
class AutoStopReview6Test {
    private val min = 60_000L

    private class FlakyStore(initial: AutoStopSnapshot = AutoStopSnapshot()) : AutoStopStore {
        @Volatile var ok = true; @Volatile var s = initial
        override fun load() = s
        override fun save(s: AutoStopSnapshot): Boolean { if (ok) this.s = s; return ok }
    }

    private class Rig(initial: AutoStopSnapshot = AutoStopSnapshot()) {
        val store = FlakyStore(initial); val retries = mutableListOf<Runnable>()
        val engine = AutoStopEngine(store, { 1_000 * 60_000L }, SerialStateMachine.DIRECT, { null }, { false }, {}, {}, {}, schedule = { _, r -> retries += r })
        fun runRetries() { while (retries.isNotEmpty()) retries.removeAt(0).run() }
        fun state() = engine.machine.state()!!
    }

    @Test fun anOldUserStartRetryNeverClearsANewerManualStop() {
        val r = Rig(AutoStopSnapshot(manualStop = false, autoStoppedAtMs = 5L, reason = BackgroundPolicy.Away.NOT_WORN))
        r.store.ok = false; r.engine.submit(AutoStopEvent.UserStarted) // fails, a retry is pending
        r.store.ok = true; r.engine.submit(AutoStopEvent.ManualStopped) // the newer action, durable
        assertTrue(r.store.load().manualStop)
        r.runRetries()
        assertTrue("the old start's retry did not clear the newer manual stop", r.store.load().manualStop)
        assertTrue(r.engine.visible()!!.manualStop); assertEquals(r.store.load(), r.state().saved)
    }

    @Test fun anOldManualStopRetryNeverOverwritesANewerUserStart() {
        val r = Rig(AutoStopSnapshot(manualStop = false, autoStoppedAtMs = 5L, reason = BackgroundPolicy.Away.NOT_WORN))
        r.store.ok = false; r.engine.submit(AutoStopEvent.ManualStopped)
        r.store.ok = true; r.engine.submit(AutoStopEvent.UserStarted)
        assertFalse(r.store.load().manualStop)
        r.runRetries()
        assertFalse("the old manual stop's retry did not overwrite the newer start", r.store.load().manualStop)
        assertFalse(r.engine.visible()!!.manualStop)
    }

    @Test fun twoFailedIntentsThenRecoveryKeepTheNewest() {
        for (newestIsStop in listOf(true, false)) {
            val r = Rig(AutoStopSnapshot(manualStop = !newestIsStop, autoStoppedAtMs = 5L, reason = BackgroundPolicy.Away.NOT_WORN))
            r.store.ok = false
            r.engine.submit(if (newestIsStop) AutoStopEvent.UserStarted else AutoStopEvent.ManualStopped)
            r.engine.submit(if (newestIsStop) AutoStopEvent.ManualStopped else AutoStopEvent.UserStarted)
            r.store.ok = true; r.runRetries()
            assertEquals("newest is stop=$newestIsStop", newestIsStop, r.store.load().manualStop)
            assertEquals(newestIsStop, r.engine.visible()!!.manualStop)
        }
    }

    @Test fun aFailedUserStartIsCarriedByTheNextSuccessfulSnapshot() {
        val r = Rig(AutoStopSnapshot(manualStop = true, wakeStep = 2, nextWakeAtMs = 5L))
        r.store.ok = false; r.engine.submit(AutoStopEvent.UserStarted)
        r.store.ok = true
        // an unrelated change commits first (the glasses seen present: the ladder resets); it must carry the pending start, not drop it
        r.engine.submit(AutoStopEvent.Evaluate(WakeInputs(false, 20, true, true), HubSignals(true, LinkState.CONNECTED, true, false, 0L, false, false)))
        assertFalse("the user start is durable", r.store.load().manualStop); assertEquals(0, r.store.load().wakeStep)
        r.runRetries(); assertFalse(r.store.load().manualStop)
    }

    // ---- P2: the unsaved fallback start keeps the 60 s minimum after persistence recovers ----

    @Test fun aSecondHubStartOneSecondAfterAnUnsavedOneIsRefusedAfterRecovery() {
        val inner = InMemoryHubRestartStore(HubRestartSnapshot(BackgroundPolicy.Backoff(1, 0L), 0L)); val ok = AtomicBoolean(false) // a due, non-reset ladder: the reset below must save
        val store = object : HubRestartStore { override fun load() = inner.load(); override fun save(s: HubRestartSnapshot) = ok.get() && inner.save(s) }
        var now = 10 * min; var starts = 0
        val w = GlassesWatchdog(
            now = { now }, enabled = { true }, captureActive = { true }, hubRunning = { false }, link = { LinkState.OFFLINE }, lastHeardAtMs = { null },
            standingDown = { false }, startHub = { starts++ }, reconnect = {}, probeAlive = {}, relaunch = { null }, publish = {}, log = {}, warn = {},
            hubStore = store, executor = SerialStateMachine.DIRECT,
        )
        w.tick(); assertEquals("the unsaved fallback start", 1, starts)
        ok.set(true); w.onPositiveSignal() // a successful reset: persistence recovered
        now += 1_000; w.tick(); assertEquals("1 s later: still inside the 60 s minimum", 1, starts)
        now += 59_000; w.tick(); assertEquals("60 s after the unsaved start", 2, starts)
    }
}
