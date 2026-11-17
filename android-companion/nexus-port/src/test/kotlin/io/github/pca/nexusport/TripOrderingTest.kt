package io.github.pca.nexusport

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Codex v2 r3 P1-1..P1-4. A fake bridge that knows trip epochs (it refuses a show stamped with an older epoch BEFORE drawing anything, as the real one does) and blocks on demand:
 * a trip is ordered with delivery and returns within its bound whatever the bridge does; a withdrawn show is never drawn; a show that lands after an acknowledged wipe re-owes it; privacy operations survive saturation.
 */
class TripOrderingTest {
    private val fake = FakeNexusTransport()
    private val g = FakeGates()
    private val gate = CountDownLatch(1)
    private val entered = AtomicInteger()
    private val order = CopyOnWriteArrayList<String>()
    @Volatile private var bridgeEpoch = 0L
    @Volatile private var learned = false
    private val enteredAfterLearn = AtomicInteger()
    private val showCalls = AtomicInteger()
    @Volatile private var hangShow = false
    @Volatile private var hangTrip = false
    @Volatile private var hangFeatures = false
    private val stopListenSeen = CountDownLatch(1)
    /** A binder call is not interruptible: the fake ignores the interrupt a timeout sends. */
    private fun block() {
        entered.incrementAndGet()
        val end = System.nanoTime() + 30_000_000_000L
        while (gate.count > 0 && System.nanoTime() < end) try { gate.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
    }

    private val bridge = object : NexusTransport by fake {
        override fun show(cmd: NexusCommand, epoch: Long): Int {
            showCalls.incrementAndGet()
            if (learned) enteredAfterLearn.incrementAndGet()
            if (epoch < bridgeEpoch) return NexusResult.STALE          // refused before anything is drawn
            if (hangShow) { block(); if (epoch < bridgeEpoch) return NexusResult.STALE }     // the real bridge re-checks after the draw and hides it again
            order += "show"
            return fake.show(cmd, epoch)
        }
        override fun tripped(epoch: Long): Int {
            if (hangTrip) block()
            if (epoch > bridgeEpoch) bridgeEpoch = epoch
            learned = true; order += "tripped"
            return fake.tripped(epoch)
        }
        override fun setFeatures(mask: Int): Int { if (hangFeatures) block(); return fake.setFeatures(mask) }
        override fun stopListening(): Int { stopListenSeen.countDown(); return fake.stopListening() }
    }

    private fun disp(call: Long = 3_000, maxQueue: Int = 32, maxPrivacy: Int = 64, exclusive: Long = 250, tripMs: Long = 500) =
        NexusDispatcher.threaded(callTimeoutMs = call, maxQueue = maxQueue, backoffBaseMs = 20, backoffMaxMs = 60, maxPrivacy = maxPrivacy, exclusiveWaitMs = exclusive, tripTimeoutMs = tripMs)
    private fun sink(d: NexusDispatcher) = NexusHudSink(bridge, g, { ON_TTS }, FakeActions(), { 0L }, {}, FakeAsks(), dispatcher = d)
    private fun waitFor(ms: Long = 5_000, c: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (!c() && System.nanoTime() < end) Thread.sleep(5) }
    private inline fun bounded(maxMs: Long, what: String, block: () -> Unit) { val t0 = System.nanoTime(); block(); val ms = (System.nanoTime() - t0) / 1_000_000; assertTrue(ms < maxMs, "$what took ${ms}ms") }

    // ---- P1-1 ----------------------------------------------------------------------------------------------------

    @Test fun aTripReturnsWithinItsBoundWhileASendNeverReturnsAndTheBridgeKnowsTheEpochBeforeAnythingElseIsSent() {
        val s = sink(disp())
        hangShow = true
        var out: Outcome? = null
        val t1 = thread { out = runBlocking { s.show(nudge("n1")) } }
        waitFor { entered.get() == 1 }
        var late: Outcome? = null
        val t2 = thread { late = runBlocking { s.show(nudge("n2")) } }     // queued behind the stuck send
        waitFor { s.dispatcherPending() == 1 }
        g.zone = true                                          // the zone holds, then trips
        bounded(1_200, "trip with a send stuck inside the binder") { s.trip(TripReason.ZONE_HOLD) }
        assertTrue(learned, "the bridge was told the epoch before trip() returned")
        t2.join(5_000)
        assertTrue(late is Outcome.Dropped, "$late")
        val callsBefore = showCalls.get()
        runBlocking { assertTrue(s.show(nudge("n4")) is Outcome.Dropped) }
        assertEquals(callsBefore, showCalls.get(), "nothing is sent after the trip")
        assertEquals(0, enteredAfterLearn.get(), "zero deliveries reached the bridge after it learned the epoch")
        gate.countDown(); t1.join(5_000)
        assertTrue(fake.shown.isEmpty(), "the send that was stuck was refused by the bridge's epoch once it resumed, or never drew: ${fake.shown.size}")
        assertFalse(t1.isAlive, "$out")
    }

    @Test fun aTripReturnsWithinItsBoundWhenTheEpochNoticeItselfNeverReturns() {
        val s = sink(disp(tripMs = 300))
        hangTrip = true
        bounded(1_200, "trip with a hung epoch notice") { s.trip(TripReason.PAUSE) }
        gate.countDown()
    }

    @Test fun aTripWaitsForTheSendInFlightAndTheBridgeLearnsTheEpochAfterItNeverBefore() = runBlocking<Unit> {
        val s = sink(disp(exclusive = 10_000))
        hangShow = true
        val t1 = thread { runBlocking { s.show(nudge("n1")) } }
        waitFor { entered.get() == 1 }
        var done = false
        val tt = thread { s.trip(TripReason.PAUSE); done = true }
        Thread.sleep(150)
        assertFalse(done, "the trip waits (bounded) for the send that is inside the binder")
        assertFalse(learned, "the bridge is not told while a send is still in flight: that send is ordered before the epoch")
        gate.countDown(); t1.join(5_000); tt.join(5_000)
        assertTrue(done)
        assertEquals(listOf("show", "tripped"), order.toList(), "delivery, then the epoch")
    }

    // ---- P1-2 ----------------------------------------------------------------------------------------------------

    @Test fun aReplacementQueuedBehindABusyWorkerIsCancelledByTheDismissOfTheNoticeItReplaces() = runBlocking<Unit> {
        val s = sink(disp())
        assertEquals(Outcome.Shown(false), s.show(nudge("a")))
        hangFeatures = true
        s.refreshFeatures()                                     // the worker is busy
        waitFor { entered.get() == 1 }
        var b: Outcome? = null
        val tb = thread { b = runBlocking { s.show(nudge("b")) } }
        waitFor { s.liveHandles() == 1 && s.dispatcherPending() == 1 }
        s.events.onAction(Slot.NOTICE.code, HudMapper.DISMISS)  // the user dismisses what is drawn (A); B is the registered handle
        tb.join(5_000)
        assertTrue(b is Outcome.Dropped, "$b")
        gate.countDown()
        waitFor { s.liveHandles() == 0 }
        assertEquals(1, showCalls.get(), "B was never sent")
        assertEquals(0, s.liveHandles())
        s.trip(TripReason.ZONE_HOLD)
        assertTrue(fake.live.isEmpty())
    }

    // ---- P1-3 ----------------------------------------------------------------------------------------------------

    @Test fun aStalledShowThatResumesAfterHideWipeAndAcknowledgementRe_OwesTheWipe() = runBlocking<Unit> {
        val s = sink(disp(call = 150))
        hangShow = true
        val t1 = thread { runBlocking { s.show(nudge("n1")) } }
        waitFor { s.forgetOwed() }                              // the call timed out: unhealthy, debt owed
        t1.join(5_000)
        s.trip(TripReason.FORGET)                               // hide -> wipe
        Thread.sleep(100)                                       // the backoff (20..60 ms) is over
        s.tick()
        waitFor { fake.forgetGens.isNotEmpty() }
        val acked = fake.forgetGens.last()
        s.events.onForgetAccepted(acked)                        // the hub acknowledges while the stalled show has not come back
        assertTrue(s.forgetOwed(), "the stalled show may still create a notice: the acknowledgement does not clear the debt")
        val before = fake.forgetGens.size
        gate.countDown()                                        // the stalled show resumes and draws
        waitFor { s.inFlightShowCount() == 0 }
        assertTrue(s.forgetOwed())
        Thread.sleep(100)
        s.tick()
        waitFor { fake.forgetGens.size > before }
        val newest = fake.forgetGens.last()
        assertTrue(newest > acked, "a newer wipe generation covers the late notice")
        s.events.onForgetAccepted(newest)
        assertFalse(s.forgetOwed())
    }

    // ---- P1-4 ----------------------------------------------------------------------------------------------------

    @Test fun contentSaturationRejectsShowsButNeverAPrivacyOperation() = runBlocking<Unit> {
        val s = sink(disp(maxQueue = 2))
        assertEquals(Outcome.Shown(false), s.show(nudge("a")))
        hangFeatures = true; s.refreshFeatures(); waitFor { entered.get() == 1 }
        val outs = CopyOnWriteArrayList<Outcome>()
        val ts = (1..4).map { i -> thread { outs += runBlocking { s.show(nudge("c$i")) } } }
        waitFor { outs.count { it is Outcome.Dropped } >= 2 }
        assertTrue(outs.any { it == Outcome.Dropped(DropReason.TRANSPORT, NexusResult.OK) || (it as? Outcome.Dropped)?.reason == DropReason.TRANSPORT }, "a full content lane rejects")
        s.trip(TripReason.PAUSE)                                // accepted on the privacy lane although the content lane is full
        gate.countDown(); ts.forEach { it.join(5_000) }
        waitFor { fake.live.isEmpty() && s.liveHandles() == 0 }
        assertTrue(fake.live.isEmpty() && s.liveHandles() == 0, "the hide went through: ${fake.live} ${s.liveHandles()}")
    }

    @Test fun aStopListeningIsNeverLostWhenThePrivacyLaneIsFullEvenWithoutAListenHandle() {
        val s = sink(disp(maxPrivacy = 1))
        hangFeatures = true; s.refreshFeatures(); waitFor { entered.get() == 1 }    // the worker is stuck
        s.refreshFeatures()                                     // fills the one privacy slot
        s.trip(TripReason.PAUSE)                                // no LISTEN handle, a session may be opening
        assertTrue(stopListenSeen.await(5, TimeUnit.SECONDS), "the stop ran on a fresh thread although the lane was full and the worker stuck")
        gate.countDown()
    }
}
