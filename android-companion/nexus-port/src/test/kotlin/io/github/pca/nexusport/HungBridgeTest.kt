package io.github.pca.nexusport

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Codex v2 r2 P1-1 and P1-3: privacy shutdown never depends on a binder call returning. A fake bridge whose calls block (forever, or until released): hold() and trip() return within a small bound, nothing queued is
 * delivered after them, the bridge is declared unhealthy (wipe debt owed, backoff), and wipe acknowledgements are correlated with a debt generation.
 */
class HungBridgeTest {
    private class Fence {
        private val lock = Any()
        private var epoch = 0L
        var held = false
        fun hold() { synchronized(lock) { held = true; epoch++ } }
        fun release() { synchronized(lock) { held = false } }
        fun pass(): NexusPass { val e = synchronized(lock) { epoch }; val h = synchronized(lock) { held }
            return object : NexusPass {
                override fun <T : Any> act(block: () -> T): T? = synchronized(lock) { if (!held && !h && epoch == e) block() else null }
            } }
    }

    private val fake = FakeNexusTransport()
    private val g = FakeGates()
    private val fence = Fence().also { f -> g.passOverride = { f.pass() } }
    private val gate = CountDownLatch(1)
    private val entered = AtomicInteger()
    private val showCalls = AtomicInteger()
    @Volatile private var hangShow = false
    @Volatile private var hangHide = false
    @Volatile private var hangFeatures = false
    private fun block() { entered.incrementAndGet(); gate.await(30, TimeUnit.SECONDS) }

    private val hung = object : NexusTransport by fake {
        override fun show(cmd: NexusCommand, epoch: Long): Int { showCalls.incrementAndGet(); if (hangShow) block(); return fake.show(cmd, epoch) }
        override fun hide(slot: Slot): Int { if (hangHide) block(); return fake.hide(slot) }
        override fun setFeatures(mask: Int): Int { if (hangFeatures) block(); return fake.setFeatures(mask) }
        override fun stopSpeech(): Int { if (hangHide) block(); return fake.stopSpeech() }
        override fun stopListening(): Int { if (hangHide) block(); return fake.stopListening() }
    }

    private val disp = NexusDispatcher.threaded(callTimeoutMs = 250, maxQueue = 32, backoffBaseMs = 60, backoffMaxMs = 200)
    private fun sink() = NexusHudSink(hung, g, { ON_TTS }, FakeActions(), { 0L }, {}, FakeAsks(), dispatcher = disp)
    private fun waitFor(ms: Long = 5_000, c: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (!c() && System.nanoTime() < end) Thread.sleep(5) }
    private inline fun bounded(maxMs: Long, what: String, block: () -> Unit) { val t0 = System.nanoTime(); block(); val ms = (System.nanoTime() - t0) / 1_000_000; assertTrue(ms < maxMs, "$what took ${ms}ms with a hung bridge") }

    @Test fun holdAndTripReturnWhileTheBridgeNeverAnswersAndNothingQueuedIsDelivered() {
        val s = sink()
        hangShow = true
        var first: Outcome? = null; var second: Outcome? = null
        val t1 = thread { first = runBlocking { s.show(nudge("n1")) } }
        waitFor { entered.get() == 1 }
        val t2 = thread { second = runBlocking { s.show(nudge("n2")) } }
        waitFor { disp.pending() == 1 }
        bounded(400, "hold + trip") { fence.hold(); s.trip(TripReason.ZONE_HOLD) }
        t1.join(5_000); t2.join(5_000)
        assertFalse(t1.isAlive || t2.isAlive, "the show calls end (timeout, discard) instead of hanging")
        assertEquals(1, showCalls.get(), "the show queued behind the hung one was never sent")
        assertTrue(first is Outcome.Dropped && second is Outcome.Dropped, "$first $second")
        assertTrue(s.forgetOwed(), "an unhealthy bridge owes a wipe (the notice in flight may still land)")
        assertEquals(1, s.liveHandles(), "the handle of the in-flight show stays until its hide is confirmed")
        gate.countDown()
    }

    @Test fun aShowEnqueuedBeforeTheHoldButRunAfterItIsNeverSent() {
        val s = sink()
        hangFeatures = true
        s.refreshFeatures()                                    // the worker is busy inside a slow call
        waitFor { entered.get() == 1 }
        var out: Outcome? = null
        val t1 = thread { out = runBlocking { s.show(nudge()) } }
        waitFor { disp.pending() == 1 }
        bounded(400, "hold + trip") { fence.hold(); s.trip(TripReason.ZONE_HOLD) }
        gate.countDown()                                       // the slow call finishes; the queued show is next
        t1.join(5_000)
        assertEquals(Outcome.Dropped(DropReason.TRIPPED), out)
        assertEquals(0, showCalls.get(), "re-checked on the dispatcher thread right before the call")
    }

    @Test fun aHoldBetweenAdmissionAndTheCallStillWinsViaTheTicketRecheck() {
        val s = sink()
        hangFeatures = true; s.refreshFeatures(); waitFor { entered.get() == 1 }
        var out: Outcome? = null
        val t1 = thread { out = runBlocking { s.show(nudge()) } }
        waitFor { disp.pending() == 1 }
        fence.hold()                                           // the fence closes but the sink is NOT tripped (e.g. the trip hook is slow)
        gate.countDown(); t1.join(5_000)
        assertEquals(Outcome.Dropped(DropReason.TRIPPED), out)
        assertEquals(0, showCalls.get())
    }

    @Test fun aHideThatHungIsRetriedAfterTheBackoffAndTheHandleLeavesOnlyWhenConfirmed() {
        val s = sink()
        runBlocking { assertEquals(Outcome.Shown(false), s.show(nudge())) }
        hangHide = true
        bounded(400, "trip") { s.trip(TripReason.PAUSE) }
        waitFor { s.forgetOwed() }                             // the hide timed out: unhealthy
        assertEquals(1, s.liveHandles(), "not confirmed hidden")
        hangHide = false
        Thread.sleep(300)                                      // the backoff (60 ms, doubling, 200 ms ceiling) is over
        runBlocking { s.tick() }
        waitFor { s.liveHandles() == 0 }
        assertEquals(0, s.liveHandles()); assertTrue(fake.live.isEmpty())
        gate.countDown()
    }

    @Test fun nothingIsSentWhileTheBridgeBacksOff() {
        val s = sink()
        hangFeatures = true; s.refreshFeatures(); waitFor { s.forgetOwed() }       // timed out -> unhealthy
        showCalls.set(0)
        runBlocking { assertTrue(s.show(nudge()) is Outcome.Dropped) }
        assertEquals(0, showCalls.get(), "no call during the backoff")
        gate.countDown()
    }

    // ---- P1-3 ----------------------------------------------------------------------------------------------------

    private val direct get() = NexusHudSink(fake, g, { ON }, FakeActions(), { 0L })

    @Test fun aLateAcknowledgementOfAnOlderWipeNeverClearsANewerDebt() = runBlocking<Unit> {
        val s = direct
        s.trip(TripReason.FORGET)                              // wipe A pending
        val a = s.wipeGeneration()
        s.show(nudge("b"))                                     // notice B
        fake.forgetResult = NexusResult.ERROR
        s.trip(TripReason.FORGET)                              // Forget B: another wipe, which fails
        val b = s.wipeGeneration()
        assertTrue(b > a)
        s.events.onForgetAccepted(a)                           // the delayed acknowledgement of A
        assertTrue(s.forgetOwed(), "B is still owed")
        fake.forgetResult = NexusResult.OK
        s.tick()
        assertEquals(b, fake.forgetGens.last(), "the retry covers the newest generation")
        s.events.onForgetAccepted(b)
        assertFalse(s.forgetOwed())
    }

    @Test fun anAcknowledgementOfANewerGenerationSettlesTheOlderOnesToo() {
        val s = direct
        s.trip(TripReason.FORGET); s.trip(TripReason.DELETE_ALL)
        s.events.onForgetAccepted(s.wipeGeneration())
        assertFalse(s.forgetOwed())
    }

    @Test fun aWipeRequestCarriesTheGenerationItCovers() {
        val s = direct
        s.trip(TripReason.FORGET)
        assertEquals(listOf(s.wipeGeneration()), fake.forgetGens)
    }

    @Test fun tripTellsTheBridgeItsEpoch() = runBlocking<Unit> {
        val s = direct
        s.show(nudge()); s.trip(TripReason.PAUSE)
        assertEquals(1, fake.epochs.size); assertEquals(1, fake.trippedEpochs.size)
        assertTrue(fake.trippedEpochs.single() > fake.epochs.single(), "a show admitted before the trip carries an older epoch than the trip notice")
        s.show(HudEvent.CaptureState(HudEvent.CaptureState.State.CAPTURING))
        assertEquals(fake.trippedEpochs.single(), fake.epochs.last(), "a show admitted after the trip is current")
    }
}
