package com.rokid.context.link

import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.TraceChunk
import com.rokid.context.core.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Slice 3a wire plumbing on the phone side (S74): new inbound channels, ack correlation, the old-peer fixture. */
class Slice3aWireTest {
    private val inbound = GlassesInbound()

    // A frozen capture of what an old glasses build sends in a session: hello, motion hint, frame meta. No new channel.
    private val oldPeerSession = listOf(
        Channels.HELLO to listOf("""{"v":1,"glassesBatteryPct":71,"bufferSize":0,"glassesNowMs":1700000000000,"pauses":[],"resumedAtMs":{},"rokidCloses":0,"iconBlocked":false}"""),
        Channels.MOTION_HINT to listOf("""{"v":1,"state":"STILL","atMs":1700000001000,"stillFrac5m":0.5}"""),
    )

    @Test fun stampedReceiverAcksMatchAndTheCapsAckStaysCapsOnly() {
        // The shapes the glasses PlanController now sends (fix round 1): every plan ack echoes install and session; the announcement is gen 0.
        val gate = PlanAckGate(install = { "p1" }, sessionStart = { 777 })
        for (st in PlanAckState.entries) {
            val a = CapturePlanAck(gen = 3, state = st, reason = if (st == PlanAckState.REVERTED) "auto:trial_end" else null, caps = listOf("plan1"), phoneInstall = "p1", sessionStartMs = 777)
            assertEquals(AckVerdict.MATCH, gate.classify(a), "$st")
        }
        val caps = CapturePlanAck(gen = 0, caps = listOf("plan1", "shadow", "trial"))   // sent after a plan was held and reverted: still the plain announcement
        assertEquals(AckVerdict.CAPS, gate.classify(caps))
        val tracker = PlanPeerTracker(graceMs = 10_000)
        tracker.onSessionOpen(0); tracker.onHello(0); tracker.onCapsAck()
        assertEquals(PlanPeer.CAPABLE, tracker.state(60_000))
        val old = CapturePlanAck(gen = 3, state = PlanAckState.EFFECTIVE, phoneInstall = "p1")   // an unstamped ack is the bug this fixes
        assertEquals(AckVerdict.STALE, gate.classify(old))
    }

    @Test fun markIsDecodedWhenOlderThanSessionStart() {
        val m = MarkMessage(epoch = "e", seq = 3, atMs = 1_000, atElapsedMs = 500, sessionStartMs = 9_000)
        assertEquals(listOf<GlassesEvent>(GlassesEvent.MarkReceived(m)), inbound.onMessage(Channels.MARK, listOf(Wire.encode(m))))
    }

    @Test fun queuedMarkOlderThanSessionAccepted() {
        val gate = PlanAckGate(install = { "i" }, sessionStart = { 50_000 })
        val m = MarkMessage(epoch = "e", seq = 1, atMs = 10, atElapsedMs = 5, sessionStartMs = 40_000)
        val ev = inbound.onMessage(Channels.MARK, listOf(Wire.encode(m))).single()
        assertEquals(GlassesEvent.MarkReceived(m), ev)
        assertEquals(0, gate.ackStale, "marks never go through the ack gate")
    }

    @Test fun planAckAndTraceChunkAreDecoded() {
        val a = CapturePlanAck(gen = 2, state = PlanAckState.EFFECTIVE, phoneInstall = "i", sessionStartMs = 5)
        assertEquals(listOf<GlassesEvent>(GlassesEvent.PlanAckReceived(a)), inbound.onMessage(Channels.CAPTURE_PLAN_ACK, listOf(Wire.encode(a))))
        val c = TraceChunk(traceId = "trace-0001", seq = 0, count = 1)
        assertEquals(listOf<GlassesEvent>(GlassesEvent.TraceChunkReceived(c)), inbound.onMessage(Channels.TRACE_CHUNK, listOf(Wire.encode(c))))
    }

    @Test fun otherVersionOnNewChannelIsOutdatedNotApplied() {
        val bad = Wire.encode(CapturePlanAck(gen = 1)).replace("\"v\":1", "\"v\":2")
        assertEquals(listOf<GlassesEvent>(GlassesEvent.Outdated(2)), inbound.onMessage(Channels.CAPTURE_PLAN_ACK, listOf(bad)))
    }

    @Test fun ackFromOtherInstallIgnored() {
        val gate = PlanAckGate(install = { "mine" }, sessionStart = { 7_000 })
        val v = gate.classify(CapturePlanAck(gen = 4, state = PlanAckState.EFFECTIVE, phoneInstall = "theirs", sessionStartMs = 7_000))
        assertEquals(AckVerdict.STALE, v)
        assertEquals(1, gate.ackStale)
    }

    @Test fun ackFromOldSessionIgnored() {
        val gate = PlanAckGate(install = { "mine" }, sessionStart = { 7_000 })
        assertEquals(AckVerdict.STALE, gate.classify(CapturePlanAck(gen = 4, state = PlanAckState.EFFECTIVE, phoneInstall = "mine", sessionStartMs = 6_000)))
        assertEquals(AckVerdict.STALE, gate.classify(CapturePlanAck(gen = 4, state = PlanAckState.EFFECTIVE, phoneInstall = "mine", sessionStartMs = 0)))
        assertEquals(2, gate.ackStale)
        assertEquals(AckVerdict.MATCH, gate.classify(CapturePlanAck(gen = 4, state = PlanAckState.EFFECTIVE, phoneInstall = "mine", sessionStartMs = 7_000)))
        assertEquals(2, gate.ackStale)
    }

    @Test fun unsolicitedCapsAckIsCapsNotStale() {
        val gate = PlanAckGate(install = { "mine" }, sessionStart = { 7_000 })
        assertEquals(AckVerdict.CAPS, gate.classify(CapturePlanAck(gen = 0, caps = listOf("plan1"))))
        assertEquals(0, gate.ackStale)
        // gen 0 that does carry an install or session is an ordinary ack and is matched like one.
        assertEquals(AckVerdict.STALE, gate.classify(CapturePlanAck(gen = 0, phoneInstall = "x")))
    }

    @Test fun oldGlassesNeverAcksPhoneShowsTooOld() {
        val tracker = PlanPeerTracker(graceMs = 10_000)
        tracker.onSessionOpen(0)
        assertEquals(PlanPeer.UNKNOWN, tracker.state(1_000))
        oldPeerSession.forEach { (ch, parts) ->
            inbound.onMessage(ch, parts).forEach { e ->
                if (e is GlassesEvent.HelloReceived) tracker.onHello(2_000)
                assertTrue(e !is GlassesEvent.PlanAckReceived)
            }
        }
        assertEquals(PlanPeer.UNKNOWN, tracker.state(5_000), "inside the grace window")
        assertEquals(PlanPeer.TOO_OLD, tracker.state(12_001))
        // A new session starts over, and a caps ack makes the peer capable.
        tracker.onSessionOpen(20_000)
        tracker.onHello(20_100)
        tracker.onCapsAck()
        assertEquals(PlanPeer.CAPABLE, tracker.state(40_000))
    }

    @Test fun oldHelloFixtureStillDecodes() {
        val ev = inbound.onMessage(Channels.HELLO, oldPeerSession[0].second).single()
        assertEquals(71, (ev as GlassesEvent.HelloReceived).hello.glassesBatteryPct)
        assertEquals(Hello::class, ev.hello::class)
    }
}
