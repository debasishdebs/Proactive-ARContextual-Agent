package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Codex review 4: the AutoStop / watchdog state is ONE serialised state machine. Property tests (random interleavings from several threads, a fake clock) and the
 * deterministic reproductions of the five-finding races: a foreground return during the stop commit, a user start during a wake claim, a reset during a delayed save.
 */
class AutoStopActorTest {
    private val min = 60_000L
    private fun sec(l: CountDownLatch) = assertTrue(l.await(5, TimeUnit.SECONDS))

    private fun randomWake(r: Random) = WakeInputs(exempt = r.nextInt(4) != 0, minutes = listOf(5, 20, 0)[r.nextInt(3)], linkAllowed = r.nextInt(5) != 0, serviceRunning = r.nextBoolean())
    private fun randomHub(r: Random) = HubSignals(
        hubRunning = r.nextInt(3) != 0, link = listOf(LinkState.CONNECTED, LinkState.OFFLINE, LinkState.CONNECTING)[r.nextInt(3)], worn = listOf(true, false, null)[r.nextInt(3)],
        inFront = r.nextInt(5) == 0, lastFrontMs = 0L, busy = r.nextInt(6) == 0, runActive = r.nextInt(8) == 0,
    )
    private fun randomEvent(r: Random): AutoStopEvent = when (r.nextInt(10)) {
        in 0..5 -> AutoStopEvent.Evaluate(randomWake(r), randomHub(r))
        6, 7 -> AutoStopEvent.ProcessStarted("process start", randomWake(r))
        8 -> if (r.nextBoolean()) AutoStopEvent.UserStarted else AutoStopEvent.HubForeground
        else -> AutoStopEvent.ManualStopped
    }

    private class Recorded(val event: AutoStopEvent, val at: Long, val state: AutoStopState)

    /** One run: [threads] submitters, a real multi-thread executor, a fake clock advanced at random; returns the trace and what each step checked. */
    private class Run(seed: Int, threads: Int, perThread: Int, val gen: (Random) -> AutoStopEvent, awaitEach: Boolean = false) {
        val clock = AtomicLong(1_000_000L)
        val store = InMemoryAutoStopStore()
        val trace: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())
        val violations: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var stops = 0
        private val pool = Executors.newFixedThreadPool(4)
        val engine = AutoStopEngine(
            store, { clock.get() }, pool, wakeInputs = { null },
            startWake = { Random(clock.get()).nextBoolean() }, scheduleWake = {}, log = {}, warn = {},
            onStep = { e, t, s ->
                trace += Recorded(e, t, s)
                if (store.load() != s.saved) violations += "persisted != in-memory after $e"
            },
        )
        init {
            engine.attach(AutoStopEngine.HubPort(
                sample = { null }, wake = { null }, wakeHub = { false }, scheduleWake = {}, publishNote = {}, timeText = { "x" }, log = {},
                stopHub = {
                    stops++
                    // the stop runs right after its confirm step: the latest processed inputs must still say "stop"
                    val s = engine.machine.state()!!; val h = s.hub!!; val w = s.wake!!
                    if (h.inFront || h.busy || h.runActive || !h.hubRunning || BackgroundPolicy.away(h.link, h.worn) == null || w.minutes == BackgroundPolicy.AUTO_STOP_NEVER)
                        violations += "stopped against the latest inputs $h $w"
                },
            ))
            val rnd = Random(seed)
            val plans = List(threads) { List(perThread) { gen(rnd) to rnd.nextLong(0, 12 * 60_000L) } }
            val done = Collections.synchronizedList(mutableListOf<kotlinx.coroutines.CompletableDeferred<Any?>>())
            val ts = plans.map { plan -> Thread { for ((e, dt) in plan) { clock.addAndGet(dt); val d = engine.submit(e); done += d; if (awaitEach) runBlocking { d.await() } } } }
            ts.forEach { it.start() }; ts.forEach { it.join(10_000) }
            runBlocking { done.toList().forEach { it.await() } }
            pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun randomInterleavingsAreASequentialOrderPersistedAfterEveryStepAndNeverStopAgainstLaterInputs() {
        var stops = 0; var cancelled = 0
        repeat(150) { seed ->
            val run = Run(seed, threads = 3, perThread = 40, gen = ::randomEvent)
            stops += run.stops; cancelled += run.trace.zipWithNext().count { (a, b) -> a.state.pendingStop != null && b.state.pendingStop == null && b.state.saved.autoStoppedAtMs == 0L }
            assertEquals("seed $seed: ${run.violations.take(3)}", emptyList<String>(), run.violations)
            // the trace is ONE order: folding the pure reducer over it from the loaded state gives every recorded state, and the final one
            var s = AutoStopState(saved = AutoStopSnapshot())
            for (r in run.trace) { s = AutoStopMachine.reduce(s, r.event, r.at).state; assertEquals("seed $seed replay", r.state, s) }
            assertEquals("seed $seed final", run.engine.machine.state(), s)
            assertEquals("seed $seed: persisted == in-memory at the end", run.store.load(), s.saved)
            assertNull("seed $seed: no stop left half-committed", s.pendingStop)
        }
        // coverage of real stops must not depend on the machine's load: under a busy CPU the submitters outrun the drain and every stop's recheck queues behind a
        // later input, so the free-running runs may see none. Sequential seeded runs (one submitter that awaits each step) reach a stop deterministically.
        var sequentialStops = 0
        repeat(50) { seed ->
            val run = Run(1_000 + seed, threads = 1, perThread = 40, gen = ::randomEvent, awaitEach = true)
            sequentialStops += run.stops
            assertEquals("sequential seed $seed: ${run.violations.take(3)}", emptyList<String>(), run.violations)
        }
        assertTrue("the sequential runs reach real stops ($sequentialStops)", sequentialStops > 0)
        stops += sequentialStops
        assertTrue("the property saw real stops ($stops) and cancelled ones ($cancelled)", stops > 0 && cancelled > 0)
    }

    // ---- Finding 1: inputs that change during the stop's IO commit cancel the stop ----

    private class Gated {
        val inSave = CountDownLatch(1); val release = CountDownLatch(1); @Volatile var gate = true
        val store = object : AutoStopStore {
            private val inner = InMemoryAutoStopStore()
            override fun load() = inner.load()
            override fun save(s: AutoStopSnapshot): Boolean { if (gate && s.autoStoppedAtMs != 0L) { gate = false; inSave.countDown(); release.await(5, TimeUnit.SECONDS) }; return inner.save(s) }
        }
    }

    private fun stopRig(g: Gated, front: () -> Boolean, busy: () -> Boolean, stops: IntArray, t: AtomicLong): AutoStopController = AutoStopController(
        now = { t.get() }, minutes = { 20 }, hubRunning = { stops[0] == 0 }, link = { LinkState.CONNECTED }, worn = { false }, inFront = front, lastFrontMs = { 0L },
        busy = busy, runActive = { false }, stopHub = { stops[0]++ }, wakeHub = { false }, exempt = { false }, linkAllowed = { true }, publishNote = {}, timeText = { "x" },
        log = {}, store = g.store, executor = Executors.newSingleThreadExecutor(),
    )

    @Test fun aForegroundEventDuringTheStopCommitCancelsTheStopAndRevertsTheMark() {
        val g = Gated(); val stops = IntArray(1); val t = AtomicLong(1_000_000L); val front = java.util.concurrent.atomic.AtomicBoolean()
        val c = stopRig(g, { front.get() }, { false }, stops, t)
        c.evaluate(); t.addAndGet(20 * min)
        var sleep: Long? = -1L
        val a = Thread { sleep = c.evaluate() }.apply { start() }
        sec(g.inSave) // the mark is being committed
        front.set(true); val later = c.engine.submit(AutoStopEvent.Evaluate()) // the app came to the front: an event queued during the commit
        g.release.countDown(); a.join(5_000); runBlocking { later.await() }
        assertEquals("no stop after a foreground event", 0, stops[0])
        assertEquals("the mark is reverted, persisted", 0L, g.store.autoStoppedAtMs()); assertNull(c.engine.machine.state()!!.pendingStop)
        assertEquals("the controller holds for the front", BackgroundPolicy.FRONT_HOLD_MS, sleep)
    }

    @Test fun anInputWithoutAnEventIsSampledAgainAtTheConfirm() {
        val g = Gated(); val stops = IntArray(1); val t = AtomicLong(1_000_000L); val busy = java.util.concurrent.atomic.AtomicBoolean()
        val c = stopRig(g, { false }, { busy.get() }, stops, t)
        c.evaluate(); t.addAndGet(20 * min)
        val a = Thread { c.evaluate() }.apply { start() }
        sec(g.inSave); busy.set(true) // an ask started while the mark was being committed (no event)
        g.release.countDown(); a.join(5_000)
        assertEquals(0, stops[0]); assertEquals(0L, g.store.autoStoppedAtMs())
    }

    @Test fun withNothingChangedTheConfirmedStopRuns() {
        val g = Gated(); val stops = IntArray(1); val t = AtomicLong(1_000_000L)
        val c = stopRig(g, { false }, { false }, stops, t)
        c.evaluate(); t.addAndGet(20 * min)
        val a = Thread { c.evaluate() }.apply { start() }
        sec(g.inSave); g.release.countDown(); a.join(5_000)
        assertEquals(1, stops[0]); assertTrue(g.store.autoStoppedAtMs() != 0L)
    }

    // ---- Finding 2: a user start during a wake claim is never undone by the claim ----

    @Test fun aUserStartDuringAWakeClaimClearsTheProbeWhateverTheOutcome() {
        for (issued in listOf(true, false)) {
            val store = InMemoryAutoStopStore(AutoStopSnapshot(autoStoppedAtMs = 1L, reason = BackgroundPolicy.Away.DISCONNECTED, nextWakeAtMs = 1_000 * min))
            val inStart = CountDownLatch(1); val release = CountDownLatch(1); val up = java.util.concurrent.atomic.AtomicBoolean()
            val pool = Executors.newFixedThreadPool(2)
            val e = AutoStopEngine(store, { 1_000 * min }, pool, { WakeInputs(true, 20, true, up.get()) },
                startWake = { inStart.countDown(); release.await(5, TimeUnit.SECONDS); if (issued) up.set(true); issued }, scheduleWake = {}, log = {}, warn = {})
            val claim = e.submit(AutoStopEvent.ProcessStarted("process start"))
            sec(inStart)
            val user = e.submit(AutoStopEvent.UserStarted) // the user opened the app while the wake's start is in flight
            release.countDown(); runBlocking { claim.await(); user.await() }
            val s = store.load()
            assertFalse("issued=$issued: the probe stays cleared", s.wakeProbe); assertEquals(0L, s.autoStoppedAtMs); assertEquals(0, s.wakeStep); assertFalse(s.manualStop)
            assertEquals(s, e.machine.state()!!.saved)
            pool.shutdown()
        }
    }

    // ---- Finding 4: a submit never touches the disk on the caller thread ----

    @Test fun submittingFromTheMainThreadNeverLoadsOrSavesOnIt() {
        val threads = Collections.synchronizedList(mutableListOf<String>())
        val store = object : AutoStopStore {
            private val inner = InMemoryAutoStopStore(AutoStopSnapshot(autoStoppedAtMs = 1L, reason = BackgroundPolicy.Away.NOT_WORN, nextWakeAtMs = 0L))
            override fun load() = inner.load().also { threads += "load@" + Thread.currentThread().name }
            override fun save(s: AutoStopSnapshot): Boolean { threads += "save@" + Thread.currentThread().name; return inner.save(s) }
        }
        val io = Executors.newSingleThreadExecutor { Thread(it, "actor-io") }
        val e = AutoStopEngine(store, { 1_000 * min }, io, { WakeInputs(true, 20, true, false) }, { true }, {}, {}, {})
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        val d = main.submit<kotlinx.coroutines.CompletableDeferred<Any?>> { e.submit(AutoStopEvent.ProcessStarted("process start")) }.get(5, TimeUnit.SECONDS)
        assertEquals(AutoStopClaimed(AutoStopWake.Claim.STARTED), runBlocking { d.await() })
        assertTrue(threads.toString(), threads.isNotEmpty() && threads.none { it.endsWith("@fake-main") })
        io.shutdown(); main.shutdown()
    }

    // ---- Finding 3: the watchdog ladder: a reset during a delayed save is never lost; persisted == in-memory ----

    @Test fun aPositiveSignalDuringADelayedHubStartSaveIsKeptAndPersisted() {
        val inSave = CountDownLatch(1); val release = CountDownLatch(1); val gate = java.util.concurrent.atomic.AtomicBoolean(true)
        val inner = InMemoryHubRestartStore()
        val store = object : HubRestartStore {
            override fun load() = inner.load()
            override fun save(s: HubRestartSnapshot): Boolean { if (gate.compareAndSet(true, false)) { inSave.countDown(); release.await(5, TimeUnit.SECONDS) }; return inner.save(s) }
        }
        val pool = Executors.newFixedThreadPool(2)
        var starts = 0
        val w = GlassesWatchdog(
            now = { 10 * min }, enabled = { true }, captureActive = { true }, hubRunning = { false }, link = { LinkState.OFFLINE }, lastHeardAtMs = { null },
            standingDown = { false }, startHub = { starts++ }, reconnect = {}, probeAlive = {}, relaunch = { null }, publish = {}, log = {}, warn = {}, hubStore = store, executor = pool,
        )
        val a = Thread { w.tick() }.apply { start() }
        sec(inSave) // the attempt (step 1) is being committed
        w.onPositiveSignal() // the glasses came back meanwhile
        release.countDown(); a.join(5_000)
        val want = HubRestartSnapshot(BackgroundPolicy.reset(), 10 * min)
        val end = System.nanoTime() + 5_000_000_000L
        while (inner.load() != want && System.nanoTime() < end) Thread.sleep(1) // the queued reset is committed by the actor thread
        assertEquals(1, starts)
        assertEquals("the reset after the attempt wins, on disk too (the delayed save did not overwrite it)", want, inner.load())
        pool.shutdown()
    }

    @Test fun watchdogLadderInterleavingsKeepPersistedEqualToInMemoryAndReplay() {
        repeat(100) { seed ->
            val rnd = Random(seed)
            val store = InMemoryHubRestartStore(); val clock = AtomicLong(10 * min)
            val trace = Collections.synchronizedList(mutableListOf<Triple<WatchdogLadder.Event, Long, WatchdogLadder.State>>())
            val bad = Collections.synchronizedList(mutableListOf<String>())
            val pool = Executors.newFixedThreadPool(4)
            val m = SerialStateMachine<WatchdogLadder.State, WatchdogLadder.Event, WatchdogLadder.Effect>(
                load = { WatchdogLadder.State(saved = store.load()) }, reduce = WatchdogLadder::reduce, persisted = { it.saved }, save = { store.save(it.saved) },
                now = { clock.get() }, executor = pool,
                onStep = { e, t, s -> trace += Triple(e, t, s); if (store.load() != s.saved) bad += "diverged after $e" },
            )
            fun ev(): WatchdogLadder.Event = when (rnd.nextInt(6)) {
                0 -> WatchdogLadder.Event.PositiveSignal; 1 -> WatchdogLadder.Event.HubWanted; 2 -> WatchdogLadder.Event.HubRunning; 3 -> WatchdogLadder.Event.GlassesPresent
                4 -> WatchdogLadder.Event.TryHubStart(clock.addAndGet(rnd.nextLong(0, 20 * min))); else -> WatchdogLadder.Event.TryReconnect(clock.addAndGet(rnd.nextLong(0, 20 * min)))
            }
            val plans = List(3) { List(40) { ev() } }
            val done = Collections.synchronizedList(mutableListOf<kotlinx.coroutines.CompletableDeferred<Any?>>())
            plans.map { p -> Thread { p.forEach { done += m.submit(it) } } }.onEach { it.start() }.forEach { it.join(10_000) }
            runBlocking { done.toList().forEach { it.await() } }
            pool.shutdown()
            assertEquals("seed $seed", emptyList<String>(), bad)
            var s = WatchdogLadder.State()
            for ((e, t, after) in trace) { s = WatchdogLadder.reduce(s, e, t).state; assertEquals("seed $seed replay", after, s) }
            assertEquals(m.state(), s); assertEquals(store.load(), s.saved)
        }
    }
}
