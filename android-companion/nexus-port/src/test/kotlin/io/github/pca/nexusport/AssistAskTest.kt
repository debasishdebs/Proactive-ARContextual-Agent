package io.github.pca.nexusport

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T5: the assist-button ask, driven with FAKE speech callbacks (the events the bridge sends) and a fake voice backend. */
class AssistAskTest {
    private val t = FakeNexusTransport()
    private val g = FakeGates()
    private val asks = FakeAsks()
    private var now = 0L
    private var cfg = ON_ASK.copy(ttsEnabled = true)
    private val logs = mutableListOf<String>()
    private fun sink() = NexusHudSink(t, g, { cfg }, FakeActions(), { now }, { logs += it }, asks)
    private val SECRET = "where did I park the red car near Charminar"

    @Test fun offByDefaultNothingListensAndNothingIsAsked() {
        val s = NexusHudSink(t, g, { NexusConfig() }, FakeActions(), { now }, { logs += it }, asks)
        s.events.onListening(true)
        s.events.onAskTranscript(SECRET)
        assertTrue(asks.asked.isEmpty()); assertEquals(0, s.liveHandles())
        assertEquals(NexusFeatures.NONE, NexusConfig().featureMask())
        assertEquals(NexusFeatures.NONE, NexusConfig(enabled = true, sttEnabled = true).featureMask() and NexusFeatures.ASSIST)   // the takeover alone is not enough
        assertEquals(NexusFeatures.STT or NexusFeatures.ASSIST, ON_ASK.featureMask())
        assertTrue(t.calls.contains("stopListen"), "the bridge was told to stop listening")
    }

    @Test fun aFinalTranscriptIsOneAskAndTheAnswerComesBackThroughTheSameSink() = runBlocking<Unit> {
        val s = sink()
        s.events.onListening(true)
        assertEquals(setOf(Slot.LISTEN), s.liveSlots())
        s.events.onAskTranscript(SECRET)
        assertEquals(1, asks.asked.size); assertEquals(SECRET, asks.asked.single().second)
        assertEquals(0, s.liveHandles())                                    // the session is over
        s.events.onAskTranscript("again")                                   // a second final of the same ask is ignored
        assertEquals(1, asks.asked.size)
        val id = asks.asked.single().first
        val o = s.show(HudEvent.AskAnswer(id, "Behind the fort", listOf("Mon"), setOf("m1"), 10_000, speak = true))
        assertEquals(Outcome.Shown(spoken = true), o)                       // TTS only because the TTS switch is on
        assertEquals(setOf(Slot.NOTICE, Slot.SPEECH), s.liveSlots())
    }

    @Test fun theAnswerIsNotSpokenWhenTheTtsSwitchIsOff() = runBlocking<Unit> {
        cfg = ON_ASK
        val s = sink()
        s.events.onListening(true); s.events.onAskTranscript("q")
        val o = s.show(HudEvent.AskAnswer(asks.asked.single().first, "a", emptyList(), setOf("m1"), 10_000, speak = true))
        assertEquals(Outcome.Shown(spoken = false), o)
        assertTrue(t.spoken.isEmpty())
    }

    @Test fun deniedBusyNoSpeechAndOtherGiveAShortFixedNotice() {
        for ((code, line) in listOf(AskFail.DENIED_BUSY to "Microphone busy", AskFail.NO_SPEECH to "Didn't catch", AskFail.OTHER to "unavailable")) {
            val tt = FakeNexusTransport(); val s = NexusHudSink(tt, g, { cfg }, FakeActions(), { now }, {}, asks)
            s.events.onListening(true)
            s.events.onAskFailed(code)
            assertEquals(1, tt.shown.size, "$code"); assertTrue(line in tt.shown.single().text, tt.shown.single().text)
            assertTrue(asks.asked.isEmpty()); assertEquals(setOf(Slot.NOTICE), s.liveSlots())
        }
    }

    @Test fun anEmptyTranscriptIsNoSpeechAndAsksNothing() {
        val s = sink()
        s.events.onListening(true); s.events.onAskTranscript("   ")
        assertTrue(asks.asked.isEmpty()); assertTrue("Didn't catch" in t.shown.single().text)
    }

    @Test fun aZoneHoldMidAskIsZonePausedNothingIsDeliveredAndTheListeningStops() = runBlocking<Unit> {
        // (a) while listening
        run {
            val tt = FakeNexusTransport(); val a = FakeAsks(); val s = NexusHudSink(tt, g, { cfg }, FakeActions(), { now }, {}, a)
            s.events.onListening(true)
            s.trip(TripReason.ZONE_HOLD)
            assertEquals(listOf(AskRefusal.ZONE_PAUSED), a.refused.map { it.second })
            assertTrue("stopListen" in tt.calls); assertTrue(tt.live.isEmpty()); assertEquals(0, s.liveHandles())
            s.events.onAskTranscript(SECRET)                                // a transcript that was already in flight
            assertTrue(a.asked.isEmpty()); assertTrue(tt.shown.isEmpty())
        }
        // (b) while the phone is answering
        for ((reason, want) in listOf(TripReason.ZONE_HOLD to AskRefusal.ZONE_PAUSED, TripReason.PAUSE to AskRefusal.PAUSED, TripReason.NOT_WORN to AskRefusal.NOT_WORN, TripReason.MODE_OFF to AskRefusal.DISABLED)) {
            val tt = FakeNexusTransport(); val a = FakeAsks(); val s = NexusHudSink(tt, g, { cfg }, FakeActions(), { now }, {}, a)
            s.events.onListening(true); s.events.onAskTranscript("q")
            val id = a.asked.single().first
            s.trip(reason)
            assertEquals(listOf(id to want), a.refused, reason.name)
            val o = s.show(HudEvent.AskAnswer(id, "late", emptyList(), setOf("m1"), 10_000, speak = true))
            assertEquals(Outcome.Dropped(DropReason.TRIPPED), o, reason.name)
            assertTrue(tt.shown.isEmpty() && tt.spoken.isEmpty(), "nothing delivered after ${reason.name}")
        }
    }

    @Test fun aShowThatWasInFlightWhenTheZoneTrippedDeliversNothing() = runBlocking<Unit> {
        val s = sink()
        s.events.onListening(true); s.events.onAskTranscript("q")
        val id = asks.asked.single().first
        g.sourcesGate = { s.trip(TripReason.ZONE_HOLD) }                     // the zone closes while the provenance gate is reading
        val o = s.show(HudEvent.AskAnswer(id, "a", emptyList(), setOf("m1"), 10_000, speak = true))
        assertEquals(Outcome.Dropped(DropReason.TRIPPED), o)
        assertTrue(t.shown.isEmpty() && t.spoken.isEmpty())
    }

    @Test fun aGateThatIsAlreadyClosedWhenTheTranscriptArrivesRefusesWithoutAskingThePhone() {
        fun case(f: (FakeGates) -> Unit, r: AskRefusal) = f to r
        for ((flip, want) in listOf(
            case({ it.zone = true }, AskRefusal.ZONE_PAUSED), case({ it.ask = true }, AskRefusal.ZONE_PAUSED), case({ it.worn = false }, AskRefusal.NOT_WORN), case({ it.throwOn = "zone" }, AskRefusal.ZONE_PAUSED),
        )) {
            val gg = FakeGates(); val a = FakeAsks(); val s = NexusHudSink(FakeNexusTransport(), gg, { cfg }, FakeActions(), { now }, {}, a)
            s.events.onListening(true); flip(gg)
            s.events.onAskTranscript("q")
            assertTrue(a.asked.isEmpty()); assertEquals(want, a.refused.single().second)
        }
    }

    @Test fun pressingTheButtonInAClosedZoneNeverOpensTheListening() {
        g.zone = true
        val s = sink()
        s.events.onListening(true)
        assertTrue("stopListen" in t.calls); assertEquals(0, s.liveHandles()); assertEquals(AskRefusal.ZONE_PAUSED, asks.refused.single().second)
    }

    @Test fun aSilentGateChangeCancelsThePendingAskOnTheNextTickAndThereIsNoRetry() = runBlocking<Unit> {
        val s = sink()
        s.events.onListening(true); s.events.onAskTranscript("q")
        val id = asks.asked.single().first
        g.zone = true
        s.tick()
        assertEquals(listOf(id to AskRefusal.ZONE_PAUSED), asks.refused)
        g.zone = false; s.tick(); s.tick()
        assertEquals(1, asks.asked.size, "no auto-retry"); assertEquals(1, asks.refused.size)
        assertEquals(Outcome.Dropped(DropReason.TRIPPED), s.show(HudEvent.AskAnswer(id, "a", emptyList(), setOf("m"), 5_000)))
    }

    @Test fun anAskThatIsNeverAnsweredExpires() = runBlocking<Unit> {
        val s = sink()
        s.events.onListening(true); s.events.onAskTranscript("q")
        now = NexusLimits.ASK_MAX_MS; s.tick()
        assertEquals(AskRefusal.CANCELLED, asks.refused.single().second)
    }

    @Test fun aNewAskSupersedesTheOldOne() = runBlocking<Unit> {
        val s = sink()
        s.events.onListening(true); s.events.onAskTranscript("one")
        s.events.onListening(true); s.events.onAskTranscript("two")
        assertEquals(2, asks.asked.size); assertEquals(listOf(asks.asked[0].first to AskRefusal.CANCELLED), asks.refused)
        assertEquals(Outcome.Dropped(DropReason.TRIPPED), s.show(HudEvent.AskAnswer(asks.asked[0].first, "old", emptyList(), setOf("m"), 5_000)))
        assertTrue(s.show(HudEvent.AskAnswer(asks.asked[1].first, "new", emptyList(), setOf("m"), 5_000)) is Outcome.Shown)
    }

    @Test fun switchingTheFeatureOffMidListeningStopsItAndCancels() {
        val s = sink()
        s.events.onListening(true)
        cfg = NexusConfig(enabled = true)
        s.refreshFeatures()
        assertTrue("stopListen" in t.calls); assertEquals(0, s.liveHandles()); assertEquals(AskRefusal.DISABLED, asks.refused.single().second)
        assertEquals(NexusFeatures.NONE, t.features)
    }

    @Test fun aHandlerThatThrowsDoesNotBreakTheSink() {
        val s = NexusHudSink(t, g, { cfg }, FakeActions(), { now }, {}, object : NexusAskHandler {
            override fun ask(askId: String, text: String) = throw IllegalStateException("backend")
            override fun refused(askId: String, why: AskRefusal) = throw IllegalStateException("backend")
        })
        s.events.onListening(true); s.events.onAskTranscript("q"); s.trip(TripReason.PAUSE)
        assertEquals(0, s.liveHandles())
    }

    @Test fun theTranscriptIsNeverLoggedAndTheLogsAreFixedWords() = runBlocking<Unit> {
        val s = sink()
        s.events.onListening(true); s.events.onAskTranscript(SECRET)
        val id = asks.asked.single().first
        s.show(HudEvent.AskAnswer(id, "Behind the fort", listOf("Mon"), setOf("m1"), 10_000, speak = true))
        s.events.onListening(true); s.events.onAskTranscript(SECRET); s.trip(TripReason.ZONE_HOLD)
        s.events.onListening(true); s.events.onAskFailed(AskFail.NO_SPEECH)
        assertTrue(logs.isNotEmpty())
        for (l in logs) { assertFalse("Charminar" in l || "red car" in l || "fort" in l || id in l, l) }
        assertTrue(logs.all { it.startsWith("nexus ") }, logs.toString())
    }

    @Test fun aBridgeReconnectForgetsTheAsk() {
        val s = sink()
        s.events.onListening(true)
        s.onBridgeConnected()
        assertEquals(0, s.liveHandles())
        s.events.onAskTranscript("q")
        assertTrue(asks.asked.isEmpty())
    }
}
