package com.rokid.context.work.frames.place

import com.rokid.context.core.model.vision.CaptionRefusal
import com.rokid.context.core.model.vision.CaptionRefused
import com.rokid.context.core.model.vision.OfflineCaptioner
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun pl(id: String, name: String, kind: String = "monument", summary: String = "A very long stored summary that must never reach the text embedder.") =
    PlaceEntity(id, null, name, kind, 1.0, 2.0, 40, summary, "wikipedia:en", "p", 0, false, false, 0, 10)

/** V2b: the pure offline tier with a fake captioner and a fake embedder. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineTierTest {
    private class Cap(var result: Result<String> = Result.success("a museum gallery with paintings")) : OfflineCaptioner {
        var captions = 0; var releases = 0; val paths = mutableListOf<String>()
        override suspend fun caption(jpegPath: String): Result<String> { captions++; paths += jpegPath; return result }
        override suspend fun release() { releases++ }
    }

    /** One axis per word: the caption "museum" is close to a place text that contains "museum". */
    private class Emb : TextEmbed {
        val seen = mutableListOf<List<String>>()
        var fail = false
        override suspend fun embed(texts: List<String>): List<FloatArray>? {
            seen += texts; if (fail) return null
            return texts.map { t -> floatArrayOf(if ("museum" in t) 1f else 0f, if ("fort" in t) 1f else 0f, if ("mosque" in t) 1f else 0f, 0.5f) }
        }
    }

    private var on = true; private var now = 1_000_000L; private var cutoff = 0.55f
    private val cap = Cap(); private val emb = Emb()
    private fun tier() = OfflineTier(cap, emb, { on }, { cutoff }, { now }, kotlinx.coroutines.Dispatchers.Unconfined)
    private val museum = OfflineCandidate(pl("wd:Q1", "Salar Jung Museum", "museum"))
    private val fort = OfflineCandidate(pl("wd:Q2", "Golconda Fort", "fort"))

    @Test fun theBestCandidateAboveTheCutoffIsTheOneResult() = runTest {
        val r = tier().guess("/x/p.jpg", listOf(fort, museum))
        assertEquals("wd:Q1", r.place?.id); assertTrue(r.token, r.token.startsWith("maybe(n=2,"))
        assertEquals(1, cap.captions); assertEquals(listOf("/x/p.jpg"), cap.paths)
    }

    @Test fun belowTheCutoffIsNoGuess() = runTest {
        cap.result = Result.success("a living room with a sofa")
        val r = tier().guess("/p", listOf(fort, museum))
        assertNull(r.place); assertTrue(r.token, r.token.startsWith("below("))
    }

    @Test fun theCutoffIsRespectedAndClamped() = runTest {
        cap.result = Result.success("a museum next to a fort") // cosine 0.745 with the museum text
        val t = tier()
        cutoff = 0.80f; assertNull(t.guess("/p", listOf(museum)).place)
        now += OfflineTier.RATE_LIMIT_MS; cutoff = 0.70f; assertNotNull(t.guess("/p", listOf(museum)).place)
        now += OfflineTier.RATE_LIMIT_MS; cutoff = 0.99f; assertNull("clamped to 0.90", t.guess("/p", listOf(museum)).place)
        now += OfflineTier.RATE_LIMIT_MS; cutoff = 0.05f; assertNotNull("clamped to 0.30", t.guess("/p", listOf(museum)).place)
    }

    @Test fun onlyOneResultEvenWithManyAboveTheCutoff() = runTest {
        val twin = OfflineCandidate(pl("wd:Q3", "Another Museum", "museum"))
        val r = tier().guess("/p", listOf(museum, twin, fort))
        assertNotNull(r.place); assertTrue(r.token.contains("n=3"))
    }

    @Test fun noCandidatesMeansNoGuessAndNothingRuns() = runTest {
        val r = tier().guess("/p", emptyList())
        assertNull(r.place); assertEquals("no_candidates", r.token); assertEquals(0, cap.captions); assertEquals(0, cap.releases)
    }

    @Test fun flagsOffMeansNothingRuns() = runTest {
        on = false
        val r = tier().guess("/p", listOf(museum))
        assertNull(r.place); assertEquals("off", r.token); assertEquals(0, cap.captions); assertEquals(0, cap.releases); assertTrue(emb.seen.isEmpty())
    }

    @Test fun oneCaptionPerSixtySeconds() = runTest {
        val t = tier()
        assertNotNull(t.guess("/p", listOf(museum)).place)
        now += 59_999; assertEquals("rate_limited", t.guess("/p", listOf(museum)).token); assertTrue(t.rateLimited())
        assertEquals(1, cap.captions)
        now += 1; assertFalse(t.rateLimited()); assertNotNull(t.guess("/p", listOf(museum)).place); assertEquals(2, cap.captions)
    }

    @Test fun theModelIsReleasedAfterEveryCaptionEvenARefusedOrFailedOne() = runTest {
        val t = tier()
        t.guess("/p", listOf(museum)); assertEquals(1, cap.releases)
        now += 60_000; cap.result = Result.failure(CaptionRefused(CaptionRefusal.THERMAL)); t.guess("/p", listOf(museum)); assertEquals(2, cap.releases)
        now += 60_000; cap.result = Result.failure(IllegalStateException("x")); t.guess("/p", listOf(museum)); assertEquals(3, cap.releases)
    }

    @Test fun aRefusalIsNoGuessAndDoesNotSpendTheRateSlot() = runTest {
        val t = tier()
        cap.result = Result.failure(CaptionRefused(CaptionRefusal.BATTERY_LOW))
        val r = t.guess("/p", listOf(museum))
        assertNull(r.place); assertEquals("refused_battery_low", r.token); assertTrue(emb.seen.isEmpty())
        cap.result = Result.success("museum"); assertNotNull(t.guess("/p", listOf(museum)).place) // thermal and battery refusals may be retried at once
    }

    @Test fun anEmbedderThatIsNotAvailableIsNoGuess() = runTest {
        emb.fail = true
        assertEquals("failed", tier().guess("/p", listOf(museum)).token)
    }

    @Test fun anEmptyCaptionIsNoGuess() = runTest {
        cap.result = Result.success("  ")
        assertEquals("failed", tier().guess("/p", listOf(museum)).token)
    }

    @Test fun candidateTextIsNameKindAndDescription() = runTest {
        tier().guess("/p", listOf(museum))
        val texts = emb.seen.single()
        assertEquals("a museum gallery with paintings", texts[0]); assertEquals("Salar Jung Museum. museum. A very long stored summary that must never reach the text embedder.", texts[1])
        assertTrue(OfflineCandidate(pl("wd:Q9", "N", "fort", "x".repeat(900))).text().length <= 300)
    }

    /** Layer 3 (owner spec 28 Nov): a caption that matches only the DESCRIPTION ranks the place above one whose name alone is a worse match. */
    @Test fun theDescriptionRaisesTheRankOfAMatchingPlace() = runTest {
        cap.result = Result.success("a museum gallery")
        val named = OfflineCandidate(pl("wd:Q5", "Heritage Hall", "building", "")) // name only: no 'museum' word anywhere
        val described = OfflineCandidate(pl("wd:Q6", "Heritage Hall", "building", "A museum of old paintings."))
        val r = tier().guess("/p", listOf(named, described))
        assertEquals("the described twin wins", "wd:Q6", r.place?.id)
        now += OfflineTier.RATE_LIMIT_MS
        val nameOnly = tier().guess("/p", listOf(named))
        assertNull("without the description the same place stays below the cut-off", nameOnly.place)
    }

    @Test fun aPinUsesItsNameAndNote() {
        val pin = OfflineCandidate(pl("user:00000000-0000-0000-0000-000000000001", "Grandma's kitchen", "user_pin", "Blue tiles and a clay stove"))
        assertEquals("Grandma's kitchen. Blue tiles and a clay stove", pin.text())
    }

    @Test fun theResultAndItsTokenNeverCarryTheCaptionOrAName() = runTest {
        cap.result = Result.success("SECRET CAPTION museum")
        val r = tier().guess("/p", listOf(museum, fort))
        assertFalse(r.token.contains("SECRET")); assertFalse(r.token.contains("Salar")); assertTrue(Regex("""[a-z_]+(\(n=\d+,score=\w+,margin=\w+\))?""").matches(r.token))
    }

    // ---- candidate selection ----

    private fun near(vararg d: Double) = d.mapIndexed { i, m -> pl("wd:N$i", "N$i") to m }

    @Test fun nearbyWithinTheRadiusOnlyAndNearestFirst() {
        val c = OfflineTier.select(emptyList(), near(140.0, 30.0, 400.0, 90.0, 410.0, 420.0, 430.0, 600.0)) // 500 m would hold 7: no widening
        assertEquals(listOf("wd:N1", "wd:N3", "wd:N0"), c.map { it.place.id })
    }

    @Test fun widensToFiveHundredOnlyWhileTheListStaysAtFiveOrFewer() {
        assertEquals(4, OfflineTier.select(emptyList(), near(20.0, 200.0, 300.0, 450.0, 900.0)).size) // 150 m gives 1, 500 m gives 4
        assertEquals(1, OfflineTier.select(emptyList(), near(20.0, 200.0, 300.0, 450.0, 480.0, 490.0)).size) // 500 m would give 6 > 5: stays at the 150 m list
        assertEquals(0, OfflineTier.select(emptyList(), near(600.0)).size)
    }

    @Test fun theRadiusSettingMovesTheBandAndIsClamped() {
        val d = near(40.0, 80.0, 300.0, 310.0, 320.0, 330.0, 340.0)
        assertEquals(listOf("wd:N0"), OfflineTier.select(emptyList(), d, radiusM = 50).map { it.place.id })
        assertEquals(listOf("wd:N0", "wd:N1"), OfflineTier.select(emptyList(), d, radiusM = 100).map { it.place.id })
        assertEquals("below the range reads as the smallest radius", 1, OfflineTier.select(emptyList(), d, radiusM = 10).size)
        assertEquals("above the range reads as 500 m", 7, OfflineTier.select(emptyList(), d, radiusM = 900).size)
    }

    @Test fun mediumPlacesAreAlwaysKeptAndDeduplicated() {
        val m = pl("wd:M", "M")
        val c = OfflineTier.select(listOf(m), listOf(m to 10.0, pl("wd:N", "N") to 20.0))
        assertEquals(listOf("wd:M", "wd:N"), c.map { it.place.id })
    }

    @Test fun neverMoreThanTheCapOfCandidates() {
        assertEquals(OfflineTier.MAX_CANDIDATES, OfflineTier.select(emptyList(), (1..40).map { pl("wd:$it", "P$it") to 5.0 }).size)
    }

    @Test fun defaultsAreTheOwnersNumbers() {
        assertEquals(0.55f, OfflineTier.DEFAULT_CUTOFF); assertEquals(150, OfflineTier.DEFAULT_RADIUS_M); assertEquals(60_000L, OfflineTier.RATE_LIMIT_MS)
    }
}
