package io.github.pca.nexusbridge

import io.github.pca.nexusport.NexusAction
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.Slot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Codex v2 r4 P1-3 (a connection session; a reconnect makes in-flight sends stale) and the bridge half of P1-1 (re-check right after the hub call returns) and P1-2 (the created-history flag). */
class SessionBridgeTest {
    private class Bus : NexusBusClient {
        @Volatile var notice: String? = null
        @Volatile var inShow: () -> Unit = {}
        override fun showNotice(title: String, body: String, ttlMs: Long, wake: Boolean, actions: List<NexusAction>): Int { inShow(); notice = body; return NexusResult.OK }
        override fun hideNotice(): Int { notice = null; return NexusResult.OK }
        override fun showPin(title: String, lines: List<String>, ttlMs: Long) = NexusResult.OK
        override fun hidePin() = NexusResult.OK
        override fun speak(text: String) = NexusResult.OK
        override fun stopSpeech() = NexusResult.OK
        override fun forgetNotices(onAccepted: () -> Unit) = NexusResult.OK
        override fun startListening(cb: ListenCallbacks) = NexusResult.OK
        override fun stopListening() = NexusResult.OK
    }

    private val N = Slot.NOTICE.code
    private val S1 = 111L; private val S2 = 222L

    private fun BridgeRenderer.show(s: Long, e: Long) = showIn(s, e, N, "T", "b", 5_000, false, emptyList(), emptyList())

    @Test fun oldSessionSendResumingAfterReconnectAndTripIsHiddenAndReportsCreatedHistory() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        r.resetEpoch(); r.beginSession(S1)
        r.tripIn(S1, 7)
        val inside = CountDownLatch(1); val go = CountDownLatch(1)
        bus.inShow = { inside.countDown(); go.await(5, TimeUnit.SECONDS) }
        var res = -1
        val t = thread { res = r.show(S1, 8) }                       // epoch 8: passes both checks on entry
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        bus.inShow = {}
        r.resetEpoch(); r.beginSession(S2)                          // reconnect: the counter restarts at 0
        r.tripIn(S2, 2)                                             // a new epoch-2 zone trip: 8 >= 2 would pass an epoch-only check
        go.countDown(); t.join(5_000)
        assertEquals(NexusResult.STALE, NexusResult.code(res))
        assertTrue(NexusResult.created(res), "the hub may have kept it: the phone re-owes the wipe")
        assertNull(bus.notice, "hidden when it returned")
    }

    @Test fun anOldSessionCannotEnterOrReplaceTheCurrentConnection() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        r.resetEpoch(); r.beginSession(S2)
        assertEquals(NexusResult.STALE, r.show(S1, 99))
        assertNull(bus.notice)
        assertEquals(NexusResult.STALE, r.beginSession(S1), "a late call of an old connection cannot hijack")
        assertEquals(NexusResult.STALE, r.tripIn(S1, 50))
        assertEquals(NexusResult.OK, r.beginSession(S2), "idempotent for the current session")
        assertEquals(NexusResult.OK, NexusResult.code(r.show(S2, 1)))
    }

    @Test fun beginSessionForTheSameSessionKeepsTheEpochCounter() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        r.resetEpoch(); r.beginSession(S1); r.tripIn(S1, 5); r.beginSession(S1)
        assertEquals(NexusResult.STALE, r.show(S1, 4))
        assertEquals(NexusResult.INVALID, r.beginSession(0))
    }

    @Test fun aLegacySendInFlightAcrossAReconnectIsAlsoStale() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        val inside = CountDownLatch(1); val go = CountDownLatch(1)
        bus.inShow = { inside.countDown(); go.await(5, TimeUnit.SECONDS) }
        var res = -1
        val t = thread { res = r.showAt(8, N, "T", "b", 5_000, false, emptyList(), emptyList()) }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        bus.inShow = {}
        r.resetEpoch()
        go.countDown(); t.join(5_000)
        assertEquals(NexusResult.STALE, res)
        assertNull(bus.notice)
    }

    @Test fun theResultFlagsCreatedHistoryOnlyWhenSomethingMayHaveBeenDrawn() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        r.resetEpoch(); r.beginSession(S1); r.tripIn(S1, 5)
        assertEquals(NexusResult.STALE, r.show(S1, 4), "refused before anything was drawn: confirmed nothing")
        assertEquals(NexusResult.INVALID, r.showIn(S1, 6, 99, "T", "b", 5_000, false, emptyList(), emptyList()))
        val ok = r.show(S1, 6)
        assertEquals(NexusResult.OK, NexusResult.code(ok)); assertTrue(NexusResult.created(ok))
        assertNotEquals(0, ok and NexusResult.CREATED_HISTORY)
    }

    @Test fun aTripWhileTheHubCallIsStuckHidesTheNoticeOnlyAfterItReturns() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        r.resetEpoch(); r.beginSession(S1)
        val inside = CountDownLatch(1); val go = CountDownLatch(1)
        bus.inShow = { inside.countDown(); go.await(5, TimeUnit.SECONDS) }
        var res = -1
        val t = thread { res = r.show(S1, 1) }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        r.tripIn(S1, 2)
        assertNull(bus.notice, "not drawn yet: the hub call has not returned, and nothing here can interrupt it")
        bus.inShow = {}
        go.countDown(); t.join(5_000)
        assertEquals(NexusResult.STALE, NexusResult.code(res))
        assertNull(bus.notice)
    }
}
