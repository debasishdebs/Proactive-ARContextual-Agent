package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.Geo
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
    private var photoOk = true
    private var v2 = false
    private var retry = false
    private var cell = CellOutcome.FRESH
    private var seed: List<Pair<String, Long>> = emptyList()

    private var fixCalls = 0
    private var candidateCalls = 0
    private val matchCalls = mutableListOf<Pair<String, List<String>>>()
    private val memories = mutableListOf<PlaceMemoryRequest>()
    private val cards = mutableListOf<PlaceCard>()
    private val outcomes = mutableListOf<PlaceOutcome>()
    private val logs = mutableListOf<String>()
    private var flight = PlaceFlight()

    private var zoneList: List<com.rokid.context.core.model.zone.PauseZone>? = emptyList()
    private fun zoneAt(lat: Double, lon: Double, buffer: Int = 100) = com.rokid.context.core.model.zone.PauseZone("z1", "Z", lat - 0.0005, lon - 0.0005, lat + 0.0005, lon + 0.0005, buffer)

    private val store = object : PlaceFrameStore {
        override suspend fun newestDwell(sinceReceivedMs: Long) = frame
        override suspend fun tombstoned(frameId: String) = tomb
    }

    private fun sampler(scope: TestScope) = PlaceSampler(
        scope = scope, store = store, fix = { fixCalls++; fix },
        candidates = { _, _, _ -> candidateCalls++; PlaceCandidates(places, cell) },
        match = { id, lines -> matchCalls += id to lines; thrown?.let { throw it }; reply },
        saveMemory = { memories += it; saved }, onCard = { cards += it }, clock = { now }, enabled = { enabled }, zoneBlocked = { zone }, networkUp = { online }, zones = { zoneList },
        admit = { admit }, photoAllowed = { photoOk }, generation = { gen }, flight = flight, log = { logs += it }, stats = { outcomes += it },
        dedupeV2On = { v2 }, seedRecent = { seed }, retryOn = { retry },
    )

    private fun TestScope.poke(s: PlaceSampler = sampler(this)) { s.poke(); runCurrent() }
    private fun fresh(id: String) { frame = PlaceFrame(id, now - 5_000) }

    // ---- gating ----

    @Test fun offDoesNothingAtAll() = runTest {
        enabled = false; poke()
        assertEquals(0, fixCalls + candidateCalls + matchCalls.size); assertTrue(cards.isEmpty()); assertTrue(outcomes.isEmpty())
    }

    @Test fun attemptLine_newFieldsPresentAndContentFree() = runTest {
        poke()
        val line = logs.last { it.startsWith("place attempt ") }
        for (k in listOf("fixSrc", "fixRealAgeB", "pinFirst", "weakWin", "retry", "hist", "textHit")) assertTrue(k, Regex("""\b$k=\S+""").containsMatchIn(line))
        assertTrue("fixSrc=fresh" in line)
        assertTrue(line.length < 1024)
        assertFalse(line.contains("Charminar"))
        assertFalse(Regex("""\d{5,}""").containsMatchIn(line.replace(Regex("""(ids|chosen)=\S+"""), "")))
        val ids = (1..12).map { place("wd:Q$it", "Place $it", north(it * 10.0)) }
        places = ids; poke()
        assertTrue(logs.last { it.startsWith("place attempt ") }.length < 1024)
    }

    // ---- S2: dedupe v2 ----
    private fun again(afterMs: Long, id: String) { now += afterMs; fresh(id) }
    private val match1 = PlaceMatchResult(PlaceMatchOutput("1", 0.86f), true)
    private val none = PlaceMatchResult(PlaceMatchOutput("NONE", 0.9f), true)
    private fun lastLine() = logs.last { it.startsWith("place attempt ") }

    @Test fun weakNone_doesNotConsumeCardDedupe() = runTest {
        v2 = true; reply = none; poke()
        assertEquals(PlaceOutcome.NONE, outcomes.last())
        reply = match1; again(31_000, "f2"); poke()
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertEquals(1, cards.size)
    }

    @Test fun notAdmitted_setsNoWindow() = runTest {
        v2 = true; admit = false; poke()
        assertEquals(PlaceOutcome.NOT_ADMITTED, outcomes.last()); assertEquals(null, flight.weak)
        admit = true; again(1_000, "f2"); poke()
        assertEquals(PlaceOutcome.MATCHED, outcomes.last())
    }

    @Test fun weakBackoff_doublesTo120sThenResetsOnMove() = runTest {
        v2 = true; reply = none; poke()
        again(25_000, "f2"); poke()
        assertEquals(PlaceOutcome.DUPLICATE, outcomes.last()); assertTrue(lastLine().contains("reason=weak_backoff") && lastLine().contains("weakWin=30"))
        again(10_000, "f3"); poke(); assertEquals(PlaceOutcome.NONE, outcomes.last()); assertEquals(2, flight.weak!!.streak)
        again(50_000, "f4"); poke(); assertEquals(PlaceOutcome.DUPLICATE, outcomes.last()); assertTrue(lastLine().contains("weakWin=60"))
        again(20_000, "f5"); poke(); assertEquals(PlaceOutcome.NONE, outcomes.last()); assertEquals(3, flight.weak!!.streak)
        assertEquals(120_000L, PlaceSampler.weakWindowMs(3)); assertEquals(120_000L, PlaceSampler.weakWindowMs(9))
        fix = PlaceFix(north(100.0), LON, 3_000, 10f); again(21_000, "f6"); poke()
        assertEquals(PlaceOutcome.NONE, outcomes.last()); assertEquals(1, flight.weak!!.streak)
    }

    @Test fun samePlaceWithin30m_cardSuppressedMemoryRevealed() = runTest {
        v2 = true; poke(); assertEquals(1, cards.size)
        again(121_000, "f2"); poke()
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertEquals(1, cards.size)
        assertTrue(lastLine().contains("card=suppressed cardWhy=already_shown_here")); assertTrue(lastLine().contains("reveal=ok"))
        assertEquals(2, memories.size)
    }

    @Test fun samePlaceAfter30min_cardsAgain() = runTest {
        v2 = true; poke(); again(31 * 60_000L, "f2"); poke()
        assertEquals(2, cards.size)
    }

    @Test fun movedAway_cardsAgain() = runTest {
        v2 = true; poke(); fix = PlaceFix(north(200.0), LON, 3_000, 10f); again(121_000, "f2"); poke()
        assertEquals(2, cards.size)
    }

    @Test fun seededFromHistory_suppressesAfterRestart() = runTest {
        v2 = true; seed = listOf("wd:Q1" to T0 - 60_000); poke()
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertTrue(cards.isEmpty()); assertTrue(lastLine().contains("card=suppressed"))
    }

    // ---- S3: F4 retry ----
    private val pinId = "user:00000000-0000-0000-0000-000000000001"
    private fun TestScope.retryNow(pin: String? = pinId, s: PlaceSampler = sampler(this)) { s.retryRecent(RetryReason.PIN_CREATED, pin); runCurrent() }

    @Test fun retryOnPin_bypassesSeenOnce() = runTest {
        retry = true; places = emptyList(); val s = sampler(this); poke(s)
        assertEquals(PlaceOutcome.NO_CANDIDATES, outcomes.last())
        places = listOf(place(pinId, "Test pin", radius = 30)); poke(s)
        assertEquals("the same frame without a retry is SEEN", PlaceOutcome.SEEN, outcomes.last())
        retryNow(s = s)
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertTrue(lastLine().contains("retry=pin_created")); assertEquals(1, cards.size)
    }

    @Test fun retryOnPin_notNear_noCall() = runTest {
        retry = true; retryNow("user:00000000-0000-0000-0000-0000000000ff")
        assertEquals(PlaceOutcome.NO_CANDIDATES, outcomes.last()); assertTrue(lastLine().contains("reason=retry_pin_not_near") && lastLine().contains("retry=pin_not_near")); assertEquals(0, matchCalls.size)
    }

    @Test fun retryOff_isNoOp() = runTest {
        retry = false; retryNow(); assertTrue(outcomes.isEmpty())
    }

    @Test fun retry_respectsZoneAndCallGap_delayedOnce() = runTest {
        retry = true; places = listOf(place(pinId, "Test pin", radius = 30)); poke()
        assertEquals(1, matchCalls.size)
        again(5_000, "f2"); retryNow()
        assertEquals(PlaceOutcome.RATE_LIMITED, outcomes.last()); assertEquals(1, matchCalls.size)
        now += 20_000; advanceTimeBy(20_001); runCurrent()
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertEquals(2, matchCalls.size)
        zone = true; retryNow(); assertEquals(PlaceOutcome.ZONE, outcomes.last()); assertEquals(2, matchCalls.size)
    }

    @Test fun packFailed_frameNotHandled_retriedNextPoke() = runTest {
        retry = true; places = emptyList(); cell = CellOutcome.FAILED; val s = sampler(this); poke(s)
        assertEquals(PlaceOutcome.NO_CANDIDATES, outcomes.last())
        places = listOf(place("wd:Q1", "Charminar")); cell = CellOutcome.FRESH; poke(s)
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertTrue(lastLine().contains("retry=cell_retry"))
    }

    @Test fun packFreshEmpty_staysHandled() = runTest {
        retry = true; places = emptyList(); cell = CellOutcome.FRESH; val s = sampler(this); poke(s); poke(s)
        assertEquals(PlaceOutcome.SEEN, outcomes.last())
    }

    @Test fun anActiveOrApproachingZoneNeverReadsAFixNorCallsTheNetworkOrTheModel() = runTest {
        zone = true; poke()
        assertEquals(listOf(PlaceOutcome.ZONE), outcomes)
        assertEquals(0, fixCalls); assertEquals(0, candidateCalls); assertEquals(0, matchCalls.size); assertTrue(cards.isEmpty()); assertTrue(memories.isEmpty())
    }

    // S2: the one-shot fix is judged against the zones itself.
    @Test fun aFixInsideAZoneNeverFetchesNorMatches() = runTest {
        zoneList = listOf(zoneAt(LAT, LON)); poke()
        assertEquals(listOf(PlaceOutcome.ZONE), outcomes); assertEquals(1, fixCalls)
        assertEquals(0, candidateCalls); assertEquals(0, matchCalls.size); assertTrue(cards.isEmpty()); assertTrue(memories.isEmpty())
    }

    @Test fun aFixWithinTheApproachMarginOfAZoneIsRefused() = runTest {
        zoneList = listOf(zoneAt(north(1_500.0), LON)); poke()
        assertEquals(listOf(PlaceOutcome.ZONE), outcomes); assertEquals(0, candidateCalls); assertEquals(0, matchCalls.size)
    }

    @Test fun anUnreadableZoneListRefusesTheFix() = runTest {
        zoneList = null; poke()
        assertEquals(listOf(PlaceOutcome.ZONE), outcomes); assertEquals(0, candidateCalls); assertEquals(0, matchCalls.size)
    }

    @Test fun aFixFarFromEveryZoneProceeds() = runTest {
        zoneList = listOf(zoneAt(north(30_000.0), LON)); poke()
        assertEquals(1, candidateCalls); assertEquals(1, matchCalls.size)
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

    @Test fun onlineButPhotosNotAllowedByTheDataSaverShowsTheNearbyCardWithNoCall() = runTest {
        photoOk = false; fix = PlaceFix(north(130.0), LON, 1_000, 10f); poke()
        assertEquals("no photo was matched", 0, matchCalls.size)
        assertEquals(listOf(PlaceOutcome.NEARBY), outcomes)
        assertEquals(PlaceCard("wd:Q1", "Charminar", "80 m away", "nearby · not checked", PlaceConfidence.NEARBY, "f1"), cards.single())
        assertTrue(memories.isEmpty())
        assertTrue(logs.last(), logs.last().contains("reason=nearby_card call=skipped_data_saver online=true"))
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
        assertTrue(logs.all { it.matches(Regex("place outcome=[A-Z_]+")) || it.startsWith("place attempt outcome=") })
    }

    // ---- trip diagnostics: the decision record of every attempt (ids, buckets, reasons; never content) ----

    private val PIN = "user:11111111-2222-3333-4444-555555555555"

    @Test fun aMatchedAttemptLogsIdsBucketsTimingsAndTheCardResultButNeverNamesNotesOrCoordinates() = runTest {
        places = listOf(place(PIN, "SECRET-NAME-XYZ", summary = "SECRET-NOTE-XYZ", kind = "user_pin"), place("wd:Q9", "Other Landmark", lat = north(300.0)))
        poke()
        val line = logs.single { it.startsWith("place attempt ") }
        assertTrue(line, line.contains("outcome=MATCHED reason=matched call=made online=true cell=FRESH cand=2 ids=$PIN,wd:Q9 "))
        assertTrue(line, line.contains("frameAgeS=5 fixAgeB=le5s accB=le10m ") && line.contains("chosen=$PIN distB=lt25m confPct=86 "))
        assertTrue(line, line.contains("card=shown cardWhy=- reveal=ok err=-"))
        assertTrue(line, Regex("candMs=\\d+ callMs=\\d+ totalMs=\\d+").containsMatchIn(line))
        val all = logs.joinToString("\n")
        for (secret in listOf("SECRET", "Other Landmark", "Charminar", "17.36", "78.47", "f1", "monument in")) assertFalse("log leaks $secret: $all", all.contains(secret))
        assertTrue("one line stays far under the 1 KiB cap", line.length < 700)
    }

    @Test fun everyRefusalNamesItsReasonAndStateOfTheRateLimit() = runTest {
        zone = true; poke()
        assertTrue(logs.last().contains("outcome=ZONE reason=zone_blocked "))
        zone = false; fix = null; fresh("f2"); poke()
        assertTrue(logs.last().contains("outcome=NO_FIX reason=fix_unavailable "))
        fix = PlaceFix(LAT, LON, 3_000, 80f); fresh("f3"); poke()
        assertTrue(logs.last().contains("outcome=STALE_FIX reason=fix_inaccurate ") && logs.last().contains("accB=le100m"))
        fix = PlaceFix(LAT, LON, 3_000, 10f); fresh("f4"); poke()      // matched: starts the 20 s gap and the dedupe memory
        now += 25_000; fresh("f5"); poke()
        assertTrue(logs.last(), logs.last().contains("outcome=DUPLICATE reason=same_place_recently ") && logs.last().contains("dedupeAgeS=25 dedupeDistB=lt25m"))
        now += 130_000; fresh("f6"); fix = PlaceFix(LAT, LON, 1_000, 10f); online = false; poke()
        assertTrue(logs.last(), logs.last().contains("outcome=NEARBY reason=nearby_card call=skipped_offline online=false") && logs.last().contains("chosen=wd:Q1 distB=lt25m") && logs.last().contains("card=shown"))
        online = true; admit = false; now += 25_000; fresh("f7"); fix = PlaceFix(north(900.0), LON, 1_000, 10f); poke()
        assertTrue(logs.last(), logs.last().contains("outcome=NOT_ADMITTED reason=not_admitted call=skipped_not_admitted"))
    }

    @Test fun cardWhenRevealFailsOrTheModelSaysNoneIsExplained() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("NONE", 0.2f), true); poke()
        assertTrue(logs.last(), logs.last().contains("outcome=NONE reason=model_none call=made") && logs.last().contains("confPct=20"))
        now += 25_000; fresh("f2"); fix = PlaceFix(north(300.0), LON, 1_000, 10f); reply = PlaceMatchResult(PlaceMatchOutput("1", 0.3f), true); poke()
        assertTrue(logs.last(), logs.last().contains("outcome=LOW_CONFIDENCE reason=confidence_below_50 ") && logs.last().contains("chosen=wd:Q1"))
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

/** Codex place v2 r1 P2: the reserved pin slots apply to the FINAL model list, after the sub-place expansion and the cut to 16. */
class PlaceSamplerPinSlotsTest {
    private val pinId = { n: Int -> "user:00000000-0000-0000-0000-%012d".format(n) }

    @Test fun threeReservedPinsSurviveFiveRootsAndTwelveNearerChildren() {
        // five public roots 100..140 m north, three pins 280..290 m away (farther), twelve public children of the roots standing right at the point (nearer than everything)
        val roots = (1..5).map { place("wd:R$it", "Root $it", lat = north(100.0 + it * 10), radius = 50) }
        val pins = (1..3).map { place(pinId(it), "Pin $it", lat = north(280.0 + it * 5), radius = 10, kind = "viewpoint") }
        val children = (1..12).map { place("wd:C$it", "Child $it", lat = north(1.0 * it), radius = 50, parent = "wd:R${(it % 5) + 1}") }
        val chosen = Geo.candidates(roots + pins, LAT, LON)
        assertEquals("the pack keeps the pins in the 8", 3, chosen.count { UserPlace.isUserId(it.place.id) })
        val expanded = Geo.expandSubPlaces(chosen.map { it.place }, children)
        assertTrue(expanded.size > PlaceSampler.MAX_LIST)
        val cut = PlaceSampler.cutKeepingPins(expanded.map { it to Geo.distanceM(LAT, LON, it.lat, it.lon) })
        assertEquals(PlaceSampler.MAX_LIST, cut.size)
        assertEquals("all three reserved pins are in the model list", setOf(pinId(1), pinId(2), pinId(3)), cut.map { it.first.id }.filter(UserPlace::isUserId).toSet())
        assertEquals("the list stays nearest edge first", cut.map { it.first.id }, PlaceSampler.cutKeepingPins(cut).map { it.first.id })
    }

    @Test fun withNoPinsTheCutIsTheNearest16() {
        val all = (1..20).map { place("wd:P$it", "P$it", lat = north(it * 5.0)) }
        assertEquals(all.take(16).map { it.id }, PlaceSampler.cutKeepingPins(all.map { it to Geo.distanceM(LAT, LON, it.lat, it.lon) }).map { it.first.id })
    }
}
