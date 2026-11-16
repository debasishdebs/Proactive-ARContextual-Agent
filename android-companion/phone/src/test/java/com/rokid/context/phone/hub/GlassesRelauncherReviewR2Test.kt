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

    /** Review hub-wp2 r3 P2-3: the expiry cleanup inside the post-write validation must not erase the reservation from disk. */
    @Test fun anAttemptExpiringWhileTheWriteIsHeldKeepsItsReservationOnDisk() = runTest {
        val io = HeldIo(); val store = InMemoryRelaunchStore().apply { saveAttempts(List(5) { 1L }) }; var launches = 0
        val state = MutableStateFlow(LinkState.CONNECTED)
        val clock = { currentTime + 3_600_000L - 8_500L } // the five old attempts expire 8.5 s into the test: after the reservation starts (8 s), before its held write completes
        val r = GlassesRelauncher(backgroundScope, state, { true }, Guards(), { launches++; state.value = LinkState.CONNECTING }, clock, {}, store = store, io = io)
        r.start(); runCurrent(); state.value = LinkState.CLOSED_ON_GLASSES; runCurrent()
        advanceTimeBy(8_100) // the attempt reserves at once (the five old ones still count) and holds on IO
        assertEquals("the write is still held", 0, launches)
        advanceTimeBy(1_000) // the old attempts expire DURING the held write
        pump(io)
        assertEquals(1, launches)
        val t = store.attempts()
        assertEquals("the reservation is on disk (the expired ones may or may not be)", 1, t.count { it > 1L })
    }

    /** Review hub-wp2 r3 P2-4: cancellation landing right after the write returned still takes the reservation back; six such writes leave the quota intact. */
    @Test fun sixCancelledWritesLeaveNoHourlyLimit() = runTest {
        val io = HeldIo(); val store = InMemoryRelaunchStore(); var launches = 0
        val state = MutableStateFlow(LinkState.CONNECTED)
        val r = GlassesRelauncher(backgroundScope, state, { true }, Guards(), { launches++; state.value = LinkState.CONNECTING }, { currentTime }, {}, store = store, io = io)
        r.start(); runCurrent()
        repeat(6) {
            state.value = LinkState.CLOSED_ON_GLASSES; runCurrent(); advanceTimeBy(8_100) // the write is held
            r.onUserExit() // cancels the attempt job while the noncancellable write is in flight
            pump(io)
            r.onExplicitOpen(); state.value = LinkState.CONNECTED; runCurrent()
        }
        assertEquals("nothing launched", 0, launches)
        assertEquals("every cancelled reservation was taken back", emptyList<Long>(), store.attempts())
        state.value = LinkState.CLOSED_ON_GLASSES; runCurrent(); advanceTimeBy(8_100); pump(io)
        assertEquals("no 'hourly limit': the seventh try launches", 1, launches)
    }

    /** Review hub-wp2 r3 P2-5 / P1: a not-eligible automatic open reserves nothing and launches nothing; user-initiated opens never go through here. */
    @Test fun aNotEligibleAttemptConsumesNoQuota() = runTest {
        val store = InMemoryRelaunchStore().apply { saveAttempts(List(5) { currentTime - 1 }) }; var launches = 0; var eligible = false
        val state = MutableStateFlow(LinkState.OFFLINE)
        val r = GlassesRelauncher(backgroundScope, state, { true }, Guards(), {}, { currentTime }, {}, store = store, io = kotlinx.coroutines.Dispatchers.Unconfined)
        repeat(3) { assertEquals("not eligible", r.attemptNow({ launches++ }, { eligible })); runCurrent() }
        assertEquals(0, launches); assertEquals("no slot reserved", 5, store.attempts().size)
        eligible = true; assertEquals(null, r.attemptNow({ launches++ }, { eligible })); runCurrent()
        assertEquals(1, launches); assertEquals(6, store.attempts().size)
    }

    private fun TestScope.rig(io: kotlin.coroutines.CoroutineContext, g: Guards = Guards(), store: InMemoryRelaunchStore = InMemoryRelaunchStore(), initial: LinkState = LinkState.OFFLINE, reopen: (GlassesRelauncher) -> Unit = {}): Pair<GlassesRelauncher, MutableStateFlow<LinkState>> {
        val state = MutableStateFlow(initial); lateinit var r: GlassesRelauncher
        r = GlassesRelauncher(backgroundScope, state, { true }, g, { reopen(r) }, { currentTime }, {}, store = store, io = io)
        return r to state
    }

    /** Review hub-wp2 r4 P1-1: an authorization that completes during a call (and its 15 s settle) is not allowed to open; once the call is over it is. */
    @Test fun anAuthorizationCompletingDuringACallMayNotOpenTheSession() = runTest {
        val g = Guards(); val (r, _) = rig(kotlinx.coroutines.Dispatchers.Unconfined, g); r.start(); runCurrent()
        g.call = false
        assertEquals("a call started while the authorization was out", false, r.autoOpenStillAllowed())
        g.call = true
        assertEquals(true, r.autoOpenStillAllowed())
        val off = GlassesRelauncher(backgroundScope, MutableStateFlow(LinkState.OFFLINE), { false }, Guards(), {}, { currentTime }, {})
        assertEquals("the setting was switched off meanwhile", false, off.autoOpenStillAllowed())
    }

    /** Review hub-wp2 r4 P1-1 + P2-3: the handed-back open (a call, or an authorization timeout) reaches the relauncher as a failed open, and its guarded retry follows the 5 min back-off. */
    @Test fun aHandedBackOpenIsRetriedByTheRelauncherAfterFiveMinutes() = runTest {
        var launches = 0
        val (r, _) = rig(kotlinx.coroutines.Dispatchers.Unconfined) { launches++ }
        r.start(); runCurrent(); r.connectGuarded(); runCurrent()
        assertEquals(1, launches)
        r.autoOpenFailed() // the authorization timed out (or a call blocked its continuation): the link says so
        advanceTimeBy(299_000); runCurrent(); assertEquals("not before the back-off", 1, launches)
        advanceTimeBy(2_000); runCurrent(); assertEquals("the retry owner tries again", 2, launches)
        advanceTimeBy(3_600_000L); runCurrent() // nothing silently stranded: a further failure keeps the ladder going
    }

    /** Review hub-wp2 r4 P2-4: OFFLINE-CONNECTING-OFFLINE inside one launch (a synchronous ConnectFailed) is conflated by the StateFlow; the explicit result still arms the retry. */
    @Test fun aSynchronousConnectFailureStillGetsItsRetryAfterFiveMinutes() = runTest {
        var launches = 0
        val (r, state) = rig(kotlinx.coroutines.Dispatchers.Unconfined) { me -> launches++; me.autoOpenFailed() }
        r.start(); runCurrent(); r.connectGuarded(); runCurrent()
        assertEquals(1, launches); assertEquals(LinkState.OFFLINE, state.value)
        advanceTimeBy(300_100); runCurrent()
        assertEquals("one attempt and a retry 5 min later", 2, launches)
        r.stop()
    }

    /** Review hub-wp2 r4 P2-4: a result after the hub stopped schedules nothing. */
    @Test fun aFailureReportedAfterStopSchedulesNoRetry() = runTest {
        var launches = 0
        val (r, _) = rig(kotlinx.coroutines.Dispatchers.Unconfined) { launches++ }
        r.start(); runCurrent(); r.connectGuarded(); runCurrent(); r.stop()
        r.autoOpenFailed(); advanceTimeBy(3_600_000L); runCurrent()
        assertEquals(1, launches)
    }

    /** Review hub-wp2 r4 P2-2: a replaced attempt's cancellation rollback must not erase the replacement's reservation (six attempts leave six records, never a seventh on a rebuilt relauncher). */
    @Test fun twoAttemptsWithOverlappingCancelKeepEveryReservation() = runTest {
        val io = HeldIo(); val store = InMemoryRelaunchStore(); var launches = 0
        val (r, _) = rig(io, store = store) { launches++ }
        r.start(); runCurrent()
        repeat(6) {
            r.connectGuarded(); runCurrent() // the first attempt reserves and its write is held
            r.connectGuarded(); runCurrent() // replaces it: the old one is cancelled with its write still in flight
            pump(io)
            assertEquals("every launch is on disk (iteration $it)", launches, store.attempts().size)
        }
        assertEquals("six actual attempts", 6, launches)
        assertEquals("six persisted", 6, store.attempts().size)
        val rebuilt = GlassesRelauncher(backgroundScope, MutableStateFlow(LinkState.OFFLINE), { true }, Guards(), { launches++ }, { currentTime }, {}, store = store, io = kotlinx.coroutines.Dispatchers.Unconfined)
        rebuilt.start(); runCurrent(); rebuilt.connectGuarded(); runCurrent()
        assertEquals("a rebuilt relauncher sees six in the hour: no seventh", 6, launches)
    }
}
