package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.retrieval.CandidateScorer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun place(id: String, name: String = "Charminar", kind: String = "monument", summary: String = "A 1591 mosque and monument.", r: Int = 50) =
    PlaceEntity(id, null, name, kind, 17.36, 78.47, r, summary, "wikipedia:en", "p:1", 1L, false, false, 0, 100)

/** 1.4.6 pack Moments: one candidate interface for pins and pack places; shadow only; counts only. */
class PackMomentsTest {
    @After fun reset() { PackMomentStats.clear() }

    private val hits = listOf(place("wd:Q1") to 120.0, place("user:abc", name = "Home", kind = "home", summary = "") to 10.0, place("osm:w9", name = "Fort", summary = "") to 400.0)

    @Test fun pinsAndPackPlacesBothBecomeMomentCandidates() {
        val c = MomentCandidates.of(hits, packMoments = true)
        assertEquals(listOf(MomentOrigin.PACK, MomentOrigin.PIN, MomentOrigin.PACK), c.map { it.origin })
        assertEquals(listOf("wd:Q1", "user:abc", "osm:w9"), c.map { it.id })
        assertEquals(70.0, c[0].edgeGapM, 0.0001); assertEquals(0.0, c[1].edgeGapM, 0.0001)
    }

    @Test fun flagOffKeepsTheOlderPackText_flagOnAddsTheSummary() {
        val off = MomentCandidates.of(hits, packMoments = false)[0].anchorText
        val on = MomentCandidates.of(hits, packMoments = true)[0].anchorText
        assertEquals(PlaceShadow.anchorText(hits[0].first), off)
        assertEquals("Charminar. monument. A 1591 mosque and monument.", on)
        assertEquals("a pack place without a summary keeps the name and kind", PlaceShadow.anchorText(hits[2].first), MomentCandidates.of(hits, true)[2].anchorText)
        assertEquals(300, PackMoment.richText(place("wd:Q2", summary = "x".repeat(400))).substringAfterLast(". ").length)
    }

    @Test fun onlyPackTextIsCacheable_aPinNameNeverIs() {
        val c = MomentCandidates.of(hits, true)
        assertTrue(c[0].cacheableText); assertFalse(c[1].cacheableText)
    }

    @Test fun theSameScorerInputComesOutOfBothKinds() {
        val v = floatArrayOf(1f, 0f)
        val img = floatArrayOf(0.5f, 0.5f)
        val c = MomentCandidates.of(hits, true)
        val pinCand = c[1].toScorer(listOf(img), null); val packCand = c[0].toScorer(emptyList(), v)
        assertEquals("user:abc", pinCand.id); assertEquals(1, pinCand.imageRefs.size); assertNull(pinCand.textAnchor)
        assertEquals("wd:Q1", packCand.id); assertTrue(packCand.imageRefs.isEmpty()); assertNotNull(packCand.textAnchor)
        assertEquals(CandidateScorer.Channel.TEXT, CandidateScorer.score(v, listOf(packCand)).channel)
    }

    private class FakeEmbedder : ImageTextEmbedder {
        val texts = ArrayList<String>()
        override val spaceId = "fake"
        override val dim = 2
        override suspend fun embedImage(bytes: ByteArray) = floatArrayOf(1f, 0f)
        override suspend fun embedText(text: String) = floatArrayOf(1f, 0f)
        override suspend fun embedTexts(texts: List<String>): List<FloatArray> { this.texts += texts; return texts.map { floatArrayOf(1f, 0f) } }
    }

    private fun shadow(e: FakeEmbedder, on: Boolean, counts: MutableList<Pair<Int, Int>>) =
        PlaceShadow(embedder = { e }, photo = { ByteArray(1) }, elapsed = { 0L }, packMoments = { on }, onMoments = { p, n -> counts += p to n })

    @Test fun flagOffReportsNothingAndEmbedsTheOlderText() = runBlocking {
        val e = FakeEmbedder(); val counts = ArrayList<Pair<Int, Int>>()
        val out = shadow(e, false, counts).score("f1", hits)
        assertEquals(ShadowOutcome.Kind.SCORED, out.kind)
        assertTrue(counts.isEmpty())
        assertTrue(e.texts.none { "1591" in it })
    }

    @Test fun flagOnReportsCountsOnlyAndEmbedsTheRichText_resultStillScored() = runBlocking {
        val e = FakeEmbedder(); val counts = ArrayList<Pair<Int, Int>>()
        val out = shadow(e, true, counts).score("f1", hits)
        assertEquals(ShadowOutcome.Kind.SCORED, out.kind)
        assertEquals(listOf(2 to 1), counts)
        assertTrue(e.texts.any { "1591" in it })
        assertFalse("a pin's text never gets the summary", e.texts.any { it.startsWith("Home") && it.length > "Home. home".length })
    }

    @Test fun flagOnIsAMatchingResultOnly_noCardOrCallHookExists() {
        // PlaceShadow returns a ShadowOutcome: scores and ids for the sampler's own redacted log; it has no card, model or sender dependency.
        val src = srcOf("work/frames/place/PlaceShadow.kt") + srcOf("work/frames/place/PackMoments.kt")
        listOf("PlaceCard(", "ModelRouter", "RoleCall", "sendToGlasses", "okhttp", "java.net", "HttpURLConnection", "Room", "MomentEntity", "MomentDao", "ZoneFence", "ZoneTicket").forEach { assertFalse("no $it", it in src) }
    }

    @Test fun aPackMomentIsNeverAUserMemoryOrAPin() {
        val c = MomentCandidates.of(hits, true).filter { it.origin == MomentOrigin.PACK }
        assertTrue(c.all { !com.rokid.context.memory.place.UserPlace.isUserId(it.id) })
        val src = srcOf("work/frames/place/PackMoments.kt")
        assertFalse("no export or memory write", Regex("""(?i)export|MemoryEntity|insert|\.upsert""").containsMatchIn(src.lines().filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/**") }.joinToString("\n")))
    }

    @Test fun statsAreCountsOnlyAndForgetResetsThem() {
        PackMomentStats.record(3, 2)
        assertEquals("place pack moments v=1 pack=3 pin=2", PackMomentStats.line(3, 2))
        assertEquals("Last attempt: 3 pack and 2 pin candidates; 1 attempts since start or last Forget.", PackMomentStats.summary())
        ShadowCaches.resetForTests(); ShadowCaches.register(ShadowCaches.DERIVED_ITEMS[2], PackMomentStats::clear)
        ShadowCaches.clearAll()
        assertEquals("Last attempt: 0 pack and 0 pin candidates; 0 attempts since start or last Forget.", PackMomentStats.summary())
        ShadowCaches.resetForTests()
    }

    @Test fun theFlagIsOffByDefaultAndWiredToTheShadow() {
        assertEquals("packMoments", com.rokid.context.orchestration.forget.DeveloperFlags.PACK_MOMENTS)
        val samplers = srcOf("work/frames/place/PlaceSamplers.kt")
        assertTrue("DeveloperFlags.packMoments(app)" in samplers && "ShadowCaches.DERIVED_ITEMS[2]" in samplers)
        val flags = srcOf("orchestration/forget/ForgetShadow.kt")
        assertTrue(Regex("""fun packMoments\(.*getBoolean\(PACK_MOMENTS, false\).*getOrDefault\(false\)""").containsMatchIn(flags))
        assertTrue("OFF clears the in-memory data", Regex("""fun setPackMoments\(.*ShadowCaches.clearAll\(\)""").containsMatchIn(flags))
    }

    private fun srcOf(rel: String): String {
        val base = listOf("src/main/java/com/rokid/context/", "services/orchestration/src/main/java/com/rokid/context/").map { File(it + rel) }.firstOrNull { it.exists() } ?: error("no $rel")
        return base.readText()
    }
}
