package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Review hub-wp2 r2 P1-2 / P2-4: the launch follows a durable record AND a fresh look at every guard; a record that is not durable launches nothing. */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassesRelauncherReviewR2Test {
    /** An IO dispatcher whose work runs only when the test lets it (the suspension of the durable write is real, never Unconfined). */
    private class HeldIo : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class Guards : RelaunchGuards {
        @Volatile var call = true
        override fun callIdle() = call; override fun mediaIdle() = true; override fun worn(): Boolean? = null
    }

    private fun TestScope.pump(io: HeldIo) { repeat(3) { io.runAll(); runCurrent() } }

    @Test fun aCallThatStartsWhileTheDurableWriteIsHeldBlocksTheLaunchAndTheGuardedRetryFollows() = runTest {
        val io = HeldIo(); val g = Guards(); val store = InMemoryRelaunchStore(); var launches = 0
        val state = MutableStateFlow(LinkState.CONNECTED)
        val r = GlassesRelauncher(backgroundScope, state, { true }, g, { launches++; state.value = LinkState.CONNECTING }, { currentTime }, {}, store = store, io = io)
        r.start(); runCurrent(); state.value = LinkState.CLOSED_ON_GLASSES; runCurrent()
        advanceTimeBy(8_100); runCurrent() // the guards passed; the write is now held on the IO dispatcher
        g.call = false // a call starts while the write is in flight
        pump(io)
        assertEquals("no launch during the call", 0, launches)
        assertEquals("the unused reservation was taken back", emptyList<Long>(), store.attempts())
        advanceTimeBy(30_000); pump(io); assertEquals("still in the call", 0, launches)
        g.call = true; advanceTimeBy(10_100); pump(io)
        assertEquals("the guarded retry launches once the call is over", 1, launches); assertEquals(1, store.attempts().size)
    }

    /** A store whose durable write can be made to fail; the plain attempts are shared (the "disk"). */
    private class FlakyStore(val disk: InMemoryRelaunchStore, val ok: AtomicBoolean) : RelaunchStore by disk {
        override fun saveAttemptsDurable(a: List<Long>): Boolean { if (!ok.get()) return false; disk.saveAttempts(a); return true }
    }

    @Test fun aFailedDurableRecordNeverLaunchesAcrossTwoRelauncherInstances() = runTest {
        val disk = InMemoryRelaunchStore().apply { saveAttempts(List(5) { 0L }) } // five attempts at t=0: one left in the hour
        val ok = AtomicBoolean(false); var launches = 0
        fun instance(): Pair<GlassesRelauncher, MutableStateFlow<LinkState>> {
            val state = MutableStateFlow(LinkState.CONNECTED)
            val r = GlassesRelauncher(backgroundScope, state, { true }, Guards(), { launches++; state.value = LinkState.CONNECTING }, { currentTime }, {}, store = FlakyStore(disk, ok), io = kotlinx.coroutines.Dispatchers.Unconfined)
            r.start(); return r to state
        }
        val (a, sa) = instance(); runCurrent(); sa.value = LinkState.CLOSED_ON_GLASSES; runCurrent(); advanceTimeBy(60_000)
        assertEquals("the record cannot be made durable: no launch", 0, launches)
        a.stop() // the process dies; a rebuilt relauncher reads the same disk
        val (b, sb) = instance(); runCurrent(); sb.value = LinkState.CLOSED_ON_GLASSES; runCurrent(); advanceTimeBy(60_000)
        assertEquals("still none from the second instance", 0, launches); assertEquals("the disk record is untouched", 5, disk.attempts().size)
        ok.set(true); advanceTimeBy(20_000)
        assertEquals("once the store works, the one remaining attempt is used", 1, launches); assertEquals("six in the hour, never seven", 6, disk.attempts().size)
        b.stop()
    }
}
