package com.rokid.context.phone.nexus

import com.rokid.context.core.model.Overlay
import com.rokid.context.work.frames.place.PlaceCard
import com.rokid.context.work.frames.place.PlaceConfidence
import io.github.pca.nexusport.HudEvent
import io.github.pca.nexusport.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NexusFeederTest {
    private val sent = mutableListOf<HudEvent>()
    private val out = HudShow { e -> sent += e; Outcome.Shown() }
    private var pinLive = false
    private var asks: Map<String, Set<String>?> = emptyMap()

    private fun kotlinx.coroutines.test.TestScope.feeder() =
        NexusFeeder(backgroundScope, out, popupMs = { 6_000 }, askSources = { asks[it] }, pinLive = { pinLive }, clock = { testScheduler.currentTime })

    private val card = PlaceCard("p1", "Charminar", "A 16th century monument.", "Wikipedia · fetched 12 Nov", PlaceConfidence.PHOTO, frameId = "f9", cardId = "pc1")

    @Test fun aPlaceCardIsSentOnceWithItsPhotoAsTheSource() = runTest {
        val f = feeder()
        f.placeCard(card); f.placeCard(card); f.placeCard(null); runCurrent()
        val e = sent.single() as HudEvent.PlaceCard
        assertEquals(setOf("frame:f9"), e.sources); assertEquals("Charminar", e.title); assertEquals("pc1", e.ref); assertEquals(6_000, e.ttlMs)
        assertTrue("A 16th century monument." in e.body && "Wikipedia" in e.body)
        f.placeCard(card.copy(cardId = "pc2", confidence = PlaceConfidence.NEARBY)); runCurrent()
        assertEquals(2, sent.size); assertTrue("not checked" in (sent[1] as HudEvent.PlaceCard).body)
    }

    @Test fun aNudgeCarriesItsSourcesAndIsSentOnce() = runTest {
        val f = feeder()
        f.nudge(NexusNudge("n1", "Call the plumber", setOf("m1", "frame:f1"))); f.nudge(NexusNudge("n1", "Call the plumber", setOf("m1"))); f.nudge(null); runCurrent()
        val e = sent.single() as HudEvent.Nudge
        assertEquals(setOf("m1", "frame:f1"), e.sources); assertEquals("n1", e.ref)
    }

    @Test fun anAnswerIsSentWithTheSourcesOfItsStoredAskAndNeverWhenTheyCannotBeRead() = runTest {
        asks = mapOf("a1" to setOf("m2"), "a2" to null)
        val f = feeder()
        f.answer(Overlay.Answer("q", "It is in the drawer.", speaking = false, askId = "a1")); f.answer(Overlay.Answer("q", "It is in the drawer.", speaking = true, askId = "a1"))
        f.answer(Overlay.Answer("q", "Unverifiable.", speaking = false, askId = "a2"))
        f.answer(Overlay.Answer("q", "No ask id.", speaking = false, askId = null))
        f.answer(Overlay.Listening("hel")); f.answer(null); runCurrent()
        val e = sent.single() as HudEvent.AskAnswer
        assertEquals(setOf("m2"), e.sources); assertEquals(false, e.speak); assertEquals("a1", e.ref)
    }

    @Test fun aMarkConfirmIsContentFree() = runTest {
        feeder().markSaved("mk:e:1"); runCurrent()
        val e = sent.single() as HudEvent.MarkConfirm
        assertEquals("ok", e.reasonToken); assertTrue(e.sources.isEmpty())
    }

    @Test fun theCapturePinIsSentOnChangeAndRefreshedWhenGoneOrOld() = runTest {
        val f = feeder(); f.startPolling(everyMs = 15_000)
        f.capture(HudEvent.CaptureState.State.CAPTURING); runCurrent()
        assertEquals(1, sent.size)
        pinLive = true; advanceTimeBy(60_000); assertEquals("a live young pin is left alone", 1, sent.size)
        pinLive = false; advanceTimeBy(15_000); assertEquals("a withdrawn pin comes back", 2, sent.size)
        pinLive = true; advanceTimeBy(11 * 60_000L); assertTrue("an old pin is refreshed before its TTL", sent.size >= 3)
        f.capture(HudEvent.CaptureState.State.PAUSED); runCurrent()
        assertEquals(HudEvent.CaptureState.State.PAUSED, (sent.last() as HudEvent.CaptureState).state)
    }

    @Test fun resetForgetsWhatWasSentSoASwitchBackShowsTheNextCard() = runTest {
        val f = feeder()
        f.placeCard(card); runCurrent(); f.reset(); f.placeCard(card); runCurrent()
        assertEquals(2, sent.size)
    }

    @Test fun aFailingTransportNeverEscapes() = runTest {
        val f = NexusFeeder(backgroundScope, { throw IllegalStateException("x") }, { 6_000 }, { null }, { false })
        f.nudge(NexusNudge("n1", "t", emptySet())); runCurrent()
    }
}
