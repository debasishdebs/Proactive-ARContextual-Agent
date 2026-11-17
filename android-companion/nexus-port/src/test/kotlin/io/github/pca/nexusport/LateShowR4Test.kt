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
 * Codex v2 r4 P1-1 (partial) and P1-2. A show stuck in the bridge returns long after the wipe that should have covered it was acknowledged and the stuck-call cut-off passed:
 * unless the bridge CONFIRMS that nothing was created, the wipe is owed again. After a timeout no content goes out until a probe came back.
 */
class LateShowR4Test {
    private val fake = FakeNexusTransport()
    private val g = FakeGates()
    private val gate = CountDownLatch(1)
    private val showCalls = AtomicInteger()
    @Volatile private var hangShow = false
    @Volatile private var lateResult = NexusResult.OK

    private val bridge = object : NexusTransport by fake {
        override fun show(cmd: NexusCommand, epoch: Long): Int {
            showCalls.incrementAndGet()
            if (hangShow) {
                val end = System.nanoTime() + 30_000_000_000L
                while (gate.count > 0 && System.nanoTime() < end) try { gate.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                return lateResult
            }
            return fake.show(cmd, epoch)
        }
    }

    private fun disp() = NexusDispatcher.threaded(callTimeoutMs = 150, backoffBaseMs = 20, backoffMaxMs = 60)
    private fun sink() = NexusHudSink(bridge, g, { ON_TTS }, FakeActions(), { 0L }, {}, FakeAsks(), dispatcher = disp(), inFlightMaxNs = 50_000_000L)
    private fun waitFor(ms: Long = 5_000, c: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (!c() && System.nanoTime() < end) Thread.sleep(5) }

    /** The exact sequence: show stuck, timeout, Forget, wipe acknowledged AFTER the cut-off (debt settled), the show finally returns with [late]. */
    private fun sequence(late: Int): NexusHudSink = runBlocking {
        lateResult = late
        val s = sink()
        hangShow = true
        val t1 = thread { runBlocking { s.show(nudge("n1")) } }
        waitFor { s.forgetOwed() }                                // timed out: unhealthy, debt owed
        t1.join(5_000)
        s.trip(TripReason.FORGET)
        Thread.sleep(100)                                         // backoff over
        s.tick()
        waitFor { fake.forgetGens.isNotEmpty() }
        val acked = fake.forgetGens.last()
        Thread.sleep(80)                                          // past the (shortened) 120 s cut-off: the unfinished show no longer holds the acknowledgement back
        s.events.onForgetAccepted(acked)
        assertFalse(s.forgetOwed(), "settled: the cut-off passed")
        assertEquals(1, s.inFlightShowCount(), "the show is still tracked")
        gate.countDown()                                          // the stuck call returns
        waitFor { s.inFlightShowCount() == 0 }
        s
    }

    @Test fun aLateStaleShowThatCreatedHistoryReOwesTheWipeAfterTheCutoffSettledTheDebt() {
        val s = sequence(NexusResult.STALE or NexusResult.CREATED_HISTORY)      // the bridge hid it again right after it returned, but the hub kept the notice
        assertTrue(s.forgetOwed(), "created history: a new wipe is owed")
        val before = fake.forgetGens.size
        Thread.sleep(100); runBlocking { s.tick() }
        waitFor { fake.forgetGens.size > before }
        assertTrue(fake.forgetGens.size > before && fake.forgetGens.last() > fake.forgetGens.first())
    }

    @Test fun aLateOkShowReOwesTheWipeToo() {
        assertTrue(sequence(NexusResult.OK).forgetOwed())
    }

    @Test fun aLateStaleShowThatCreatedNothingDoesNotLoop() {
        val s = sequence(NexusResult.STALE)                        // confirmed: nothing created
        assertFalse(s.forgetOwed())
    }

    @Test fun aLateShowWhoseOutcomeIsUnknownCountsAsCreated() {
        assertTrue(sequence(NexusResult.ERROR).forgetOwed())
    }

    @Test fun createdMeansEverythingExceptAConfirmedNothing() {
        assertTrue(NexusResult.created(NexusResult.OK))
        assertTrue(NexusResult.created(NexusResult.STALE or NexusResult.CREATED_HISTORY))
        assertTrue(NexusResult.created(NexusResult.ERROR))
        assertFalse(NexusResult.created(NexusResult.STALE))
        assertFalse(NexusResult.created(NexusResult.INVALID))
        assertEquals(NexusResult.STALE, NexusResult.code(NexusResult.STALE or NexusResult.CREATED_HISTORY))
    }

    @Test fun afterATimeoutNoContentIsSentUntilAProbeCameBack() = runBlocking<Unit> {
        val s = sink()
        hangShow = true
        val t1 = thread { runBlocking { s.show(nudge("n1")) } }
        waitFor { s.forgetOwed() }
        t1.join(5_000)
        Thread.sleep(100)                                          // the backoff is over
        hangShow = false
        val o = s.show(nudge("n2"))
        assertTrue(o is Outcome.Dropped, "$o")
        assertEquals(1, showCalls.get(), "nothing was sent: the backoff alone is not a probe")
        s.tick()                                                   // the tick probes with a non-content call
        var shown = false
        val end = System.nanoTime() + 5_000_000_000L
        while (!shown && System.nanoTime() < end) { shown = s.show(nudge("n3")) is Outcome.Shown; if (!shown) Thread.sleep(10) }
        assertTrue(shown, "content flows again once the probe came back")
        gate.countDown()
    }
}
