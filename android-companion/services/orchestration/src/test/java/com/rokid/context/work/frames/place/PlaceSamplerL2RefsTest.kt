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

private const val LAT3 = 17.361944
private const val LON3 = 78.474722
private fun pl2(id: String, name: String, radius: Int = 50, lat: Double = LAT3) = PlaceEntity(id, null, name, "monument", lat, LON3, radius, "$name is a monument. It is old.", "wikipedia:en", "page:1", 1_700_000_000_000L, false, false, 0, 100)


/** Layer 2 with reference photos (owner spec 28 Nov) and the "no photo, by GPS only" switch, at the sampler. Fakes only; log lines are counts and tokens. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSamplerL2RefsTest {
    private val t0 = 10_000_000L
    private var photoOk = true
    private var places = listOf(pl2("wd:Q1", "Alpha"), pl2("wd:Q2", "Beta"))
    private var withPhoto = setOf("wd:Q1")
    private var answer = "none"
    private var pol = PlacePolicy(confirmedOnly = true)
    private val asked = mutableListOf<List<String>>()
    private val cards = mutableListOf<PlaceCard>()
    private val events = mutableListOf<PlaceHistoryEvent>()
    private val outcomes = mutableListOf<PlaceOutcome>()
    private val logs = mutableListOf<String>()
    private var legacyCalls = 0

    private val store = object : PlaceFrameStore {
        override suspend fun newestDwell(sinceReceivedMs: Long) = PlaceFrame("f1", t0 - 5_000)
        override suspend fun tombstoned(frameId: String) = false
    }

    private fun sampler(scope: TestScope) = PlaceSampler(
        scope = scope, store = store, fix = { PlaceFix(LAT3, LON3, 3_000, 10f) }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) },
        match = { _, _ -> legacyCalls++; null },
        matchPlaces = { _, ps -> asked += ps.map { it.id }; PlaceMatchResult(PlaceMatchOutput(answer, 0.9f), true, refImages = ps.count { it.id in withPhoto }, candWithRefs = ps.count { it.id in withPhoto }) },
        refPlaceIds = { ps -> ps.map { it.id }.filter { it in withPhoto }.toSet() },
        saveMemory = { true }, onCard = { cards += it }, clock = { t0 }, enabled = { true }, zoneBlocked = { false }, networkUp = { true }, zones = { emptyList() },
        photoAllowed = { photoOk }, log = { logs += it }, stats = { outcomes += it }, history = { events += it }, historyOn = { true }, policy = { pol },
    )

    private fun TestScope.poke() { sampler(this).poke(); runCurrent() }

    @Test fun layer2AsksWithEveryNarrowedCandidateIncludingOneWithoutAPhoto() = runTest {
        answer = "1"
        poke()
        assertEquals("both candidates reach the vision call, the one with no photo too", listOf(listOf("wd:Q1", "wd:Q2")), asked)
        assertEquals(0, legacyCalls); assertEquals(PlaceOutcome.MATCHED, outcomes.single()); assertEquals("Alpha", cards.single().title)
        assertTrue(logs.toString(), logs.any { "l2refs=1" in it && "l2candRef=1" in it })
    }

    @Test fun dataSaverBlockingTheUploadStillGivesHeldDataSaverAndSendsNothing() = runTest {
        photoOk = false
        poke()
        assertTrue("no vision call, no images", asked.isEmpty())
        assertEquals(PlaceOutcome.HELD, outcomes.single()); assertEquals(CardDelivery.HELD_DATA_SAVER, events.single().delivery); assertTrue(cards.isEmpty())
    }

    @Test fun switchOffKeepsAPlaceWithoutPhotoHeldEvenWhenTheModelSaysNone() = runTest {
        places = listOf(pl2("wd:Q2", "Beta")); withPhoto = emptySet()
        pol = PlacePolicy(confirmedOnly = true, noPhotoByGps = false)
        poke()
        assertTrue(cards.isEmpty()); assertEquals(PlaceOutcome.NONE, outcomes.single()); assertEquals(CardDelivery.HELD_NOT_CONFIRMED, events.last().delivery)
    }

    @Test fun switchOnDeliversAPlaceWithoutPhotoAsANearbyCardNotConfirmed() = runTest {
        places = listOf(pl2("wd:Q2", "Beta")); withPhoto = emptySet()
        pol = PlacePolicy(confirmedOnly = true, noPhotoByGps = true)
        poke()
        assertEquals(PlaceOutcome.NEARBY, outcomes.single()); assertEquals(1, cards.size); assertEquals("Beta", cards.single().title)
        assertEquals(HistoryKind.NEARBY, events.last().kind); assertNull("a nearby card, never a held row", events.last().delivery)
    }

    @Test fun switchOnStillHoldsAPlaceThatHasAPhoto() = runTest {
        places = listOf(pl2("wd:Q1", "Alpha")); withPhoto = setOf("wd:Q1")
        pol = PlacePolicy(confirmedOnly = true, noPhotoByGps = true)
        poke()
        assertTrue("it has photos: only the layers may show it", cards.isEmpty()); assertEquals(CardDelivery.HELD_NOT_CONFIRMED, events.last().delivery)
    }

    @Test fun switchOnAlsoWorksWhenDataSaverBlocksThePhoto() = runTest {
        places = listOf(pl2("wd:Q2", "Beta")); withPhoto = emptySet(); photoOk = false
        pol = PlacePolicy(confirmedOnly = true, noPhotoByGps = true)
        poke()
        assertEquals(PlaceOutcome.NEARBY, outcomes.single()); assertEquals(1, cards.size)
    }

    @Test fun noFixStaysNoCardEvenWithTheSwitchOn() = runTest {
        pol = PlacePolicy(confirmedOnly = true, noPhotoByGps = true); withPhoto = emptySet()
        val s = PlaceSampler(scope = this, store = store, fix = { null }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) }, match = { _, _ -> null },
            saveMemory = { true }, onCard = { cards += it }, clock = { t0 }, enabled = { true }, zoneBlocked = { false }, networkUp = { true }, zones = { emptyList() },
            stats = { outcomes += it }, policy = { pol }, refPlaceIds = { emptySet() })
        s.poke(); runCurrent()
        assertTrue(cards.isEmpty()); assertEquals(PlaceOutcome.NO_FIX, outcomes.single())
    }
}
