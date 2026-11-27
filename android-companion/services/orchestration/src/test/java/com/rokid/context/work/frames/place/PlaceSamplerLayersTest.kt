package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.retrieval.PhotoConfirm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LAT2 = 17.361944
private const val LON2 = 78.474722
private fun pl(id: String, name: String, radius: Int = 50, lat: Double = LAT2) = PlaceEntity(id, null, name, "monument", lat, LON2, radius, "$name is a monument. It is old.", "wikipedia:en", "page:1", 1_700_000_000_000L, false, false, 0, 100)

/**
 * The layered place confirmation in the sampler (design 2026-11-27 A2): Layer 1 (photo vs reference photos), Layer 2 (the online call over the narrowed list), Layer 3 (the offline guess), the confirmed-only policy and
 * its HELD_* reasons, the check modes, pin cards, and "no fix = nothing". Fakes only; every line is content-free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSamplerLayersTest {
    private val t0 = 10_000_000L
    private var online = true
    private var photoOk = true
    private var admit = true
    private var fix: PlaceFix? = PlaceFix(LAT2, LON2, 3_000, 10f)
    private var places = listOf(pl("wd:Q1", "Alpha"))
    private var reply: PlaceMatchResult? = PlaceMatchResult(PlaceMatchOutput("1", 0.9f), true)
    private var thrown: Exception? = null
    private var pf = false
    private var pol = PlacePolicy(confirmedOnly = true)
    private var layer: PhotoLayerOutcome? = null
    private var guess: OfflineTierResult? = null
    private var guessAvailable = false
    private var guessCalls = 0
    private var layerCalls = 0
    private val calls = mutableListOf<List<String>>()
    private val cards = mutableListOf<PlaceCard>()
    private val events = mutableListOf<PlaceHistoryEvent>()
    private val outcomes = mutableListOf<PlaceOutcome>()
    private val logs = mutableListOf<String>()
    private var seed = emptyList<PlaceSeed>()
    private var v2 = false

    private val store = object : PlaceFrameStore {
        override suspend fun newestDwell(sinceReceivedMs: Long) = PlaceFrame("f1", t0 - 5_000)
        override suspend fun tombstoned(frameId: String) = false
    }

    private fun sampler(scope: TestScope) = PlaceSampler(
        scope = scope, store = store, fix = { fix }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) },
        match = { _, lines -> calls += lines; thrown?.let { throw it }; reply },
        saveMemory = { true }, onCard = { cards += it }, clock = { t0 }, enabled = { true }, zoneBlocked = { false }, networkUp = { online }, zones = { emptyList() },
        admit = { admit }, photoAllowed = { photoOk }, log = { logs += it }, stats = { outcomes += it }, history = { events += it }, historyOn = { true },
        pinFirstOn = { pf }, dedupeV2On = { v2 }, seedRecent = { seed },
        offlineGuess = guess?.let { r -> { _, _ -> guessCalls++; r } }, offlineAvailable = { guessAvailable },
        photoLayer = layer?.let { l -> { _, _, _ -> layerCalls++; l } }, policy = { pol },
    )

    private fun TestScope.poke() { sampler(this).poke(); runCurrent() }
    private fun held() = events.lastOrNull()?.delivery
    private fun result(outcome: PhotoConfirm.Outcome, accepted: String? = null, candidates: List<String>, withRef: Int = 1) = PhotoLayerOutcome.Decided(
        PhotoConfirm.Result(outcome, accepted?.let { PhotoConfirm.PlaceScore(it, 960, com.rokid.context.memory.retrieval.RefKind.OWN, PhotoConfirm.Level.HIGH) }, candidates,
            candidates.mapIndexed { i, id -> if (i < withRef) PhotoConfirm.PlaceScore(id, 900, com.rokid.context.memory.retrieval.RefKind.OWN, PhotoConfirm.Level.MID) else PhotoConfirm.PlaceScore(id, null, null, PhotoConfirm.Level.NO_REF) }, 20), 3)

    // ---- Layer 1 ----

    @Test fun l1AcceptShowsAnImageCardWithoutAnyCall() = runTest {
        places = listOf(pl("wd:Q1", "Alpha"), pl("wd:Q2", "Beta"))
        layer = result(PhotoConfirm.Outcome.ACCEPT, "wd:Q2", listOf("wd:Q2"))
        poke()
        assertEquals(PlaceOutcome.IMAGE, outcomes.single()); assertEquals(1, cards.size); assertEquals("Beta", cards.single().title)
        assertTrue("no online call was needed", calls.isEmpty())
        assertEquals(HistoryKind.IMAGE, events.single().kind); assertEquals(96, events.single().confPct); assertNull(events.single().delivery)
        val l = logs.last { it.startsWith("place layers ") }
        assertTrue(l, "l1=accept" in l && "k=3" in l && "card=shown" in l)
    }

    @Test fun l1MediumNarrowsTheListForTheOnlineCall() = runTest {
        places = listOf(pl("wd:Q1", "Alpha"), pl("wd:Q2", "Beta"), pl("wd:Q3", "Gamma"))
        layer = result(PhotoConfirm.Outcome.MEDIUM, null, listOf("wd:Q3", "wd:Q1"), withRef = 2)
        reply = PlaceMatchResult(PlaceMatchOutput("2", 0.9f), true)
        poke()
        assertEquals(1, calls.size); assertEquals(2, calls.single().size)
        assertFalse("the dropped place is not offered", calls.single().any { it.contains("Beta") })
        assertEquals(PlaceOutcome.MATCHED, outcomes.single()); assertEquals("Gamma", cards.single().title) // the narrowed list keeps the nearest-first order: Alpha, Gamma; choice 2
        assertEquals(HistoryKind.PHOTO, events.single().kind)
        assertTrue(logs.last { it.startsWith("place layers ") }.contains("l1=medium"))
    }

    @Test fun l1AllBelowDropsEverythingAndHoldsTheRow() = runTest {
        layer = result(PhotoConfirm.Outcome.BELOW, null, emptyList())
        poke()
        assertEquals(PlaceOutcome.HELD, outcomes.single()); assertTrue(cards.isEmpty()); assertTrue(calls.isEmpty())
        assertEquals(CardDelivery.HELD_NOT_CONFIRMED, held())
    }

    @Test fun l1SkippedGoesOnToTheOtherLayers() = runTest {
        layer = PhotoLayerOutcome.Skipped("hot")
        poke()
        assertEquals(1, calls.size); assertEquals(PlaceOutcome.MATCHED, outcomes.single())
        assertTrue(logs.last { it.startsWith("place layers ") }.contains("l1=skip skip=hot"))
    }

    @Test fun theDeveloperSwitchOfLayer1IsTheKillSwitch() = runTest {
        layer = result(PhotoConfirm.Outcome.ACCEPT, "wd:Q1", listOf("wd:Q1")); pol = PlacePolicy(confirmedOnly = true, l1 = false)
        poke()
        assertEquals(0, layerCalls); assertEquals(PlaceOutcome.MATCHED, outcomes.single()); assertEquals(1, calls.size)
    }

    // ---- Layer 2 ----

    @Test fun l2ConfirmsAndShowsThePhotoCard() = runTest {
        poke()
        assertEquals(PlaceOutcome.MATCHED, outcomes.single()); assertEquals(1, cards.size); assertEquals(HistoryKind.PHOTO, events.single().kind); assertEquals(90, events.single().confPct)
        assertTrue(logs.last { it.startsWith("place layers ") }.let { "l2=confirmed" in it && "card=shown" in it })
    }

    @Test fun l2BlockedByDataSaverIsHeldWithItsReason() = runTest {
        photoOk = false
        poke()
        assertEquals(PlaceOutcome.HELD, outcomes.single()); assertTrue(cards.isEmpty()); assertTrue(calls.isEmpty())
        val e = events.single(); assertEquals(HistoryKind.NEARBY, e.kind); assertEquals(CardDelivery.HELD_DATA_SAVER, e.delivery); assertNull(e.cardId)
        assertTrue(logs.last { it.startsWith("place layers ") }.let { "l2=skipped_data_saver" in it && "card=held_data_saver" in it })
    }

    @Test fun l2NoneIsANoMatchRow() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("NONE", 0.9f), true)
        poke()
        assertEquals(PlaceOutcome.NONE, outcomes.single()); assertTrue(cards.isEmpty())
        assertEquals(HistoryKind.NO_MATCH, events.single().kind); assertEquals(CardDelivery.HELD_NOT_CONFIRMED, events.single().delivery)
    }

    @Test fun lowConfidenceStaysAPossibleRowAndNoCard() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("1", 0.2f), true)
        poke()
        assertEquals(PlaceOutcome.LOW_CONFIDENCE, outcomes.single()); assertTrue(cards.isEmpty()); assertEquals(HistoryKind.POSSIBLE, events.single().kind)
    }

    @Test fun aGenuineCallFailureFallsToTheOfflineGuessInAutomaticAndIsHeldInOnlineOnly() = runTest {
        thrown = java.io.IOException("x"); guessAvailable = true; guess = OfflineTierResult.maybe(places[0], "lt70", "lt06", 1)
        poke()
        assertEquals(PlaceOutcome.MAYBE, outcomes.last()); assertEquals(1, cards.size); assertEquals(1, guessCalls)
        cards.clear(); events.clear(); outcomes.clear(); guessCalls = 0
        pol = PlacePolicy(confirmedOnly = true, checkMode = CheckMode.ONLINE_ONLY)
        poke()
        assertEquals(PlaceOutcome.HELD, outcomes.last()); assertTrue(cards.isEmpty()); assertEquals(0, guessCalls); assertEquals(CardDelivery.HELD_CALL_FAILED, held())
    }

    @Test fun notAdmittedIsHeldButStillNotAdmitted() = runTest {
        admit = false
        poke()
        assertEquals(PlaceOutcome.NOT_ADMITTED, outcomes.single()); assertEquals(CardDelivery.HELD_NOT_ADMITTED, held()); assertTrue(cards.isEmpty())
    }

    // ---- Layer 3 and the check modes ----

    @Test fun offlineWithTheModelShowsAMaybeCard() = runTest {
        online = false; guessAvailable = true; guess = OfflineTierResult.maybe(places[0], "lt70", "lt06", 1)
        poke()
        assertEquals(PlaceOutcome.MAYBE, outcomes.single()); assertEquals(1, cards.size); assertEquals(HistoryKind.MAYBE, events.single().kind); assertEquals(0, calls.size)
        assertTrue(logs.last { it.startsWith("place layers ") }.contains("l3=maybe"))
    }

    @Test fun offlineWithoutTheModelIsHeldNoModel() = runTest {
        online = false; guess = null
        poke()
        assertEquals(PlaceOutcome.HELD, outcomes.single()); assertTrue(cards.isEmpty()); assertEquals(CardDelivery.HELD_OFFLINE_NO_MODEL, held())
        online = false; guess = OfflineTierResult.OFF; guessAvailable = false; events.clear(); outcomes.clear()
        poke(); assertEquals(CardDelivery.HELD_OFFLINE_NO_MODEL, held())
    }

    @Test fun offlineGuessBelowTheCutoffIsHeldNotConfirmed() = runTest {
        online = false; guessAvailable = true; guess = OfflineTierResult.OFF.let { r -> OfflineTierResult.RATE_LIMITED }
        poke()
        assertEquals(CardDelivery.HELD_NOT_CONFIRMED, held())
    }

    @Test fun onlineOnlyWithoutANetworkIsHeldAndNeverUsesTheGuess() = runTest {
        online = false; guessAvailable = true; guess = OfflineTierResult.maybe(places[0], "lt70", "lt06", 1); pol = PlacePolicy(confirmedOnly = true, checkMode = CheckMode.ONLINE_ONLY)
        poke()
        assertEquals(PlaceOutcome.HELD, outcomes.single()); assertEquals(0, guessCalls); assertEquals(CardDelivery.HELD_ONLINE_ONLY_NO_NETWORK, held())
    }

    @Test fun onThePhoneOnlyNeverCallsOnlineEvenWithANetwork() = runTest {
        pol = PlacePolicy(confirmedOnly = true, checkMode = CheckMode.PHONE_ONLY); guessAvailable = true; guess = OfflineTierResult.maybe(places[0], "lt70", "lt06", 1)
        poke()
        assertTrue(calls.isEmpty()); assertEquals(PlaceOutcome.MAYBE, outcomes.single()); assertEquals(1, cards.size)
    }

    @Test fun onThePhoneOnlyWithNothingToCompareIsHeldNoReference() = runTest {
        pol = PlacePolicy(confirmedOnly = true, checkMode = CheckMode.PHONE_ONLY); layer = result(PhotoConfirm.Outcome.NO_REF, null, listOf("wd:Q1"), withRef = 0)
        poke()
        assertTrue(calls.isEmpty()); assertEquals(CardDelivery.HELD_NO_REFERENCE, held()); assertTrue(cards.isEmpty())
    }

    @Test fun layer2OffByItsDeveloperSwitchNeverCalls() = runTest {
        pol = PlacePolicy(confirmedOnly = true, l2 = false)
        poke()
        assertTrue(calls.isEmpty()); assertEquals(PlaceOutcome.HELD, outcomes.single())
    }

    // ---- no fix, pins, old behaviour ----

    @Test fun noFixMeansNothingAtAll() = runTest {
        fix = null
        poke()
        assertEquals(PlaceOutcome.NO_FIX, outcomes.single()); assertTrue(cards.isEmpty()); assertTrue(events.isEmpty()); assertTrue(calls.isEmpty()); assertEquals(0, layerCalls)
    }

    private val pin = "user:00000000-0000-0000-0000-000000000001"

    @Test fun aPinFirstCardIsHeldByDefaultAndTheAttemptGoesOnToThePhotoChecks() = runTest {
        pf = true; places = listOf(pl(pin, "My pin", radius = 30)); online = false; guess = null
        poke()
        assertTrue("no location-only pin card on the glasses", cards.isEmpty()); assertEquals(PlaceOutcome.HELD, outcomes.single())
        val e = events.single(); assertEquals(HistoryKind.PIN, e.kind); assertEquals(CardDelivery.HELD_OFFLINE_NO_MODEL, e.delivery)
    }

    @Test fun aPinFirstCardCanBeConfirmedByThePhotoLayer() = runTest {
        pf = true; places = listOf(pl(pin, "My pin", radius = 30)); layer = result(PhotoConfirm.Outcome.ACCEPT, pin, listOf(pin))
        poke()
        assertEquals(PlaceOutcome.IMAGE, outcomes.single()); assertEquals(1, cards.size)
    }

    @Test fun thePinCardSettingShowsTheLocationOnlyPinCard() = runTest {
        pf = true; places = listOf(pl(pin, "My pin", radius = 30)); pol = PlacePolicy(confirmedOnly = true, pinCardsNearby = true)
        poke()
        assertEquals(PlaceOutcome.PIN_FIRST, outcomes.single()); assertEquals(1, cards.size); assertEquals(HistoryKind.PIN, events.single().kind); assertNull(events.single().delivery)
    }

    @Test fun confirmedOnlyOffKeepsTheOldBehaviour() = runTest {
        pol = PlacePolicy(); online = false
        poke()
        assertEquals(PlaceOutcome.NEARBY, outcomes.single()); assertEquals(1, cards.size); assertEquals(HistoryKind.NEARBY, events.single().kind); assertNull(events.single().delivery)
        assertTrue("no layers line without the layered flow", logs.none { it.startsWith("place layers ") })
        cards.clear(); events.clear(); outcomes.clear(); pf = true; places = listOf(pl(pin, "My pin", radius = 30))
        poke(); assertEquals(PlaceOutcome.PIN_FIRST, outcomes.last()); assertEquals(1, cards.size)
    }

    // ---- never silent, never counted as shown ----

    @Test fun aHeldRowNeverSeedsTheOncePerPlaceDedupe() = runTest {
        v2 = true; seed = listOf(PlaceSeed("wd:Q1", t0 - 60_000, "NEARBY", delivery = CardDelivery.HELD_DATA_SAVER))
        poke()
        assertEquals("a held row showed nothing: the place still gets its card", PlaceOutcome.MATCHED, outcomes.single()); assertEquals(1, cards.size)
        cards.clear(); outcomes.clear(); events.clear(); seed = listOf(PlaceSeed("wd:Q1", t0 - 60_000, "IMAGE")); val s2 = PlaceFlight()
        // an IMAGE card IS a shown card (fresh flight: the seed is read once per process)
        val sm = PlaceSampler(scope = this, store = store, fix = { fix }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) }, match = { _, l -> calls += l; reply }, saveMemory = { true }, onCard = { cards += it },
            clock = { t0 }, enabled = { true }, zoneBlocked = { false }, networkUp = { true }, zones = { emptyList() }, flight = s2, stats = { outcomes += it }, history = { events += it }, historyOn = { true }, dedupeV2On = { true }, seedRecent = { seed }, policy = { pol })
        sm.poke(); runCurrent()
        assertTrue("an IMAGE card is a shown card: the place is not carded again", cards.isEmpty())
    }

    @Test fun everyHeldLineIsContentFree() = runTest {
        photoOk = false; poke()
        val all = logs.joinToString("\n")
        assertFalse(all.contains("Alpha")); assertFalse(Regex("""\d+\.\d{4,}""").containsMatchIn(all))
        assertTrue(logs.last { it.startsWith("place layers ") }.length < 300)
    }
}
