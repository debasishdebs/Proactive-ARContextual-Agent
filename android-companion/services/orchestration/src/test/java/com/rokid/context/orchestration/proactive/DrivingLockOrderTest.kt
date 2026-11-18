package com.rokid.context.orchestration.proactive

import com.rokid.context.memory.place.ZoneFence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Codex v3.1 r3 P1-1: ONE lock order (zone fence -> driving commit monitor -> store lock) and a lock-free driving read. Several threads hammer delivery-shaped commits ([commitFenced]), Nexus-shaped reads (inside the
 * fence, through [DrivingSignal]-style lock-free reads) and tracker updates, with a zone controller holding and releasing the fence. Deterministic seeds, bounded time; a watchdog dumps every stack when something hangs.
 */
class DrivingLockOrderTest {
    private val clock = AtomicLong(10_000_000_000L)
    private val tracker = DrivingTracker { clock.get() }
    private val fence = ZoneFence()
    private val storeLock = Any()
    private val seams = ProactiveSeams(drivingCommit = { v, w -> tracker.commitIfNotDriving(v, w) }, drivingVersion = { tracker.version })
    private val direct: (() -> Unit) -> Unit = { it() }
    private var savedDispatch: (() -> Unit) -> Unit = direct
    private val withdrawals = AtomicInteger()
    private val listener: () -> Unit = { withdrawals.incrementAndGet(); synchronized(storeLock) { } ; fence.blocked() } // a listener takes the surfaces' / store / fence locks

    @Before fun setUp() { savedDispatch = SuggestionGateEvents.dispatch; SuggestionGateEvents.dispatch = direct; SuggestionGateEvents.addWithdrawListener(listener) }
    @After fun tearDown() { SuggestionGateEvents.removeWithdrawListener(listener); SuggestionGateEvents.dispatch = savedDispatch }

    private fun seedNotDriving() { repeat(70) { tracker.heartbeat(false); clock.addAndGet(10_000) }; refreshCalm() }
    private fun refreshCalm() { tracker.heartbeat(false); tracker.calm(CalmSource.PHONE_STEPS, Calm.WALKING); tracker.calm(CalmSource.PHONE_ACTIVITY, Calm.STILL) }

    private fun dumpAll(): String = Thread.getAllStackTraces().entries.joinToString("\n") { (t, st) -> "== ${t.name} (${t.state})\n" + st.joinToString("\n") { "   at $it" } }

    @Test fun deliveryNexusAndTrackerUpdatesNeverDeadlock() {
        seedNotDriving()
        val threads = 6; val iterations = 4_000
        val start = CountDownLatch(1); val done = CountDownLatch(threads)
        val commits = AtomicInteger(); val reads = AtomicInteger(); val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        fun worker(name: String, seed: Long, body: (Random) -> Unit) = Thread({
            try { start.await(); val r = Random(seed); repeat(iterations) { body(r) } } catch (t: Throwable) { failures += t } finally { done.countDown() }
        }, name).apply { isDaemon = true; start() }
        // two deliveries / feeds: fence -> driving commit -> store
        for (i in 0 until 2) worker("delivery-$i", 100L + i) { r ->
            val t = fence.ticket(); val dv = seams.drivingVersion()
            val out = seams.commitFenced(t, dv) { synchronized(storeLock) { if (r.nextInt(8) == 0) Thread.yield() } }
            if (out == FenceOutcome.RAN) commits.incrementAndGet()
        }
        // two Nexus-shaped readers: inside the fence they read the driving state (what publishableNow does) and the store
        for (i in 0 until 2) worker("nexus-$i", 200L + i) { r ->
            val t = fence.ticket()
            fence.act(t) { tracker.assess().blocks; tracker.version; synchronized(storeLock) { }; if (r.nextInt(8) == 0) Thread.yield(); reads.incrementAndGet() }
        }
        // the hub: heartbeats, calm readings, and (rarely) a vehicle; every closing update fires the listeners outside the tracker's lock
        worker("tracker", 300L) { r ->
            clock.addAndGet(r.nextInt(3_000).toLong())
            when (r.nextInt(10)) { 0 -> tracker.vehicle(0); 1, 2 -> refreshCalm(); 3 -> tracker.speed(r.nextFloat() * 10f); else -> tracker.heartbeat(false) }
            if (r.nextInt(50) == 0) { repeat(70) { tracker.heartbeat(false); clock.addAndGet(10_000) }; refreshCalm(); clock.addAndGet(11 * 60_000L) ; repeat(70) { tracker.heartbeat(false); clock.addAndGet(10_000) }; refreshCalm() }
        }
        // the zone controller: hold / release / trip, each cancelling jobs and running hooks outside the fence lock
        worker("zone", 400L) { r -> when (r.nextInt(3)) { 0 -> fence.hold(); 1 -> fence.release(); else -> fence.trip() } }
        start.countDown()
        if (!done.await(40, TimeUnit.SECONDS)) {
            val dump = dumpAll(); throw AssertionError("deadlock or livelock: workers did not finish in 40 s\n$dump")
        }
        assertTrue("worker failures: $failures", failures.isEmpty())
        assertTrue("some delivery commits got through while NOT_DRIVING and the fence was open (seeded run)", commits.get() > 0)
        assertTrue("closing transitions reached the withdraw listeners", withdrawals.get() > 0)
    }

    @Test fun theDrivingReadsTakeNoMonitor() {
        seedNotDriving()
        val gate = CountDownLatch(1); val release = CountDownLatch(1)
        // a writer parks INSIDE the commit monitor (the delivery's write); reads from another thread must still answer at once
        val holder = Thread({ tracker.commitIfNotDriving(tracker.version) { gate.countDown(); release.await(10, TimeUnit.SECONDS) } }, "monitor-holder").apply { isDaemon = true; start() }
        assertTrue(gate.await(5, TimeUnit.SECONDS))
        val answered = CountDownLatch(1)
        Thread({ tracker.assess(); tracker.version; tracker.assess().blocks; answered.countDown() }, "reader").apply { isDaemon = true; start() }
        val ok = answered.await(3, TimeUnit.SECONDS)
        release.countDown(); holder.join(5_000)
        assertTrue("assess() and version must not wait for the commit monitor", ok)
    }

    @Test fun theCommitVersionCheckStillRejectsAnUpdateAfterTheRead() {
        seedNotDriving()
        val v = tracker.version
        tracker.heartbeat(false) // any update moves the version
        var wrote = false
        assertEquals(false, tracker.commitIfNotDriving(v) { wrote = true }); assertEquals(false, wrote)
        assertEquals(true, tracker.commitIfNotDriving(tracker.version) { wrote = true }); assertEquals(true, wrote)
    }
}
