package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LAT = 17.361944
private const val LON = 78.474722
private const val SUMMARY = "Charminar is a monument in Hyderabad. It was built in 1591."

/** About [m] metres north of the test position. */
private fun north(m: Double) = LAT + m / 111_320.0

private fun place(id: String, name: String, lat: Double = LAT, lon: Double = LON, radius: Int = 50, kind: String = "monument", parent: String? = null, summary: String = SUMMARY) =
    PlaceEntity(id, parent, name, kind, lat, lon, radius, summary, "wikipedia:en", "page:1", 1_700_000_000_000L, false, false, 0, 100)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSamplerTest {
    private val T0 = 10_000_000L
    private var now = T0
    private var enabled = true
    private var zone = false
    private var online = true
    private var admit = true
    private var gen = 0L
    private var tomb = false
    private var frame: PlaceFrame? = PlaceFrame("f1", T0 - 5_000)
    private var fix: PlaceFix? = PlaceFix(LAT, LON, 3_000, 10f)
    private var places: List<PlaceEntity> = listOf(place("wd:Q1", "Charminar"))
    private var reply: PlaceMatchResult? = PlaceMatchResult(PlaceMatchOutput("1", 0.86f), true)
    private var thrown: Exception? = null
    private var saved = true

    private var fixCalls = 0
    private var candidateCalls = 0
    private val matchCalls = mutableListOf<Pair<String, List<String>>>()
    private val memories = mutableListOf<PlaceMemoryRequest>()
    private val cards = mutableListOf<PlaceCard>()
    private val outcomes = mutableListOf<PlaceOutcome>()
    private val logs = mutableListOf<String>()
    private var flight = PlaceFlight()

    private val store = object : PlaceFrameStore {
        override suspend fun newestDwell(sinceReceivedMs: Long) = frame
        override suspend fun tombstoned(frameId: String) = tomb
    }

    private fun sampler(scope: TestScope) = PlaceSampler(
        scope = scope, store = store, fix = { fixCalls++; fix },
        candidates = { _, _, _ -> candidateCalls++; PlaceCandidates(places, CellOutcome.FRESH) },
        match = { id, lines -> matchCalls += id to lines; thrown?.let { throw it }; reply },
        saveMemory = { memories += it; saved }, onCard = { cards += it }, clock = { now }, enabled = { enabled }, zoneBlocked = { zone }, networkUp = { online },
        admit = { admit }, generation = { gen }, flight = flight, log = { logs += it }, stats = { outcomes += it },
    )

    private fun TestScope.poke(s: PlaceSampler = sampler(this)) { s.poke(); runCurrent() }
    private fun fresh(id: String) { frame = PlaceFrame(id, now - 5_000) }

    // ---- gating ----

    @Test fun offDoesNothingAtAll() = runTest {
        enabled = false; poke()
        assertEquals(0, fixCalls + candidateCalls + matchCalls.size); assertTrue(cards.isEmpty()); assertTrue(outcomes.isEmpty())
    }

    @Test fun anActiveOrApproachingZoneNeverReadsAFixNorCallsTheNetworkOrTheModel() = runTest {
        zone = true; poke()
        assertEquals(listOf(PlaceOutcome.ZONE), outcomes)
        assertEquals(0, fixCalls); assertEquals(0, candidateCalls); assertEquals(0, matchCalls.size); assertTrue(cards.isEmpty()); assertTrue(memories.isEmpty())
    }

    @Test fun aZoneThatStartsDuringTheCallDropsTheResult() = runTest {
        val s = PlaceSampler(
            scope = this, store = store, fix = { fix }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) },
            match = { _, _ -> zone = true; reply }, saveMemory = { memories += it; true }, onCard = { cards += it }, clock = { now }, enabled = { enabled },
            zoneBlocked = { zone }, networkUp = { true }, stats = { outcomes += it },
        )
        poke(s)
        assertEquals(listOf(PlaceOutcome.DROPPED), outcomes); assertTrue(cards.isEmpty()); assertTrue(memories.isEmpty())
    }

    @Test fun noDwellFrameMeansNothing() = runTest {
        frame = null; poke()
        assertEquals(listOf(PlaceOutcome.NO_FRAME), outcomes); assertEquals(0, fixCalls)
    }

    @Test fun aFrameWithAnUnknownOrOldCaptureTimeIsSkipped() = runTest {
        frame = PlaceFrame("f1", null); poke()
        frame = PlaceFrame("f2", now - 200_000); flight = PlaceFlight(); poke()
        assertEquals(listOf(PlaceOutcome.STALE_FRAME, PlaceOutcome.STALE_FRAME), outcomes); assertEquals(0, fixCalls)
    }

    @Test fun noFixOrAStaleOrInaccurateFixIsSkippedBeforeAnyLookup() = runTest {
        fix = null; poke()
        fix = PlaceFix(LAT, LON, 61_000, 10f); poke()
        fix = PlaceFix(LAT, LON, 3_000, 51f); poke()
        fix = PlaceFix(LAT, LON, 3_000, Float.NaN); poke()
        assertEquals(listOf(PlaceOutcome.NO_FIX, PlaceOutcome.STALE_FIX, PlaceOutcome.STALE_FIX, PlaceOutcome.STALE_FIX), outcomes)
        assertEquals(0, candidateCalls); assertEquals(0, matchCalls.size)
    }

    @Test fun aFixOfExactlyTheLimitsIsAccepted() = runTest {
        fix = PlaceFix(LAT, LON, 60_000, 50f); poke()
        assertEquals(1, matchCalls.size)
    }

    @Test fun noCandidatesMeansNoUpload() = runTest {
        places = emptyList(); poke()
        assertEquals(listOf(PlaceOutcome.NO_CANDIDATES), outcomes); assertEquals(0, matchCalls.size); assertTrue(cards.isEmpty())
    }

    @Test fun theSameFrameIsNotMatchedTwice() = runTest {
        val s = sampler(this); poke(s); now += 25_000; frame = PlaceFrame("f1", now - 5_000); poke(s)
        assertEquals(1, matchCalls.size); assertEquals(PlaceOutcome.SEEN, outcomes.last())
    }

    @Test fun closedOrWipedOrDisabledMidCallDropsTheResult() = runTest {
        val s = PlaceSampler(
            scope = this, store = store, fix = { fix }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) },
            match = { _, _ -> gen++; reply }, saveMemory = { memories += it; true }, onCard = { cards += it }, clock = { now }, enabled = { enabled },
            zoneBlocked = { false }, networkUp = { true }, generation = { gen }, stats = { outcomes += it },
        )
        poke(s)
        assertEquals(listOf(PlaceOutcome.DROPPED), outcomes); assertTrue(cards.isEmpty() && memories.isEmpty())
    }

    @Test fun aPhotoForgottenDuringTheCallDropsTheResult() = runTest {
        val s = PlaceSampler(
            scope = this, store = store, fix = { fix }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) },
            match = { _, _ -> tomb = true; reply }, saveMemory = { memories += it; true }, onCard = { cards += it }, clock = { now }, enabled = { enabled },
            zoneBlocked = { false }, networkUp = { true }, stats = { outcomes += it },
        )
        poke(s)
        assertEquals(listOf(PlaceOutcome.DROPPED), outcomes); assertTrue(cards.isEmpty() && memories.isEmpty())
    }

    // ---- rate limits and dedupe ----

    @Test fun oneCallPerTwentySecondsAndNeverTwoInFlight() = runTest {
        val s = sampler(this); poke(s)
        now += 10_000; fresh("f2"); fix = PlaceFix(north(200.0), LON, 1_000, 10f); poke(s)
        assertEquals(1, matchCalls.size); assertEquals(PlaceOutcome.RATE_LIMITED, outcomes.last())
        now += 11_000; fresh("f3"); poke(s)
        assertEquals(2, matchCalls.size)
    }

    @Test fun theSamePlaceWithinTwoMinutesAndThirtyMetresSkipsTheCall() = runTest {
        val s = sampler(this); poke(s)
        now += 60_000; fresh("f2"); fix = PlaceFix(north(20.0), LON, 1_000, 10f); poke(s)
        assertEquals(1, matchCalls.size); assertEquals(PlaceOutcome.DUPLICATE, outcomes.last())
        now += 61_000; fresh("f3"); poke(s)                       // 2 min 1 s after the first
        assertEquals(2, matchCalls.size)
    }

    @Test fun movingMoreThanThirtyMetresCallsAgainAfterTheRateGap() = runTest {
        val s = sampler(this); poke(s)
        now += 25_000; fresh("f2"); fix = PlaceFix(north(40.0), LON, 1_000, 10f); poke(s)
        assertEquals(2, matchCalls.size)
    }

    // ---- the call and the contract ----

    @Test fun aPhotoMatchMakesTheCardAndTheMemory() = runTest {
        places = listOf(place("wd:Q1", "Charminar", parent = "wd:Q2"), place("wd:Q2", "Old City", kind = "historic_site"))
        poke()
        assertEquals(listOf(PlaceOutcome.MATCHED), outcomes)
        val c = cards.single()
        assertEquals(PlaceCard("wd:Q1", "Charminar", "Charminar is a monument in Hyderabad.", c.sourceLabel, PlaceConfidence.PHOTO, "f1"), c)
        assertTrue(c.sourceLabel.startsWith("Wikipedia · fetched "))
        val m = memories.single()
        assertEquals("f1", m.frameId); assertEquals("wd:Q1", m.place.id); assertEquals("Old City", m.parentName); assertEquals(0.86f, m.confidence, 0f); assertTrue(m.delivered)
    }

    @Test fun thePromptCarriesOnlyNumberedNamesAndKindsNeverSummaries() = runTest {
        places = listOf(place("wd:Q1", "Charminar"), place("wd:Q9", "Mecca Masjid", lat = north(100.0), kind = "mosque", summary = "SECRET SUMMARY TEXT"))
        poke()
        val lines = matchCalls.single().second
        assertEquals(listOf("1. Charminar | monument", "2. Mecca Masjid | mosque"), lines)
        assertFalse(lines.joinToString().contains("SECRET"))
    }

    @Test fun aChoiceOutsideTheSetIsAContractFailureAndMakesNoCard() = runTest {
        val s = sampler(this)
        for ((i, bad) in listOf("2", "0", "-1", "wd:Q1", "Charminar", "1.0", " 1", "a woman in a red sari", "").withIndex()) {
            reply = PlaceMatchResult(PlaceMatchOutput(bad, 0.95f), true)
            now += 25_000 * (i + 1); fix = PlaceFix(north(40.0 * (i + 1)), LON, 1_000, 10f); fresh("g$i"); poke(s)
        }
        assertTrue("every one is a contract failure: $outcomes", outcomes.all { it == PlaceOutcome.CONTRACT })
        assertTrue(cards.isEmpty()); assertTrue(memories.isEmpty())
    }

    @Test fun noneAndLowConfidenceMakeNoCard() = runTest {
        val s = sampler(this)
        reply = PlaceMatchResult(PlaceMatchOutput("NONE", 0.9f), true); poke(s)
        now += 25_000; fresh("f2"); fix = PlaceFix(north(100.0), LON, 1_000, 10f); reply = PlaceMatchResult(PlaceMatchOutput("1", 0.3f), true); poke(s)
        assertEquals(listOf(PlaceOutcome.NONE, PlaceOutcome.LOW_CONFIDENCE), outcomes); assertTrue(cards.isEmpty() && memories.isEmpty())
    }

    @Test fun aReplyWithoutDeliveryProofIsAFailureNotAPhotoMatchNorANearbyGuess() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("1", 0.99f), false); poke()
        assertEquals(PlaceOutcome.FAILED, outcomes.single()); assertTrue(cards.isEmpty()); assertTrue(memories.isEmpty())
    }

    @Test fun aMemoryThatCouldNotBeWrittenMeansNoCard() = runTest {
        saved = false; poke()
        assertEquals(listOf(PlaceOutcome.DROPPED), outcomes); assertTrue(cards.isEmpty())
    }

    // ---- fallback card ----

    @Test fun offlineNeverCallsTheModelAndShowsNameAndDistanceOnly() = runTest {
        online = false; fix = PlaceFix(north(130.0), LON, 1_000, 10f); poke()      // 130 m centre, 50 m radius: 80 m to the edge
        assertEquals(0, matchCalls.size); assertEquals(listOf(PlaceOutcome.NEARBY), outcomes)
        val c = cards.single()
        assertEquals(PlaceCard("wd:Q1", "Charminar", "80 m away", "nearby · not checked", PlaceConfidence.NEARBY, "f1"), c)
        assertFalse("never a summary from a guess", c.line.contains("monument"))
        assertTrue(memories.isEmpty())
    }

    @Test fun aGenuineProviderFailureFallsBackTheSameWay() = runTest {
        thrown = com.rokid.context.ai.ModelCallException("down", retryable = true); poke()
        assertEquals(PlaceOutcome.NEARBY, outcomes.single()); assertEquals("nearby · not checked", cards.single().sourceLabel)
    }

    @Test fun aNetworkFailureFallsBack() = runTest {
        thrown = java.io.IOException("reset"); poke()
        assertEquals(PlaceOutcome.NEARBY, outcomes.single())
    }

    @Test fun aPolicyDenialInTheRouterIsNeverANearbyIdentification() = runTest {
        thrown = com.rokid.context.ai.ImagesUnsupportedException(); poke()
        assertEquals(PlaceOutcome.FAILED, outcomes.single()); assertTrue(cards.isEmpty()); assertTrue(memories.isEmpty())
    }

    @Test fun anInvalidReplyOrAnUnknownExceptionIsNeverANearbyIdentification() = runTest {
        thrown = com.rokid.context.ai.ModelCallException("bad", retryable = false, kind = com.rokid.context.ai.FailureKind.INVALID_OUTPUT); poke()
        assertEquals(PlaceOutcome.FAILED, outcomes.single()); assertTrue(cards.isEmpty())
    }

    @Test fun aMatchWithNoResultIsNoCard() = runTest {
        reply = null; poke()
        assertEquals(PlaceOutcome.FAILED, outcomes.single()); assertTrue(cards.isEmpty())
    }

    @Test fun thePowerOrThermalGateMakesNoCallAndNoNearbyCard() = runTest {
        admit = false; poke()
        assertEquals(0, matchCalls.size); assertEquals(PlaceOutcome.NOT_ADMITTED, outcomes.single()); assertTrue(cards.isEmpty() && memories.isEmpty())
    }

    @Test fun noFallbackWhenTheNearestIsFarOrNotUnique() = runTest {
        online = false
        fix = PlaceFix(north(400.0), LON, 1_000, 10f); poke()                      // 350 m to the edge
        assertEquals(PlaceOutcome.NOTHING_NEARBY, outcomes.last())
        places = listOf(place("wd:Q1", "Charminar"), place("wd:Q3", "Laad Bazaar", lat = north(30.0), kind = "attraction"))
        fix = PlaceFix(LAT, LON, 1_000, 10f); fresh("f2"); now += 25_000; poke()   // two places within 100 m
        assertEquals(PlaceOutcome.NOTHING_NEARBY, outcomes.last()); assertTrue(cards.isEmpty())
    }

    @Test fun aFarSecondPlaceDoesNotBlockTheFallback() = runTest {
        online = false; places = listOf(place("wd:Q1", "Charminar"), place("wd:Q4", "Far", lat = north(250.0), kind = "attraction")); poke()
        assertEquals("Charminar", cards.single().title); assertEquals("here", cards.single().line)
    }

    // ---- no people path, logs ----

    @Test fun thereIsNoPathForModelTextOntoACard() = runTest {
        // the only model output is a candidate number: free text is a contract failure, and every card field is built from the pack row
        reply = PlaceMatchResult(PlaceMatchOutput("a smiling man in a blue shirt", 0.99f, kotlinx.serialization.json.JsonObject(mapOf("note" to kotlinx.serialization.json.JsonPrimitive("a man")))), true)
        poke()
        assertTrue(cards.isEmpty())
        val fields = PlaceCard::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("placeId", "title", "line", "sourceLabel", "confidence", "frameId", "cardId"), fields)
    }

    @Test fun logsAreOutcomeNamesOnly() = runTest {
        poke(); now += 25_000; fresh("f2"); fix = PlaceFix(north(300.0), LON, 1_000, 10f); poke()
        assertTrue(logs.all { it.matches(Regex("place outcome=[A-Z_]+")) })
    }

    @Test fun cardTextRules() {
        assertEquals("One.", PlaceCards.firstSentence("One. Two."))
        assertEquals("No full stop here", PlaceCards.firstSentence("No full stop here"))
        assertEquals("", PlaceCards.firstSentence("   "))
        val long = PlaceCards.firstSentence("word ".repeat(60).trim() + ". Next.")
        assertTrue(long.length <= PlaceCards.LINE_MAX); assertTrue(long.endsWith("…"))
        assertEquals("Monument", PlaceCards.photo(place("wd:Q1", "X", summary = ""), "f").line)
        assertEquals("OpenStreetMap", PlaceCards.sourceLabel(place("osm:w1", "X").copy(source = "osm", fetchedAt = 0)))
    }
}
