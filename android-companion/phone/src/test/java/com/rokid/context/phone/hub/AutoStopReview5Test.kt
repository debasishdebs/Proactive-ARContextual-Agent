package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Codex review 5: the five reproduced scenarios, each as a test (fake stores whose commit fails, fake service start results, timeouts, interleavings). */
class AutoStopReview5Test {
    private val min = 60_000L
    private fun sec(l: CountDownLatch) = assertTrue(l.await(5, TimeUnit.SECONDS))

    /** A store whose [ok] decides what commit() returns; a failed commit keeps the old record (as SharedPreferences does). */
    private class FlakyStore(initial: AutoStopSnapshot = AutoStopSnapshot()) : AutoStopStore {
        @Volatile var ok = true; @Volatile var s = initial; val attempts = AtomicLong()
        override fun load() = s
        override fun save(s: AutoStopSnapshot): Boolean { attempts.incrementAndGet(); if (ok) this.s = s; return ok }
    }

    private class Rig(val store: AutoStopStore, executor: java.util.concurrent.Executor = SerialStateMachine.DIRECT, val scheduler: MutableList<Pair<Long, Runnable>> = mutableListOf()) {
        val t = AtomicLong(1_000_000L); val front = AtomicBoolean(); @Volatile var lastFront = 0L; @Volatile var running = true; @Volatile var worn: Boolean? = false
        @Volatile var exempt = false; @Volatile var issue = true
        var stops = 0; var wakes = 0; val logs = java.util.Collections.synchronizedList(mutableListOf<String>()); val scheduled = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val engine = AutoStopEngine(store, { t.get() }, executor, { null }, { false }, { scheduled += it }, { logs += it }, { logs += it },
            schedule = { ms, r -> synchronized(scheduler) { scheduler += ms to r } })
        val c = AutoStopController(
            now = { t.get() }, minutes = { 20 }, hubRunning = { running }, link = { LinkState.CONNECTED }, worn = { worn }, inFront = { front.get() }, lastFrontMs = { lastFront },
            busy = { false }, runActive = { false }, stopHub = { stops++; running = false }, wakeHub = { wakes++; issue }, exempt = { exempt }, linkAllowed = { true },
            publishNote = {}, timeText = { "x" }, log = { logs += it }, scheduleWake = { scheduled += it }, engine = engine,
        )
        fun state() = engine.machine.state()!!
    }

    // ---- 1. the confirm commits nothing: no commit window between the decision over the latest inputs and StopHub ----

    /** Gates the commit that persists a used-up probe with a stop mark: before the fix that was the confirm's own (second) commit. */
    private class ProbeGate : AutoStopStore {
        val inner = InMemoryAutoStopStore(AutoStopSnapshot(wakeProbe = true)); val inSave = CountDownLatch(1); val release = CountDownLatch(1); private val armed = AtomicBoolean(true)
        override fun load() = inner.load()
        override fun save(s: AutoStopSnapshot): Boolean {
            if (s.autoStoppedAtMs != 0L && !s.wakeProbe && armed.compareAndSet(true, false)) { inSave.countDown(); release.await(5, TimeUnit.SECONDS) }
            return inner.save(s)
        }
    }

    private fun queuedDuringTheStopCommit(during: (Rig) -> Unit): Rig {
        val g = ProbeGate(); val r = Rig(g, Executors.newSingleThreadExecutor())
        assertEquals("a woken hub's 5 min probe", 5 * min, r.c.evaluate()); r.t.addAndGet(5 * min)
        val a = Thread { r.c.evaluate() }.apply { start() }
        sec(g.inSave); during(r); g.release.countDown(); a.join(5_000)
        runBlocking { r.engine.submit(AutoStopEvent.Evaluate()).await() } // everything queued is reduced
        return r
    }

    @Test fun aForegroundReturnQueuedDuringTheStopCommitCancelsTheStop() {
        val r = queuedDuringTheStopCommit { it.front.set(true); it.lastFront = it.t.get(); it.engine.submit(AutoStopEvent.Evaluate()) }
        assertEquals("no StopHub after the foreground return", 0, r.stops); assertEquals(0L, r.store.autoStoppedAtMs()); assertNull(r.state().pendingStop)
    }

    @Test fun aUserStartQueuedDuringTheStopCommitCancelsTheStop() {
        val r = queuedDuringTheStopCommit { it.engine.submit(AutoStopEvent.UserStarted) }
        assertEquals("no StopHub after the user start", 0, r.stops); assertEquals(0L, r.store.autoStoppedAtMs())
    }

    @Test fun theConfirmStepThatStopsCommitsNothing() {
        val saves = mutableListOf<AutoStopSnapshot>()
        val store = object : AutoStopStore { val i = InMemoryAutoStopStore(AutoStopSnapshot(wakeProbe = true)); override fun load() = i.load(); override fun save(s: AutoStopSnapshot) = i.save(s).also { saves += s } }
        val r = Rig(store); r.c.evaluate(); r.t.addAndGet(5 * min); r.c.evaluate()
        assertEquals(1, r.stops); assertEquals("ONE commit (the mark, the probe used up in it) before the stop", 1, saves.size); assertFalse(saves.single().wakeProbe)
    }

    // ---- 2. a user start ends the probe's synthetic away start: the full N applies ----

    @Test fun aUserStartGivesTheFullTwentyMinutesAfterTheFrontHold() {
        val r = Rig(InMemoryAutoStopStore(AutoStopSnapshot(wakeProbe = true)))
        r.c.evaluate() // the woken hub's probe: away counted from 15 min ago
        r.lastFront = r.t.get(); r.engine.submit(AutoStopEvent.UserStarted); r.c.evaluate() // the user opened the app (front hold 10 min)
        r.t.addAndGet(10 * min + 1_000); r.c.evaluate()
        assertEquals("the front hold expired, but 20 min have not", 0, r.stops)
        r.t.addAndGet(10 * min); r.c.evaluate(); assertEquals("20 min after the user start", 1, r.stops)
    }

    // ---- 3. a failed commit is not durable: nothing published, no effect; safe "keep running"; bounded retry; rate-limited log ----

    @Test fun aFailedStopCommitNeverStopsPublishesNothingAndLogsAtMostOncePerMinute() {
        val store = FlakyStore().apply { ok = false }; val r = Rig(store)
        r.c.evaluate(); r.t.addAndGet(20 * min)
        assertEquals("retry soon", BackgroundPolicy.BUSY_RECHECK_MS, r.c.evaluate())
        assertEquals("never stopped on an unsaved decision", 0, r.stops); assertEquals(0L, r.state().saved.autoStoppedAtMs); assertEquals(store.load(), r.state().saved)
        assertFalse("the stop was cancelled in memory: the latest state equals the saved one again", r.state().persistFailing); assertEquals(0L, r.engine.visible()!!.autoStoppedAtMs)
        r.t.addAndGet(30_000); r.c.evaluate(); r.t.addAndGet(20_000); r.c.evaluate()
        assertEquals("once within the minute", 1, r.logs.count { it == "persist_failed" })
        r.t.addAndGet(15_000); r.c.evaluate(); assertEquals(2, r.logs.count { it == "persist_failed" })
        assertEquals(0, r.stops)
        store.ok = true; r.c.evaluate(); assertEquals("persistence back: the stop proceeds", 1, r.stops); assertFalse(r.state().persistFailing)
    }

    @Test fun aFailedUserStartIsAppliedInMemoryAtOnceAndItsSnapshotWrittenBehindOnACappedBackoff() {
        val store = FlakyStore(AutoStopSnapshot(manualStop = true, autoStoppedAtMs = 5L, reason = BackgroundPolicy.Away.NOT_WORN)).apply { ok = false }
        val r = Rig(store)
        r.engine.submit(AutoStopEvent.UserStarted)
        assertFalse("the in-memory state is authoritative: applied at once", r.state().saved.manualStop); assertTrue("the record is unchanged", store.load().manualStop)
        assertFalse(r.engine.visible()!!.manualStop)
        // keep failing: one coalesced retry at a time, on the capped back-off; nothing is replayed
        val delays = mutableListOf<Long>()
        repeat(7) { val (ms, run) = r.scheduler.removeAt(0); delays += ms; run.run(); assertEquals("one retry armed at a time", 1, r.scheduler.size) }
        assertEquals(SerialStateMachine.RETRY_MS + listOf(60_000L, 60_000L), delays)
        store.ok = true; r.scheduler.removeAt(0).second.run()
        assertEquals("the latest snapshot is written", r.state().saved, store.load()); assertFalse(r.state().persistFailing); assertTrue("no more retries", r.scheduler.isEmpty())
    }

    @Test fun theWatchdogKeepsTheHubRunningWhenItsLadderCannotBeSavedAtMostOnceAMinute() {
        val inner = InMemoryHubRestartStore(); val ok = AtomicBoolean(false)
        val store = object : HubRestartStore { override fun load() = inner.load(); override fun save(s: HubRestartSnapshot) = ok.get() && inner.save(s) }
        var now = 10 * min; var starts = 0; val warns = mutableListOf<String>()
        val w = GlassesWatchdog(
            now = { now }, enabled = { true }, captureActive = { true }, hubRunning = { false }, link = { LinkState.OFFLINE }, lastHeardAtMs = { null },
            standingDown = { false }, startHub = { starts++ }, reconnect = {}, probeAlive = {}, relaunch = { null }, publish = {}, log = {}, warn = { warns += it },
            hubStore = store, executor = SerialStateMachine.DIRECT, persistRetry = { _, _ -> },
        )
        w.tick(); assertEquals("the hub is started though its record cannot be saved", 1, starts); assertEquals("nothing saved", HubRestartSnapshot(), inner.load())
        now += 30_000; w.tick(); assertEquals("the in-memory ladder holds (authoritative)", 1, starts); assertEquals(1, warns.count { it == "persist_failed" })
        now += 31_000; w.tick(); assertEquals("still inside the 5 min step", 1, starts)
        assertEquals("persist_failed at most once a minute", 2, warns.count { it == "persist_failed" })
        now = 10 * min + 5 * min; w.tick(); assertEquals("the next step", 2, starts)
        ok.set(true); now += 15 * min; w.tick(); assertEquals(3, starts); assertEquals("the latest record is written", now, inner.load().lastStartAtMs)
    }

    // ---- 4. an issued start is not a started hub: the wake stays in progress until the foreground report or the timeout ----

    private fun stoppedRig(scheduler: MutableList<Pair<Long, Runnable>> = mutableListOf()): Rig {
        val r = Rig(InMemoryAutoStopStore(), scheduler = scheduler).apply { exempt = true }
        r.c.evaluate(); r.t.addAndGet(20 * min); r.c.evaluate(); assertEquals(1, r.stops)
        r.t.addAndGet(60 * min); r.c.evaluate(); assertEquals("the wake request was issued", 1, r.wakes)
        return r
    }

    @Test fun anIssuedWakeStaysInProgressUntilTheServiceReportsTheForeground() {
        val r = stoppedRig()
        val s = r.store.load()
        assertTrue("still auto-stopped while the service starts", s.autoStoppedAtMs != 0L); assertTrue(s.wakeStartedAtMs != 0L)
        assertTrue("a recovery wake at the timeout", AutoStopMachine.WAKE_START_TIMEOUT_MS in r.scheduled)
        assertEquals("not stopped in the eyes of the rest of the app", 0L, r.engine.visible()!!.autoStoppedAtMs)
        r.engine.submit(AutoStopEvent.HubForeground)
        assertEquals(0L, r.store.load().autoStoppedAtMs); assertEquals(0L, r.store.load().wakeStartedAtMs)
    }

    @Test fun aWakeWhoseServiceNeverReachesTheForegroundTimesOutAndReArmsTheNextRung() {
        val r = stoppedRig()
        r.t.addAndGet(60_000); r.c.evaluate(); assertEquals("no second request inside the timeout", 1, r.wakes)
        r.t.addAndGet(60_000); r.scheduled.clear(); r.c.evaluate()
        val s = r.store.load()
        assertEquals(0L, s.wakeStartedAtMs); assertFalse("no probe", s.wakeProbe); assertTrue("still stopped", s.autoStoppedAtMs != 0L)
        assertTrue("the next rung (120 min from the claim) is armed: ${r.scheduled}", r.scheduled.contains(118 * min)); assertTrue(r.logs.contains("auto_stop wake timed out"))
        r.t.addAndGet(118 * min); r.c.evaluate(); assertEquals("woken again on the next rung", 2, r.wakes)
    }

    @Test fun aColdStartResumesAnInterruptedWake() {
        val t = 1_000 * min
        val s = InMemoryAutoStopStore(AutoStopSnapshot(autoStoppedAtMs = 1L, reason = BackgroundPolicy.Away.DISCONNECTED, wakeStep = 1, nextWakeAtMs = t + 90 * min, wakeProbe = true, wakeStartedAtMs = t - 30_000))
        val scheduled = mutableListOf<Long>(); var starts = 0
        // a fresh process each time: a new engine loads the persisted record
        fun cold(now: Long) = (runBlocking {
            AutoStopEngine(s, { now }, SerialStateMachine.DIRECT, { WakeInputs(true, 20, true, false) }, { starts++; true }, { scheduled += it }, {}, {})
                .submit(AutoStopEvent.ProcessStarted("process start")).await()
        } as AutoStopClaimed).claim == AutoStopWake.Claim.STARTED
        assertFalse(cold(t)); assertEquals("recovery scheduled at the timeout", listOf(90_000L), scheduled); assertEquals(0, starts)
        scheduled.clear(); assertFalse(cold(t + 2 * min))
        assertEquals(0L, s.load().wakeStartedAtMs); assertFalse(s.load().wakeProbe); assertEquals("the next rung is armed", listOf(88 * min), scheduled)
    }

    // ---- 5. nothing preference-backed is loaded on the caller's thread before the actor has loaded ----

    @Test fun theEngineLoadsOnItsExecutorAndReadersGetSafeValuesUntilThen() {
        val loading = CountDownLatch(1); val release = CountDownLatch(1); val loadThread = java.util.concurrent.atomic.AtomicReference<String>()
        val store = object : AutoStopStore {
            override fun load(): AutoStopSnapshot { loadThread.set(Thread.currentThread().name); loading.countDown(); release.await(5, TimeUnit.SECONDS); return AutoStopSnapshot(manualStop = true) }
            override fun save(s: AutoStopSnapshot) = true
        }
        val io = Executors.newSingleThreadExecutor { Thread(it, "actor-io") }
        val e = AutoStopEngine(store, { 0L }, io, { null }, { false }, {})
        assertNull("not loaded yet: the caller assumes keep running", e.visible())
        e.warm(); sec(loading); assertNull(e.visible()); release.countDown()
        val end = System.nanoTime() + 5_000_000_000L; while (e.visible() == null && System.nanoTime() < end) Thread.sleep(1)
        assertEquals("actor-io", loadThread.get()); assertTrue(e.visible()!!.manualStop)
        io.shutdown()
    }

    @Test fun applicationOnCreateLoadsNoPreferenceStoreEagerly() {
        val src = java.io.File("src/main/java/com/rokid/context/ContextApp.kt").readText()
        val eager = Regex("""(?m)^\s*val \w+ = [\w.]+Store\.get\(this\)""").findAll(src).map { it.value.trim() }.toList()
        assertEquals("stores are read lazily inside the providers", emptyList<String>(), eager)
        assertTrue(src.contains("AutoStopWake.processStarted(this)"))
    }
}
