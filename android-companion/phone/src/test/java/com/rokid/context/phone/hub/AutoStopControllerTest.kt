package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AutoStop start/stop gating: the controller over a fake clock, link, wear signal and store (the pure matrix is in BackgroundPolicyTest). */
class AutoStopControllerTest {
    private val min = 60_000L
    private class Rig {
        var t = 1_000_000L; var minutes = 20; var running = true; var link = LinkState.CONNECTED; var worn: Boolean? = true
        var front = false; var lastFront = 0L; var busy = false; var run = false; var exempt = false; var allowed = true; var wakeIssued = true
        var stops = 0; var wakes = 0; var note: String? = null; val logs = mutableListOf<String>(); val scheduled = mutableListOf<Long>()
        val store = InMemoryAutoStopStore()
        private var self: AutoStopController? = null
        val c = AutoStopController(
            now = { t }, minutes = { minutes }, store = store, hubRunning = { running }, link = { link }, worn = { worn }, inFront = { front }, lastFrontMs = { lastFront },
            busy = { busy }, runActive = { run }, stopHub = { stops++; running = false }, wakeHub = { wakes++; if (wakeIssued) { running = true; self?.engine?.submit(AutoStopEvent.HubForeground) }; wakeIssued }, exempt = { exempt }, linkAllowed = { allowed },
            publishNote = { note = it }, timeText = { "14:05" }, log = { logs += it }, scheduleWake = { scheduled += it },
            executor = SerialStateMachine.DIRECT,
        ).also { self = it } // the service reports the foreground after an issued start
        fun advance(ms: Long) { t += ms }
    }

    @Test fun wornAndConnectedNeverStops() {
        val r = Rig(); assertNull(r.c.evaluate()); r.advance(500 * min); assertNull(r.c.evaluate()); assertEquals(0, r.stops)
    }

    @Test fun offHeadStopsAfterNMinutesWithTheReasonAndTheNote() {
        val r = Rig().apply { worn = false }
        assertEquals("sleeps to the due time", 20 * min, r.c.evaluate()); r.advance(19 * min); assertEquals(1 * min, r.c.evaluate()); assertEquals(0, r.stops)
        r.advance(1 * min); assertEquals("after the stop it sleeps to the first rung, not until a poke", 60 * min, r.c.evaluate())
        assertEquals(1, r.stops); assertTrue("auto_stop_not_worn" in r.logs); assertEquals("Stopped: glasses not worn since 14:05. Tap to start", r.note)
        assertTrue(r.store.autoStoppedAtMs() != 0L)
    }

    @Test fun disconnectedStopsAndSaysSo() {
        val r = Rig().apply { link = LinkState.OFFLINE; worn = null }
        r.c.evaluate(); r.advance(20 * min); r.c.evaluate()
        assertEquals(1, r.stops); assertTrue("auto_stop_disconnected" in r.logs); assertTrue(r.note!!.startsWith("Stopped: glasses not connected since"))
    }

    @Test fun wearUnknownWithTheLinkUpCountsAsPresentUntilTheLinkDrops() {
        val r = Rig().apply { worn = null }
        r.c.evaluate(); r.advance(60 * min); r.c.evaluate(); assertEquals(0, r.stops)
        r.link = LinkState.OFFLINE; r.c.evaluate(); r.advance(19 * min); r.c.evaluate(); assertEquals("not yet N min since the link dropped", 0, r.stops)
        r.advance(1 * min); r.c.evaluate(); assertEquals(1, r.stops)
    }

    @Test fun puttingTheGlassesOnInsideTheWindowStartsTheClockAgain() {
        val r = Rig().apply { worn = false }
        r.c.evaluate(); r.advance(15 * min); r.worn = true; r.c.evaluate(); r.advance(10 * min); r.worn = false; r.c.evaluate()
        r.advance(19 * min); r.c.evaluate(); assertEquals(0, r.stops); r.advance(1 * min); r.c.evaluate(); assertEquals(1, r.stops)
    }

    @Test fun anAskARunOrTheFrontHoldTheStopThenItFollows() {
        val r = Rig().apply { worn = false }
        r.c.evaluate(); r.advance(30 * min)
        r.busy = true; assertEquals(BackgroundPolicy.BUSY_RECHECK_MS, r.c.evaluate()); assertEquals(0, r.stops)
        r.busy = false; r.run = true; r.c.evaluate(); assertEquals(0, r.stops)
        r.run = false; r.front = true; r.c.evaluate(); assertEquals(0, r.stops)
        r.front = false; r.lastFront = r.t - 3 * min; r.c.evaluate(); assertEquals("a tapped Reconnect or the front 3 min ago", 0, r.stops)
        r.advance(8 * min); r.c.evaluate(); assertEquals(1, r.stops)
    }

    @Test fun neverKeepsTheHubRunning() {
        val r = Rig().apply { worn = false; minutes = 0 }; r.c.evaluate(); r.advance(24 * 60 * min); assertNull(r.c.evaluate()); assertEquals(0, r.stops)
    }

    @Test fun aSettingChangeAppliesOnTheNextEvaluation() {
        val r = Rig().apply { worn = false; minutes = 60 }; r.c.evaluate(); r.advance(30 * min); r.c.evaluate(); assertEquals(0, r.stops)
        r.minutes = 20; r.c.evaluate(); assertEquals(1, r.stops)
    }

    @Test fun withoutTheExemptionNothingStartsAStoppedHub() {
        val r = Rig().apply { worn = false }; r.c.evaluate(); r.advance(20 * min); r.c.evaluate()
        r.advance(10_000 * min); assertNull(r.c.evaluate()); assertEquals(0, r.wakes); assertFalse(r.running)
    }

    @Test fun withTheExemptionTheHubWakesOnTheLadderAndStopsAgainAfterAShortProbe() {
        val r = Rig().apply { worn = false; exempt = true }; r.c.evaluate(); r.advance(20 * min); r.c.evaluate()
        assertEquals("waits the first rung", 60 * min, r.c.evaluate())
        r.advance(59 * min); r.c.evaluate(); assertEquals(0, r.wakes)
        r.advance(1 * min); r.c.evaluate(); assertEquals(1, r.wakes); assertEquals("flag cleared by the start", 0L, r.store.autoStoppedAtMs()); assertNull(r.note)
        // the woken hub looks for the glasses for 5 minutes, not another 20
        assertEquals(5 * min, r.c.evaluate()); r.advance(5 * min); r.c.evaluate(); assertEquals(2, r.stops)
        // second rung is two hours, not an hour
        r.c.evaluate(); r.advance(119 * min); r.c.evaluate(); assertEquals(1, r.wakes); r.advance(1 * min); r.c.evaluate(); assertEquals(2, r.wakes)
    }

    @Test fun aWakeThatAndroidRefusesKeepsTheStoppedStateAndDoesNotRetryAtOnce() {
        val r = Rig().apply { worn = false; exempt = true; wakeIssued = false }; r.c.evaluate(); r.advance(20 * min); r.c.evaluate(); r.advance(60 * min); r.c.evaluate()
        assertEquals(1, r.wakes); assertTrue(r.store.autoStoppedAtMs() != 0L); r.c.evaluate(); r.c.evaluate(); assertEquals("no loop", 1, r.wakes)
    }

    @Test fun aManualStopOrNeverOrNoLinkStopsTheWake() {
        for (case in 0..2) {
            val r = Rig().apply { worn = false; exempt = true }; r.c.evaluate(); r.advance(20 * min); r.c.evaluate()
            when (case) { 0 -> r.c.engine.submit(AutoStopEvent.ManualStopped); 1 -> r.minutes = 0; else -> r.allowed = false }
            r.advance(1_000 * min); assertNull("case $case", r.c.evaluate()); assertEquals("case $case", 0, r.wakes)
        }
    }

    @Test fun seeingTheGlassesPresentResetsTheWakeLadder() {
        val r = Rig().apply { worn = false; exempt = true }; r.c.evaluate(); r.advance(20 * min); r.c.evaluate(); r.advance(60 * min); r.c.evaluate()
        assertEquals(1, r.store.wakeStep()); r.worn = true; r.c.evaluate(); assertEquals(0, r.store.wakeStep())
    }

    @Test fun aUserStartEndsTheManualAndAutoStopAndTheLadder() {
        val before = AutoStopState(saved = AutoStopSnapshot(manualStop = true, autoStoppedAtMs = 5L, reason = BackgroundPolicy.Away.NOT_WORN, awaySinceMs = 1L, wakeStep = 1, nextWakeAtMs = 7L, wakeProbe = true))
        val s = AutoStopMachine.reduce(before, AutoStopEvent.UserStarted, 10L).state.saved
        assertFalse(s.manualStop); assertEquals(0L, s.autoStoppedAtMs); assertEquals(0, s.wakeStep); assertNull(s.reason); assertFalse(s.wakeProbe)
    }

    // ---- Review fixes 2026-11-07 ----

    @Test fun aStopWithTheLinkAlreadyOfflineArmsTheWakeWithoutAnyPoke() = kotlinx.coroutines.test.runTest {
        val base = 1_000_000L
        var running = true; var wakes = 0; val scheduled = mutableListOf<Long>()
        val store = InMemoryAutoStopStore()
        val c = AutoStopController(
            now = { base + testScheduler.currentTime }, minutes = { 20 }, hubRunning = { running }, link = { LinkState.OFFLINE }, worn = { null },
            inFront = { false }, lastFrontMs = { 0L }, busy = { false }, runActive = { false }, stopHub = { running = false },
            wakeHub = { wakes++; running = true; true }, exempt = { true }, linkAllowed = { true }, publishNote = {}, timeText = { "x" }, log = {}, scheduleWake = { scheduled += it },
            store = store, executor = SerialStateMachine.DIRECT, // virtual time: no real IO thread hop
        )
        val job = c.start(backgroundScope) // the link never changes again: no poke ever comes
        testScheduler.advanceTimeBy(20 * min + 1); testScheduler.runCurrent()
        assertFalse("stopped after N min", running); assertEquals(listOf(60 * min), scheduled)
        testScheduler.advanceTimeBy(60 * min); testScheduler.runCurrent()
        assertEquals("woken on the first rung with no poke", 1, wakes); assertTrue(running)
        job.cancel()
    }

    @Test fun aRefusedWakeIsReArmedOnTheLadder60Then120Then240() {
        val r = Rig().apply { worn = false; exempt = true; wakeIssued = false }; r.c.evaluate(); r.advance(20 * min)
        assertEquals(60 * min, r.c.evaluate()); assertEquals(1, r.stops)
        r.advance(60 * min); assertEquals("refused: re-armed for the second rung", 120 * min, r.c.evaluate()); assertEquals(1, r.wakes)
        r.advance(120 * min); assertEquals(240 * min, r.c.evaluate()); assertEquals(2, r.wakes)
        r.advance(240 * min); assertEquals("the ladder caps at 240", 240 * min, r.c.evaluate()); assertEquals(3, r.wakes)
        assertEquals(listOf(60 * min, 120 * min, 240 * min, 240 * min), r.scheduled)
        assertFalse("a refused wake leaves no probe behind", r.store.wakeProbe())
    }

    @Test fun aClockSetBackDoesNotBlockTheWakeForEver() {
        val r = Rig().apply { worn = false; exempt = true }; r.c.evaluate(); r.advance(20 * min); r.c.evaluate()
        r.t -= 10 * 24 * 60 * min // the wall clock is set back ten days: wake_next_at now lies far beyond any rung
        r.c.evaluate(); assertEquals("due at once, not in ten days", 1, r.wakes)
        assertTrue("and the next rung is measured from the new clock", r.store.nextWakeAtMs() - r.t <= BackgroundPolicy.WAKE_LADDER_MS.last())
    }

    @Test fun theProbeFlagInTheStoreGivesAColdWokenHubTheShortProbe() {
        val r = Rig().apply { worn = false }
        r.store.save(AutoStopSnapshot(wakeProbe = true)) // a cold-process wake (AutoStopWake) set it before this controller existed
        assertEquals(5 * min, r.c.evaluate()); r.advance(5 * min); r.c.evaluate(); assertEquals(1, r.stops); assertFalse(r.store.wakeProbe())
    }

    // ---- Review fixes 2 / Codex review 4: every commit runs on the actor's IO context, never on the caller (main) thread, and before the stop / start it guards ----

    /** Records every snapshot save and hub action with the thread it ran on. */
    private class Recording(val log: MutableList<String>) : AutoStopStore {
        private val inner = InMemoryAutoStopStore()
        override fun load() = inner.load()
        override fun save(s: AutoStopSnapshot): Boolean { synchronized(log) { log += "save(at=${s.autoStoppedAtMs != 0L},step=${s.wakeStep},probe=${s.wakeProbe},waking=${s.wakeStartedAtMs != 0L})@${Thread.currentThread().name}" }; return inner.save(s) }
    }

    @Test fun theStopAndTheWakeCommitOnIoBeforeTheStopAndStartAndNothingRunsOnTheCallerThread() {
        val events = mutableListOf<String>(); val store = Recording(events)
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-main") }; val main = ex.asCoroutineDispatcher()
        var t = 1_000_000L; var running = true
        fun rec(e: String) { synchronized(events) { events += "$e@${Thread.currentThread().name}" } }
        val c = AutoStopController(
            now = { t }, minutes = { 20 }, hubRunning = { running }, link = { LinkState.OFFLINE }, worn = { null }, inFront = { false }, lastFrontMs = { 0L },
            busy = { false }, runActive = { false }, stopHub = { rec("stop"); running = false }, wakeHub = { rec("start"); running = true; true }, exempt = { true },
            linkAllowed = { true }, publishNote = {}, timeText = { "x" }, log = {}, store = store, executor = kotlinx.coroutines.Dispatchers.IO.asExecutor(),
        )
        try {
            kotlinx.coroutines.runBlocking(main) { c.evaluateNow(); t += 20 * min; c.evaluateNow() }
            assertEquals("the mark is durable before the stop", listOf("save(at=true,step=0,probe=false,waking=false)", "stop"), events.map { it.substringBefore('@') })
            assertTrue("nothing on the caller thread: $events", events.none { it.contains("@fake-main") })
            events.clear(); t += 60 * min
            kotlinx.coroutines.runBlocking(main) { c.evaluateNow() }
            assertEquals("rung + probe durable BEFORE the start, then the wake in progress (cleared only by the foreground report)",
                listOf("save(at=true,step=1,probe=true,waking=false)", "start", "save(at=true,step=1,probe=true,waking=true)"), events.map { it.substringBefore('@') })
            assertTrue("nothing on the caller thread: $events", events.none { it.contains("@fake-main") })
        } finally { ex.shutdownNow() }
    }
}
