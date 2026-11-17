package io.github.pca.nexusport

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Codex v2 r1 P1-1, P1-2 and P1-4: delivery is one atomic step against the fences, pause gates answers and speech at final admission, STT is never opened without authorization. */
class FencedDeliveryTest {
    /** The ZoneFence model: a ticket is valid until the fence closes; act runs under the fence's lock, hold takes the same lock. */
    private class Fence {
        private val lock = Any()
        private var epoch = 0L
        var held = false
        fun hold() { synchronized(lock) { held = true; epoch++ } }
        fun release() { synchronized(lock) { held = false } }
        fun pass(): NexusPass { val e = synchronized(lock) { epoch.also { } }; val startedHeld = synchronized(lock) { held }
            return object : NexusPass {
                override fun <T : Any> act(block: () -> T): T? = synchronized(lock) { if (!held && !startedHeld && epoch == e) block() else null }
            } }
    }

    private val t = FakeNexusTransport()
    private val g = FakeGates()
    private val fence = Fence().also { f -> g.passOverride = { f.pass() } }
    private var cfg = ON_TTS.copy(sttEnabled = true, assistTakeover = true)
    private val asks = FakeAsks()
    private fun sink() = NexusHudSink(t, g, { cfg }, FakeActions(), { 0L }, {}, asks)
    private fun dropped(o: Outcome, r: DropReason) = assertEquals(r, assertIs<Outcome.Dropped>(o).reason)

    // ---- P1-1 ----------------------------------------------------------------------------------------------------

    @Test fun aZoneThatHoldsWhileTheSourcesAreBeingReadNeverReachesTheTransport() = runBlocking<Unit> {
        g.sourcesGate = { fence.hold() }                       // the zone holds after the first check, before the final admission
        dropped(sink().show(answer(speak = true)), DropReason.ZONE_HELD)
        assertTrue(t.calls.isEmpty(), "no notice, no speech: ${t.calls}")
    }

    @Test fun aHoldAndReleaseInTheGapStillClosesTheTicketEvenWhenTheFlagsReadOpen() = runBlocking<Unit> {
        g.sourcesGate = { fence.hold(); fence.release() }      // the fence flags read "open" again, but the zone was there: the trip wins
        dropped(sink().show(nudge()), DropReason.ZONE_HELD)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun aHoldThatLandsDuringTheTransportCallWaitsForItAndTheTripWithdrawsIt() = runBlocking<Unit> {
        val inShow = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = object : NexusTransport by t {
            override fun show(cmd: NexusCommand): Int { inShow.countDown(); release.await(5, TimeUnit.SECONDS); return t.show(cmd) }
        }
        val s = NexusHudSink(slow, g, { cfg }, FakeActions(), { 0L }, {}, asks)
        val shown = thread { runBlocking { s.show(answer(speak = true)) } }
        assertTrue(inShow.await(5, TimeUnit.SECONDS))
        val holder = thread { fence.hold(); s.trip(TripReason.ZONE_HOLD) }
        Thread.sleep(150)
        assertTrue(holder.isAlive, "the hold waits for the dispatch that already passed the check (check-then-act)")
        release.countDown(); shown.join(5_000); holder.join(5_000)
        assertEquals(0, s.liveHandles(), "the trip hid whatever the dispatch drew")
        assertFalse(Slot.NOTICE in t.live || Slot.SPEECH in t.live)
    }

    @Test fun aClosedFenceDropsTheSynchronousNoticesToo() {
        val s = sink()
        s.events.onListening(true)
        fence.hold()
        s.events.onAskFailed(AskFail.NO_SPEECH)
        assertEquals(0, t.count("show:"), "the ask notice is fenced like everything else")
    }

    @Test fun aThrowingPassFailsClosed() = runBlocking<Unit> {
        g.passOverride = { throw IllegalStateException("fence") }
        dropped(sink().show(nudge()), DropReason.GATE_ERROR)
        assertTrue(t.calls.isEmpty())
    }

    // ---- P1-4 ----------------------------------------------------------------------------------------------------

    @Test fun pauseDropsAnAnswerAndItsSpeechButNotTheContentFreePin() = runBlocking<Unit> {
        g.paused = true
        dropped(sink().show(answer(speak = true)), DropReason.PAUSED)
        assertTrue(t.calls.isEmpty(), "no notice and no TTS while paused")
        assertEquals(Outcome.Shown(false), sink().show(HudEvent.CaptureState(HudEvent.CaptureState.State.PAUSED)))
    }

    @Test fun pauseFlippingDuringTheSourceLookupIsCaughtAtFinalAdmission() = runBlocking<Unit> {
        g.sourcesGate = { g.paused = true }
        dropped(sink().show(answer(speak = true)), DropReason.PAUSED)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun pauseFlippingBetweenTheHandlersCheckAndTheSinkDeliveryDropsTheAnswer() = runBlocking<Unit> {
        // the assist handler checked "not paused", then looked up sources (suspension); pause came in the gap
        val s = sink(); s.events.onListening(true); s.events.onAskTranscript("q")
        val id = asks.asked.single().first
        g.paused = true
        dropped(s.show(HudEvent.AskAnswer(id, "a", emptyList(), setOf("m1"), 10_000, speak = true)), DropReason.PAUSED)
        assertEquals(0, t.count("show:") + t.count("speak"))
    }

    @Test fun aPauseTripInTheGapWinsOverTheAnswer() = runBlocking<Unit> {
        val s = sink()
        g.sourcesGate = { s.trip(TripReason.PAUSE) }
        dropped(s.show(answer(speak = true)), DropReason.TRIPPED)
        assertEquals(0, t.count("show:") + t.count("speak"))
    }

    @Test fun anAnswerThatIsLiveWhenPauseComesIsWithdrawnByTheNextTick() = runBlocking<Unit> {
        val s = sink()
        assertEquals(Outcome.Shown(true), s.show(answer(speak = true)))
        g.paused = true
        s.tick()
        assertEquals(0, s.liveHandles(), "the notice and its speech go when pause comes, even without a trip")
    }

    // ---- P1-2 ----------------------------------------------------------------------------------------------------

    @Test fun listenIsAuthorizedOnlyWhenEveryGateIsOpen() {
        val s = sink()
        assertTrue(s.events.authorizeListen())
        g.paused = true; assertFalse(s.events.authorizeListen()); g.paused = false
        g.worn = false; assertFalse(s.events.authorizeListen()); g.worn = true
        g.zone = true; assertFalse(s.events.authorizeListen()); g.zone = false
        g.ask = true; assertFalse(s.events.authorizeListen()); g.ask = false
        fence.hold(); assertFalse(s.events.authorizeListen()); fence.release()
        assertTrue(s.events.authorizeListen())
        cfg = ON_TTS; assertFalse(s.events.authorizeListen(), "the ask switches are off")
        cfg = NexusConfig(); assertFalse(s.events.authorizeListen())
        cfg = ON_ASK
        for (gate in listOf("zone", "ask", "paused", "worn")) { g.throwOn = gate; assertFalse(s.events.authorizeListen(), "a throwing $gate gate refuses"); g.throwOn = null }
        g.passOverride = { throw IllegalStateException() }; assertFalse(s.events.authorizeListen())
    }

    @Test fun aTripClosesASessionThatWasAuthorizedButHasNoHandleYet() {
        val s = sink()
        assertTrue(s.events.authorizeListen())
        t.calls.clear()
        s.trip(TripReason.ZONE_HOLD)
        assertTrue("stopListen" in t.calls, "the opening session is stopped by the trip")
    }

    @Test fun theSkillHostIsRegisteredForTheAskAloneToo() {
        sink().onBridgeConnected()
        assertTrue(t.skillHost, "the host answers authorizeListen")
        val none = NexusHudSink(t, g, { cfg }, FakeActions(), { 0L }, {}, null, null); none.onBridgeConnected()
        assertFalse(t.skillHost)
    }
}
