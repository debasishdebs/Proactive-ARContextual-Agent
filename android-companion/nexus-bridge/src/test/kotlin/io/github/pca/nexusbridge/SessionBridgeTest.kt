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

    /** A phone connection: the callback registration (token) and its session adoption. */
    private fun BridgeRenderer.connect(s: Long): Long { val t = resetEpoch(); assertEquals(NexusResult.OK, beginSession(s, t)); return t }

    private fun BridgeRenderer.show(s: Long, e: Long) = showIn(s, e, N, "T", "b", 5_000, false, emptyList(), emptyList())

    @Test fun oldSessionSendResumingAfterReconnectAndTripIsHiddenAndReportsCreatedHistory() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        r.connect(S1)
        r.tripIn(S1, 7)
        val inside = CountDownLatch(1); val go = CountDownLatch(1)
        bus.inShow = { inside.countDown(); go.await(5, TimeUnit.SECONDS) }
        var res = -1
        val t = thread { res = r.show(S1, 8) }                       // epoch 8: passes both checks on entry
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        bus.inShow = {}
        r.connect(S2)                          // reconnect: the counter restarts at 0
        r.tripIn(S2, 2)                                             // a new epoch-2 zone trip: 8 >= 2 would pass an epoch-only check
        go.countDown(); t.join(5_000)
        assertEquals(NexusResult.STALE, NexusResult.code(res))
        assertTrue(NexusResult.created(res), "the hub may have kept it: the phone re-owes the wipe")
        assertNull(bus.notice, "hidden when it returned")
    }

    @Test fun anOldSessionCannotEnterOrReplaceTheCurrentConnection() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        val tok2 = r.connect(S2)
        assertEquals(NexusResult.STALE, r.show(S1, 99))
        assertNull(bus.notice)
        assertEquals(NexusResult.STALE, r.beginSession(S1, 12345L), "a late call of an old connection cannot hijack")
        assertEquals(NexusResult.STALE, r.tripIn(S1, 50))
        assertEquals(NexusResult.OK, r.beginSession(S2, tok2), "idempotent for the current session")
        assertEquals(NexusResult.OK, NexusResult.code(r.show(S2, 1)))
    }

    @Test fun beginSessionForTheSameSessionKeepsTheEpochCounter() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        val t = r.connect(S1); r.tripIn(S1, 5); r.beginSession(S1, t)
        assertEquals(NexusResult.STALE, r.show(S1, 4))
        assertEquals(NexusResult.INVALID, r.beginSession(0, 5L))
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
        r.connect(S1); r.tripIn(S1, 5)
        assertEquals(NexusResult.STALE, r.show(S1, 4), "refused before anything was drawn: confirmed nothing")
        assertEquals(NexusResult.INVALID, r.showIn(S1, 6, 99, "T", "b", 5_000, false, emptyList(), emptyList()))
        val ok = r.show(S1, 6)
        assertEquals(NexusResult.OK, NexusResult.code(ok)); assertTrue(NexusResult.created(ok))
        assertNotEquals(0, ok and NexusResult.CREATED_HISTORY)
    }

    @Test fun aTripWhileTheHubCallIsStuckHidesTheNoticeOnlyAfterItReturns() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        r.connect(S1)
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

    /** Codex v2 r5 P1: new setCallback -> a delayed OLD beginSession(S1) -> the new connection's beginSession(S2) and tripIn(S2) must still work. */
    @Test fun aDelayedBeginSessionOfAnOldConnectionCannotClaimTheFreshOne() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        val tokOld = r.resetEpoch(); assertEquals(NexusResult.OK, r.beginSession(S1, tokOld))
        val tokNew = r.resetEpoch()                                   // the phone reconnects (new setCallback)
        assertEquals(NexusResult.STALE, r.beginSession(S1, tokOld), "the old connection's token is not the current one")
        assertEquals(NexusResult.STALE, r.show(S1, 1), "and S1 never became the session")
        assertEquals(NexusResult.OK, r.beginSession(S2, tokNew))
        assertEquals(NexusResult.OK, r.tripIn(S2, 3))
        assertEquals(NexusResult.OK, NexusResult.code(r.show(S2, 4)))
        assertEquals(NexusResult.STALE, r.tripIn(S1, 9))
        assertEquals(NexusResult.STALE, r.beginSession(S1, tokOld))
        assertEquals(NexusResult.INVALID, r.beginSession(S2, 0L))
    }

    @Test fun aSessionNeverAdoptedByTheCurrentTokenIsRefusedEverywhere() {
        val bus = Bus(); val r = BridgeRenderer({ bus }, {})
        val tok = r.resetEpoch()
        assertEquals(NexusResult.STALE, r.show(S1, 1))
        assertEquals(NexusResult.STALE, r.tripIn(S1, 1))
        assertEquals(NexusResult.STALE, r.forgetHistoryIn(S1, 1))
        assertEquals(NexusResult.OK, r.beginSession(S1, tok))
        assertEquals(NexusResult.STALE, r.beginSession(S2, tok), "a claimed connection is not re-claimed with another session")
        assertEquals(NexusResult.OK, r.beginSession(S1, tok))
    }

    /** Randomized: connections are registered, their (late, reordered) beginSession / trip / show calls interleave in any order; only the newest connection may ever be accepted, and an old one never disturbs it. */
    @Test fun randomizedInterleavingsNeverLetAnOldConnectionTouchTheNewOne() {
        val rng = java.util.Random(20261118)
        repeat(400) {
            val bus = Bus(); val r = BridgeRenderer({ bus }, {})
            class Cn(val tok: Long, val session: Long)
            val conns = ArrayList<Cn>()
            val ops = ArrayList<() -> Unit>()
            val n = 2 + rng.nextInt(3)
            for (i in 0 until n) { val sess = 1000L + it * 10 + i; conns += Cn(0, sess) }
            val toks = LongArray(n)
            // registrations happen in order; every other call is queued and shuffled
            val log = ArrayList<String>()
            var newest = -1
            val pending = ArrayList<Pair<Int, Int>>()          // (connection, op: 0 begin, 1 trip, 2 show)
            for (i in 0 until n) for (op in 0..2) pending += i to op
            pending.shuffle(rng)
            var next = 0
            val claimedBy = HashMap<Int, Boolean>()
            fun register() { toks[next] = r.resetEpoch(); newest = next; next++ }
            register()
            for ((ci, op) in pending) {
                while (next < n && rng.nextInt(3) == 0) register()
                if (ci >= next) { continue }                    // the phone has not connected yet: it has no token to call with
                val sess = conns[ci].session
                when (op) {
                    0 -> {
                        val res = r.beginSession(sess, toks[ci])
                        if (ci == newest && claimedBy[ci] != true) { assertEquals(NexusResult.OK, res); claimedBy[ci] = true } else if (ci != newest) assertEquals(NexusResult.STALE, res)
                    }
                    1 -> { val res = r.tripIn(sess, 5); if (ci != newest || claimedBy[ci] != true) assertEquals(NexusResult.STALE, res) else assertEquals(NexusResult.OK, res) }
                    else -> { val res = NexusResult.code(r.show(sess, 1)); if (ci != newest || claimedBy[ci] != true) assertEquals(NexusResult.STALE, res) }
                }
            }
            // an old registration never stole the newest connection: once it has registered it can still claim and trip
            while (next < n) register()
            assertEquals(NexusResult.OK, r.beginSession(conns[newest].session, toks[newest]))
            assertEquals(NexusResult.OK, r.tripIn(conns[newest].session, 3))
            for (i in 0 until newest) { assertEquals(NexusResult.STALE, r.beginSession(conns[i].session, toks[i])); assertEquals(NexusResult.STALE, r.tripIn(conns[i].session, 9)) }
        }
    }
}
