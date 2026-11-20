package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.retrieval.CandidateScorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun pin(id: String) = PlaceEntity(id, null, "My Desk", "other", 17.36, 78.47, 50, "s", "user", "p:1", 1L, false, false, 0, 100)
private fun pack(id: String, name: String) = PlaceEntity(id, null, name, "monument", 17.36, 78.47, 50, "s", "wikipedia:en", "p:1", 1L, false, false, 0, 100)

/** Slice 1.4.4 B1: the pin reference vector cache. Every trigger of the plan's list, the gates and the "nothing stored" rule. */
@OptIn(ExperimentalCoroutinesApi::class)
class PinReferenceVectorsTest {
    private class Emb(override val spaceId: String = "sp") : ImageTextEmbedder {
        override val dim = 2
        var images = 0; var failNext = false; var delayMs = 0L
        override suspend fun embedImage(bytes: ByteArray): FloatArray { images++; if (delayMs > 0) delay(delayMs); if (failNext) throw IllegalStateException("x"); return floatArrayOf(0.6f, 0.8f) }
        override suspend fun embedText(text: String) = floatArrayOf(1f, 0f)
    }

    private var links = mutableMapOf("user:a" to "00000001")
    private var frames = mutableMapOf("00000001" to "f1")
    private var gen = 0L; private var zone = false; private var power = true; private var now = 0L
    private var reads = mutableListOf<String>()
    private val e = Emb()

    private fun refs(scope: TestScope, max: Int = 16) = PinReferenceVectors(
        pinMoments = { links.toMap() }, momentFrame = { frames[it] }, photo = { reads += it; ByteArray(2) }, mayEmbed = { power }, zoneBlocked = { zone }, generation = { gen },
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), elapsed = { now }, max = max, waitMs = 800L,
    )

    @Test fun aMissIsEmbeddedOnceThenServedFromTheCache() = runTest {
        val r = refs(this)
        assertEquals(1, r.vectorsFor(pin("user:a"), e).size)
        assertEquals(1, r.vectorsFor(pin("user:a"), e).size)
        assertEquals(1, e.images); assertEquals(listOf("f1"), reads)
    }

    @Test fun noLinkMeansNoReferenceAndNoRead() = runTest {
        links.clear(); val r = refs(this)
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty()); assertTrue(reads.isEmpty()); assertEquals(0, e.images)
    }

    @Test fun aForgottenOrMissingMomentGivesNoReferenceAndNeverReadsThePhoto() = runTest {
        frames.clear(); val r = refs(this)
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty()); assertTrue(reads.isEmpty())
    }

    @Test fun forgettingTheMomentDropsTheCachedVectorAtOnce() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e); assertEquals(1, r.size())
        frames.clear() // the tombstone / the photo is gone; nobody notified anyone
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty()); assertEquals(0, r.size())
    }

    @Test fun clearingTheLinkDropsIt() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e)
        links.clear()
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty()); assertEquals(0, r.size())
    }

    @Test fun aDifferentMomentIsAMissAndTheOldEntryIsDropped() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e)
        links["user:a"] = "00000002"; frames["00000002"] = "f2"
        assertEquals(1, r.vectorsFor(pin("user:a"), e).size)
        assertEquals(listOf("f1", "f2"), reads); assertEquals(1, r.size())
    }

    @Test fun theSamePhotoReplacedUnderTheSameMomentIsAMiss() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e)
        frames["00000001"] = "f9"
        r.vectorsFor(pin("user:a"), e); assertEquals(listOf("f1", "f9"), reads)
    }

    @Test fun aWipeGenerationChangeEmptiesItAndTheNextUseEmbedsAgain() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e)
        gen = 1; links.clear(); r.vectorsFor(pin("user:a"), e); assertEquals(0, r.size())
        links["user:a"] = "00000001"; r.vectorsFor(pin("user:a"), e); assertEquals(2, e.images)
    }

    @Test fun aSpaceChangeEmptiesIt() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e)
        val other = Emb("other"); r.vectorsFor(pin("user:a"), other)
        assertEquals(1, other.images); assertEquals(1, r.size())
    }

    @Test fun theCacheIsBounded() = runTest {
        val r = refs(this, max = 2)
        for (i in 1..4) { links["user:$i"] = "0000000$i"; frames["0000000$i"] = "f$i"; r.vectorsFor(pin("user:$i"), e) }
        assertEquals(2, r.size())
    }

    @Test fun aZoneBlocksTheReadOfThePhoto() = runTest {
        zone = true; val r = refs(this)
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty()); advanceUntilIdle(); assertTrue(reads.isEmpty()); assertEquals(0, e.images); assertEquals(0, r.size())
    }

    @Test fun aWipeDuringTheEmbedStoresNothingAndReturnsNothing() = runTest {
        e.delayMs = 100; val r = refs(this)
        val job = async { r.vectorsFor(pin("user:a"), e) }
        yield(); gen = 1 // the wipe lands while the model runs
        assertTrue(job.await().isEmpty()); advanceUntilIdle(); assertEquals(0, r.size())
    }

    @Test fun aClosedFenceGivesNothingAndEmbedsNothing() = runTest {
        val r = refs(this)
        assertTrue(r.vectorsFor(pin("user:a"), e) { false }.isEmpty()); advanceUntilIdle(); assertEquals(0, e.images)
    }

    @Test fun thePowerGateStopsANewEmbedButNotTheCache() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e)
        power = false
        assertEquals(1, r.vectorsFor(pin("user:a"), e).size) // cached: served
        links["user:b"] = "00000002"; frames["00000002"] = "f2"
        assertTrue(r.vectorsFor(pin("user:b"), e).isEmpty()); advanceUntilIdle(); assertEquals(1, e.images)
    }

    @Test fun aFailedEmbedIsNotRetriedEveryAttempt() = runTest {
        e.failNext = true; val r = refs(this)
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty()); advanceUntilIdle()
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty()); assertEquals(1, e.images)
        now = PinReferenceVectors.FAIL_BACKOFF_MS + 1; e.failNext = false
        assertEquals(1, r.vectorsFor(pin("user:a"), e).size); assertEquals(2, e.images)
    }

    @Test fun aSlowFirstEmbedDoesNotHoldTheShadowButLandsInTheCacheForTheNextAttempt() = runTest {
        e.delayMs = 5_000; val r = refs(this)
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty()) // waited waitMs only
        advanceUntilIdle(); assertEquals(1, r.size())
        assertEquals(1, r.vectorsFor(pin("user:a"), e).size); assertEquals(1, e.images)
    }

    @Test fun nothingIsPersisted() {
        // The class takes no Context, no DAO and no file or prefs: its only constructor inputs are read lambdas (links, frame lookup, photo bytes), a gate and a scope, so it has nothing to write to.
        val ctor = PinReferenceVectors::class.java.declaredConstructors.maxByOrNull { it.parameterTypes.size }!!
        assertFalse(ctor.parameterTypes.map { it.name }.any { it.contains("Context") || it.contains("SharedPreferences") || it.contains("Dao") || it.contains("Database") || it.contains("File") })
        assertFalse(PinReferenceVectors::class.java.declaredFields.map { it.type.name }.any { it.contains("Context") || it.contains("SharedPreferences") || it.contains("File") || it.contains("Dao") })
        // and the source has no Room, file or prefs write
        val src = java.io.File("src/main/java/com/rokid/context/work/frames/place/PinReferenceVectors.kt").takeIf { it.exists() }?.readText()
        assertTrue("the source must be found from the module dir", src != null)
        listOf("getSharedPreferences", "openFileOutput", "FileOutputStream", "writeBytes", "@Insert", "withTransaction", ".edit()").forEach { assertFalse(it, src!!.contains(it)) }
    }

    @Test fun scoringUsesTheImageChannelForPinsAndLeavesPackPlacesOnText() = runTest {
        val r = refs(this)
        val sh = PlaceShadow(embedder = { e }, photo = { ByteArray(1) }, imageRefs = r::vectorsFor, elapsed = { 0L }, generation = { gen })
        val hits = listOf(pin("user:a") to 10.0, pack("wd:Q1", "Charminar") to 12.0)
        val out = sh.score("f", hits)
        assertEquals(CandidateScorer.Channel.IMAGE, out.result!!.channel)
        // Fake photo vector (0.6,0.8) is also the pin reference => 1000; the pack place is not ranked on the image channel.
        assertEquals(listOf("user:a"), out.result!!.ranked.map { it.id }); assertEquals(1000, out.result!!.top1!!.milli)
        links.clear() // no pin has a reference any more: the pack place keeps its text anchor
        assertEquals(CandidateScorer.Channel.TEXT, sh.score("f", hits).result!!.channel)
    }

    @Test fun noPinCandidateNeverAsksForAReference() = runTest {
        var asked = 0
        val sh = PlaceShadow(embedder = { e }, photo = { ByteArray(1) }, imageRefs = { _, _, _ -> asked++; emptyList() }, elapsed = { 0L })
        sh.score("f", listOf(pack("wd:Q1", "Charminar") to 1.0)); assertEquals(0, asked)
    }
}
