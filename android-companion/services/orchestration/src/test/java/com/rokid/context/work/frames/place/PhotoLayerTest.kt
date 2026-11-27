package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.retrieval.MultiFrameConfig
import com.rokid.context.memory.retrieval.MultiFrameQuery
import com.rokid.context.memory.retrieval.PhotoConfirm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Layer 1's production source over fakes: no embed without a reference, one embed with, multi-frame from stored vectors, hot phone, timeout. Content-free: only counts are asserted. */
class PhotoLayerTest {
    private val pin = "user:00000000-0000-0000-0000-000000000001"
    private fun place(id: String) = PlaceEntity(id, null, "N", "monument", 1.0, 1.0, 30, "s", "wikipedia:en", "p", 1L, false, false, 0, 100)
    private val hits = listOf(place(pin) to 0.0, place("wd:Q1") to 10.0)
    private var embeds = 0
    private val embedder = object : ImageTextEmbedder {
        override val spaceId = "sp"; override val dim = 2
        override suspend fun embedImage(bytes: ByteArray): FloatArray { embeds++; return floatArrayOf(1f, 0f) }
        override suspend fun embedText(text: String) = floatArrayOf(1f, 0f)
    }
    private fun at(milli: Int): FloatArray { val c = milli / 1000.0; return floatArrayOf(c.toFloat(), sqrt(1 - c * c).toFloat()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun layer(own: Map<String, List<FloatArray>> = emptyMap(), cent: Map<String, FloatArray> = emptyMap(), stored: FloatArray? = null, glance: Glance? = null, hot: Boolean = false,
                      multi: MultiFrameConfig? = MultiFrameConfig(), photo: ByteArray? = ByteArray(1), budget: Long = 3_000L, slow: Boolean = false, web: Map<String, FloatArray> = emptyMap(), webCalls: MutableList<List<String>>? = null) = PhotoLayer(
        embedder = { embedder }, photo = { photo }, ownRefs = { _, _, _ -> own }, centroids = { _, _ -> cent }, storedVector = { _, _ -> stored }, webRefs = { sp, ids -> webCalls?.add(ids); assertEquals("sp", sp); web.filterKeys { it in ids } }, glance = { _, _, _ -> if (slow) kotlinx.coroutines.delay(5_000); glance },
        multiFrame = { multi }, mayEmbed = { !hot }, scope = scope, budgetMs = budget,
    )

    private fun run(l: PhotoLayer) = runBlocking { l.score("f1", hits) { true } }

    @Test fun noReferenceMeansNoEmbedAndEveryPlaceStays() {
        val o = run(layer()) as PhotoLayerOutcome.Decided
        assertEquals(PhotoConfirm.Outcome.NO_REF, o.result.outcome); assertEquals(0, embeds); assertEquals(2, o.result.candidates.size)
    }

    @Test fun aPinReferenceIsScoredWithOneEmbedAndAcceptsAtHigh() {
        val o = run(layer(own = mapOf(pin to listOf(at(960))))) as PhotoLayerOutcome.Decided
        assertEquals(PhotoConfirm.Outcome.ACCEPT, o.result.outcome); assertEquals(pin, o.result.accepted!!.placeId); assertEquals(1, embeds); assertEquals(1, o.frames)
    }

    @Test fun theCentroidIsItsOwnKindWithItsOwnHigh() {
        val o = run(layer(cent = mapOf(pin to at(950)))) as PhotoLayerOutcome.Decided // centroid HIGH is 967: 950 is only medium
        assertEquals(PhotoConfirm.Outcome.MEDIUM, o.result.outcome)
    }

    @Test fun aStoredDwellVectorSavesTheEmbed() {
        run(layer(own = mapOf(pin to listOf(at(960))), stored = floatArrayOf(1f, 0f))); assertEquals(0, embeds)
    }

    @Test fun otherFramesOfTheGlanceAreAveragedWhenTheyAreSimilar() {
        val g = Glance(100_000L, listOf(MultiFrameQuery.Frame("a", 99_000L, at(950)), MultiFrameQuery.Frame("b", 98_000L, at(500))), moved = false)
        val o = run(layer(own = mapOf(pin to listOf(at(960))), glance = g)) as PhotoLayerOutcome.Decided
        assertEquals("the dwell frame and the one similar frame", 2, o.frames)
        val moved = run(layer(own = mapOf(pin to listOf(at(960))), glance = Glance(100_000L, g.others, moved = true))) as PhotoLayerOutcome.Decided
        assertEquals(1, moved.frames)
        val off = run(layer(own = mapOf(pin to listOf(at(960))), glance = g, multi = null)) as PhotoLayerOutcome.Decided
        assertEquals("multi-frame switched off", 1, off.frames)
    }

    @Test fun aHotPhoneSkipsTheEmbedButStillUsesAStoredVector() {
        val skipped = run(layer(own = mapOf(pin to listOf(at(960))), hot = true)) as PhotoLayerOutcome.Skipped
        assertEquals("hot", skipped.token); assertEquals(0, embeds)
        assertTrue(run(layer(own = mapOf(pin to listOf(at(960))), hot = true, stored = floatArrayOf(1f, 0f))) is PhotoLayerOutcome.Decided)
    }

    @Test fun aSlowStepIsBoundedByTheBudget() {
        val o = run(layer(own = mapOf(pin to listOf(at(960))), slow = true, budget = 100L)) as PhotoLayerOutcome.Skipped
        assertEquals("timeout", o.token)
    }

    @Test fun aFencedAttemptDoesNothing() {
        val o = runBlocking { layer(own = mapOf(pin to listOf(at(960)))).score("f1", hits) { false } } as PhotoLayerOutcome.Skipped
        assertEquals("fenced", o.token); assertEquals(0, embeds)
    }

    @Test fun aGoneReferencePhotoDropsTheResult() {
        val l = PhotoLayer(embedder = { embedder }, photo = { ByteArray(1) }, ownRefs = { _, _, _ -> mapOf(pin to listOf(at(960))) }, refsStillValid = { _, _ -> false }, scope = scope)
        assertEquals("reference_gone", (runBlocking { l.score("f1", hits) { true } } as PhotoLayerOutcome.Skipped).token)
    }

    // ---- B1: WEB references from place_image ----

    @Test fun aSeededPlaceWithOneWebVectorGetsAWebScore() {
        val o = run(layer(web = mapOf("wd:Q1" to at(960)))) as PhotoLayerOutcome.Decided
        assertEquals(PhotoConfirm.Outcome.ACCEPT, o.result.outcome); assertEquals("wd:Q1", o.result.accepted!!.placeId); assertEquals(com.rokid.context.memory.retrieval.RefKind.WEB, o.result.accepted!!.kind); assertEquals(1, embeds)
    }

    @Test fun aWebScoreBelowItsHighIsOnlyMediumAndNothingIsAccepted() {
        val o = run(layer(web = mapOf("wd:Q1" to at(900)))) as PhotoLayerOutcome.Decided // WEB high is 945
        assertTrue(o.result.accepted == null); assertEquals(2, o.result.candidates.size)
    }

    @Test fun noPhotoForAnyCandidateKeepsEveryPlaceInTheListWithoutAnEmbed() {
        val calls = mutableListOf<List<String>>()
        val o = run(layer(webCalls = calls)) as PhotoLayerOutcome.Decided
        assertEquals(PhotoConfirm.Outcome.NO_REF, o.result.outcome); assertEquals(2, o.result.candidates.size); assertEquals(0, embeds)
        assertEquals("only the seeded id is looked up, never a pin", listOf(listOf("wd:Q1")), calls)
    }

    @Test fun aPlaceWithAPhotoDoesNotRemoveAPlaceWithoutOne() {
        val o = run(layer(web = mapOf("wd:Q1" to at(500)))) as PhotoLayerOutcome.Decided
        assertEquals("the pin has no reference and stays", 2, o.result.candidates.size)
    }
}
