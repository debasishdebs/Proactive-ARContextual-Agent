package io.github.pca.nexusport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** T2 and T3: every gate drops the item, a gate error drops it too, the quiet give-ups, the opt-in defaults and the actions. */
class NexusHudSinkTest {
    private val t = FakeNexusTransport()
    private val g = FakeGates()
    private val actions = FakeActions()
    private var cfg = ON
    private var now = 1_000L
    private val logs = mutableListOf<String>()
    private fun sink() = NexusHudSink(t, g, { cfg }, actions, { now }, { logs += it })

    private fun dropped(o: Outcome, reason: DropReason) = assertEquals(reason, assertIs<Outcome.Dropped>(o).reason)

    @Test fun everythingIsOffByDefault() = runBlocking<Unit> {
        assertFalse(NexusConfig().enabled); assertFalse(NexusConfig().ttsEnabled); assertFalse(NexusConfig().sttEnabled); assertFalse(NexusConfig().assistTakeover)
        assertEquals(NexusFeatures.NONE, NexusConfig().featureMask())
        cfg = NexusConfig()
        dropped(sink().show(nudge()), DropReason.MODE_OFF)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun aLiveItemIsShownOnce() = runBlocking<Unit> {
        val s = sink()
        assertEquals(Outcome.Shown(false), s.show(nudge()))
        assertEquals(listOf("show:NOTICE"), t.calls)
        assertEquals(setOf(Slot.NOTICE), s.liveSlots())
    }

    @Test fun aForgottenCiteDropsTheItem() = runBlocking<Unit> {
        g.liveIds = setOf("other")
        dropped(sink().show(nudge(src = setOf("m1", "other"))), DropReason.PROVENANCE)
        dropped(sink().show(answer(src = setOf("gone"))), DropReason.PROVENANCE)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun aNudgeOrCardThatDeclaresNoSourceIsDropped() = runBlocking<Unit> {
        dropped(sink().show(nudge(src = emptySet())), DropReason.PROVENANCE)
        dropped(sink().show(HudEvent.PlaceCard("p", "t", "b", emptySet(), 5_000)), DropReason.PROVENANCE)
        assertEquals(0, g.provenanceCalls)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun aHeldFenceDropsEverythingEvenContentFreePins() = runBlocking<Unit> {
        g.zone = true
        dropped(sink().show(nudge()), DropReason.ZONE_HELD)
        dropped(sink().show(answer(speak = true)), DropReason.ZONE_HELD)
        dropped(sink().show(HudEvent.CaptureState(HudEvent.CaptureState.State.CAPTURING)), DropReason.ZONE_HELD)
        g.zone = false; g.ask = true
        dropped(sink().show(nudge()), DropReason.ZONE_HELD)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun aPauseDropsProactiveItemsOnlyAndAnAskedAnswerStillShows() = runBlocking<Unit> {
        g.paused = true
        val s = sink()
        dropped(s.show(nudge()), DropReason.PAUSED)
        dropped(s.show(HudEvent.PlaceCard("p", "t", "b", setOf("f"), 5_000)), DropReason.PAUSED)
        assertIs<Outcome.Shown>(s.show(answer()))
        assertIs<Outcome.Shown>(s.show(HudEvent.CaptureState(HudEvent.CaptureState.State.PAUSED)))
    }

    @Test fun notWornDropsEverythingIncludingSpeech() = runBlocking<Unit> {
        g.worn = false; cfg = ON_TTS
        dropped(sink().show(answer(speak = true)), DropReason.NOT_WORN)
        dropped(sink().show(HudEvent.CaptureState(HudEvent.CaptureState.State.CAPTURING)), DropReason.NOT_WORN)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun aGateErrorDropsTheItemFailClosed() = runBlocking<Unit> {
        for (gate in listOf("sources", "zone", "ask", "paused", "worn")) {
            val g2 = FakeGates().also { it.throwOn = gate }
            val t2 = FakeNexusTransport()
            val o = NexusHudSink(t2, g2, { ON }, actions).show(nudge())
            assertEquals(DropReason.GATE_ERROR, assertIs<Outcome.Dropped>(o).reason, gate)
            assertTrue(t2.calls.isEmpty(), gate)
        }
        // a throwing config is "off", not "on"
        dropped(NexusHudSink(t, g, { error("cfg") }, actions).show(nudge()), DropReason.MODE_OFF)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun aTripBetweenTheProvenanceReadAndTheCallWins() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        g.sourcesGate = { release.await() }
        val s = sink()
        val shown = async { s.show(nudge()) }
        yield()
        s.trip(TripReason.ZONE_HOLD)
        release.complete(Unit)
        dropped(shown.await(), DropReason.TRIPPED)
        assertEquals(0, t.count("show"))
        assertEquals(0, s.liveHandles())
    }

    @Test fun theGatesAreAskedAgainRightBeforeTheCall() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        g.sourcesGate = { release.await() }
        val s = sink()
        val shown = async { s.show(nudge()) }
        yield()
        g.zone = true                       // the fence closed while the provenance read was suspended; no trip was reported
        release.complete(Unit)
        dropped(shown.await(), DropReason.ZONE_HELD)
        assertEquals(0, t.count("show"))
    }

    @Test fun quietGiveUpsAreNotRetriedAndLeaveNoHandle() = runBlocking<Unit> {
        val s = sink()
        for (code in listOf(NexusResult.BUSY, NexusResult.NOT_AVAILABLE, NexusResult.NOT_REGISTERED, NexusResult.NOT_GRANTED, NexusResult.INVALID, NexusResult.DISABLED)) {
            t.calls.clear(); t.showResult = code
            val o = s.show(nudge())
            assertEquals(Outcome.Dropped(DropReason.TRANSPORT, code), o)
            assertEquals(1, t.count("show"), "one attempt only for ${NexusResult.name(code)}")
            assertEquals(0, s.liveHandles())
            s.tick()
            assertEquals(1, t.count("show"), "tick must not retry a show")
        }
    }

    @Test fun speechIsOffByDefaultAndOnlyWithTheUsersSwitch() = runBlocking<Unit> {
        val s = sink()
        assertEquals(Outcome.Shown(false), s.show(answer(speak = true)))
        assertEquals(0, t.count("speak"))
        cfg = ON_TTS
        assertEquals(Outcome.Shown(true), s.show(answer(speak = true)))
        assertEquals(listOf("The answer"), t.spoken)
        assertEquals(NexusFeatures.TTS, t.features)
        assertEquals(setOf(Slot.NOTICE, Slot.SPEECH), s.liveSlots())
        s.events.onSpeechDone()
        assertEquals(setOf(Slot.NOTICE), s.liveSlots())
    }

    @Test fun aRefusedSpeechStillLeavesTheNoticeAndNoSpeechHandle() = runBlocking<Unit> {
        cfg = ON_TTS; t.speakResult = NexusResult.DISABLED
        val s = sink()
        assertEquals(Outcome.Shown(false), s.show(answer(speak = true)))
        assertEquals(setOf(Slot.NOTICE), s.liveSlots())
    }

    @Test fun actionsRunOnceAndOnlyOnALiveHandle() = runBlocking<Unit> {
        val s = sink()
        s.show(nudge(ref = "n7"))
        s.events.onAction(Slot.NOTICE.code, "mark"); s.events.onAction(Slot.NOTICE.code, "mark")
        assertEquals(listOf("n7"), actions.marks)
        assertEquals(0, s.liveHandles())                       // the card goes once it is used
        s.events.onAction(Slot.NOTICE.code, "more")            // stale tap on a withdrawn card
        assertTrue(actions.mores.isEmpty())
        s.show(nudge(ref = "n8"))
        s.events.onAction(Slot.NOTICE.code, "more"); s.events.onAction(Slot.NOTICE.code, "more")
        assertEquals(listOf("n8"), actions.mores)
        s.show(nudge(ref = "n9"))
        s.events.onAction(Slot.NOTICE.code, "dismiss"); s.events.onAction(Slot.NOTICE.code, "dismiss")
        assertEquals(0, s.liveHandles()); assertEquals(1, actions.marks.size)
        s.show(nudge(ref = "n10"))
        s.events.onAction(Slot.NOTICE.code, "bogus")           // an action the card never offered
        assertEquals(1, s.liveHandles())
    }

    @Test fun markAndMoreAreRefusedWhileAGateIsClosedButDismissStillWorks() = runBlocking<Unit> {
        val s = sink()
        s.show(nudge())
        g.zone = true
        s.events.onAction(Slot.NOTICE.code, "mark")
        assertTrue(actions.marks.isEmpty())
        assertEquals(0, s.liveHandles())
        g.zone = false
        s.show(nudge())
        g.zone = true
        s.events.onAction(Slot.NOTICE.code, "dismiss")
        assertEquals(0, s.liveHandles())
    }

    @Test fun anActionHandlerThatThrowsDoesNotBreakTheSink() = runBlocking<Unit> {
        val s = NexusHudSink(t, g, { cfg }, object : NexusActionHandler { override fun mark(ref: String) = error("boom"); override fun more(ref: String) = error("boom") }, { now })
        s.show(nudge()); s.events.onAction(Slot.NOTICE.code, "mark")
        assertEquals(0, s.liveHandles())
    }

    @Test fun logsCarryNoCardText() = runBlocking<Unit> {
        val s = sink()
        s.show(HudEvent.Nudge("n", "SECRET-TITLE", "SECRET-BODY", setOf("m"), 5_000))
        g.zone = true
        s.show(nudge(body = "SECRET-BODY-2")); s.trip(TripReason.ZONE_HOLD)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "SECRET" in it }, logs.toString())
    }
}
