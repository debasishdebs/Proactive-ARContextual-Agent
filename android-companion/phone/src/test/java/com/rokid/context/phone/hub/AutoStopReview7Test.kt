package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Codex review 7: write-behind persistence. The in-memory state is authoritative; only the latest snapshot is written; no event is ever replayed, lost or reordered. */
class AutoStopReview7Test {
    private val min = 60_000L

    @Test fun aWakeWhoseResultCannotBeSavedStillCompletesAndCaptureIsNotStopped() {
        // the commit of the wake's result (wake in progress) fails; everything else saves
        val inner = InMemoryAutoStopStore()
        val store = object : AutoStopStore {
            var failWakeResult = true
            override fun load() = inner.load()
            override fun save(s: AutoStopSnapshot): Boolean { if (failWakeResult && s.wakeStartedAtMs != 0L) { failWakeResult = false; return false }; return inner.save(s) }
        }
        val retries = mutableListOf<Runnable>(); var t = 1_000_000L; var running = true; var worn: Boolean? = false; var link = LinkState.CONNECTED
        val engine = AutoStopEngine(store, { t }, SerialStateMachine.DIRECT, { null }, { false }, {}, {}, {}, schedule = { _, r -> retries += r })
        val c = AutoStopController(
            now = { t }, minutes = { 20 }, hubRunning = { running }, link = { link }, worn = { worn }, inFront = { false }, lastFrontMs = { 0L }, busy = { false }, runActive = { false },
            stopHub = { running = false }, wakeHub = { true }, exempt = { true }, linkAllowed = { true }, publishNote = {}, timeText = { "x" }, log = {}, engine = engine,
        )
        c.evaluate(); t += 20 * min; c.evaluate(); assertFalse("auto-stopped", running)
        t += 60 * min; c.evaluate() // the wake: issued, its result's write fails
        assertTrue("in memory the wake is in progress", engine.machine.state()!!.saved.wakeStartedAtMs != 0L)
        running = true; engine.submit(AutoStopEvent.HubForeground) // the service entered the foreground
        worn = true; c.evaluate() // connected and worn: the ladder resets
        val vis = engine.visible()!!
        assertEquals("the wake completed", 0L, vis.autoStoppedAtMs)
        assertTrue("capture is wanted: the watchdog does not stop it", BackgroundPolicy.hubNeeded(BackgroundPolicy.Needs(true, true, false, vis.autoStoppedAtMs != 0L, vis.manualStop)))
        retries.toList().forEach { it.run() }
        assertEquals("the latest snapshot is on disk", engine.machine.state()!!.saved, inner.load()); assertEquals(0L, inner.load().autoStoppedAtMs)
    }

    // ---- property: random commit() failures ----

    private class FailingStore(seed: Int) : AutoStopStore {
        private val rnd = Random(seed); @Volatile var failRate = 0.35; @Volatile private var s = AutoStopSnapshot()
        override fun load() = s
        override fun save(s: AutoStopSnapshot): Boolean = synchronized(this) { if (rnd.nextDouble() < failRate) false else { this.s = s; true } }
    }

    private fun randomEvent(r: Random): AutoStopEvent = when (r.nextInt(11)) {
        in 0..5 -> AutoStopEvent.Evaluate(
            WakeInputs(r.nextInt(4) != 0, listOf(5, 20, 0)[r.nextInt(3)], r.nextInt(5) != 0, r.nextBoolean()),
            HubSignals(r.nextInt(3) != 0, listOf(LinkState.CONNECTED, LinkState.OFFLINE)[r.nextInt(2)], listOf(true, false, null)[r.nextInt(3)], r.nextInt(5) == 0, 0L, r.nextInt(6) == 0, false),
        )
        6, 7 -> AutoStopEvent.ProcessStarted("process start", WakeInputs(r.nextInt(4) != 0, 20, true, r.nextBoolean()))
        8 -> AutoStopEvent.UserStarted
        9 -> AutoStopEvent.HubForeground
        else -> AutoStopEvent.ManualStopped
    }

    @Test fun withRandomCommitFailuresNoEventIsLostOrReorderedAndDiskCatchesUp() {
        repeat(120) { seed ->
            val store = FailingStore(seed); val clock = AtomicLong(1_000_000L)
            val trace = Collections.synchronizedList(mutableListOf<Triple<AutoStopEvent, Long, AutoStopState>>())
            val retries = Collections.synchronizedList(mutableListOf<Runnable>()); val bad = Collections.synchronizedList(mutableListOf<String>())
            val pool = Executors.newFixedThreadPool(4)
            lateinit var engine: AutoStopEngine
            engine = AutoStopEngine(store, { clock.get() }, pool, { null }, { Random(clock.get()).nextBoolean() }, {}, {}, {}, onStep = { e, t, s -> trace += Triple(e, t, s) },
                schedule = { _, r -> retries += r })
            engine.attach(AutoStopEngine.HubPort({ null }, { null }, {
                // conservative: never a stop decided on an unsaved snapshot
                if (engine.machine.state()!!.persistFailing) bad += "stopped while the latest snapshot was unsaved"
            }, { false }, {}, {}, { "x" }, {}))
            val rnd = Random(seed)
            val plans = List(3) { List(40) { randomEvent(rnd) to rnd.nextLong(0, 12 * min) } }
            val origin = IdentityHashMap<AutoStopEvent, Pair<Int, Int>>()
            plans.forEachIndexed { th, p -> p.forEachIndexed { i, (e, _) -> if (e is AutoStopEvent.Evaluate || e is AutoStopEvent.ProcessStarted) origin[e] = th to i } }
            val done = Collections.synchronizedList(mutableListOf<kotlinx.coroutines.CompletableDeferred<Any?>>())
            plans.mapIndexed { _, p -> Thread { for ((e, dt) in p) { clock.addAndGet(dt); done += engine.submit(e) } } }.onEach { it.start() }.forEach { it.join(10_000) }
            runBlocking { done.toList().forEach { it.await() } }
            assertEquals("seed $seed: $bad", emptyList<String>(), bad)

            // replay: the trace is ONE sequential order; folding the pure reducer over it gives every recorded state and the final one
            var s = AutoStopState(saved = AutoStopSnapshot())
            for ((e, t, after) in trace) { s = AutoStopMachine.reduce(s, e, t).state; assertEquals("seed $seed replay", after, s) }
            assertEquals("seed $seed final", engine.machine.state(), s)
            // nothing lost, nothing reordered: every submitted event reduced exactly once, each thread's in its submission order
            val seen = trace.map { it.first }.filter { origin.containsKey(it) }
            assertEquals("seed $seed: every identifiable event exactly once", origin.size, seen.size); assertEquals(origin.size, Collections.newSetFromMap(IdentityHashMap<AutoStopEvent, Boolean>()).apply { addAll(seen) }.size)
            for (th in 0..2) { val idx = seen.map { origin[it]!! }.filter { it.first == th }.map { it.second }; assertEquals("seed $seed thread $th order", idx.sorted(), idx) }
            for (single in listOf(AutoStopEvent.UserStarted, AutoStopEvent.HubForeground, AutoStopEvent.ManualStopped))
                assertEquals("seed $seed $single count", plans.sumOf { p -> p.count { it.first === single } }, trace.count { it.first === single })

            // disk catches up: once commits succeed, the retry writes the latest snapshot
            store.failRate = 0.0
            val end = System.nanoTime() + 5_000_000_000L
            while (store.load() != engine.machine.state()!!.saved && System.nanoTime() < end) { retries.toList().also { retries.clear() }.forEach { it.run() }; Thread.sleep(2) }
            assertEquals("seed $seed: persisted == in-memory eventually", engine.machine.state()!!.saved, store.load())
            pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
