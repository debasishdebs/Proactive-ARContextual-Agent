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
import kotlinx.coroutines.test.advanceTimeBy
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
        var images = 0; var failNext = false; var delayMs = 0L; var onEmbed: () -> Unit = {}
        override suspend fun embedImage(bytes: ByteArray): FloatArray { images++; onEmbed(); if (delayMs > 0) delay(delayMs); if (failNext) throw IllegalStateException("x"); return floatArrayOf(0.6f, 0.8f) }
        override suspend fun embedText(text: String) = floatArrayOf(1f, 0f)
    }

    private var links = mutableMapOf("user:a" to "00000001")
    private var frames = mutableMapOf("00000001" to "f1")
    private var gen = 0L; private var zone = false; private var power = true; private var now = 0L
    private var reads = mutableListOf<String>()
    private val e = Emb()

    private var momentFrameCalls = 0; private var dropFramesAtCall = -1; private var pinMomentsHook: () -> Unit = {}

    private fun refs(scope: TestScope, max: Int = 16) = PinReferenceVectors(
        pinMoments = { pinMomentsHook(); links.toMap() }, momentFrame = { val v = frames[it]; if (++momentFrameCalls == dropFramesAtCall) frames.clear(); v }, photo = { reads += it; ByteArray(2) }, mayEmbed = { power }, zoneBlocked = { zone }, generation = { gen },
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
        advanceTimeBy(50); gen = 1 // the wipe lands while the model runs
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
        val sh = PlaceShadow(embedder = { e }, photo = { ByteArray(1) }, imageRefs = r::vectorsForAll, refsStillValid = r::stillValid, elapsed = { 0L }, generation = { gen })
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
        val sh = PlaceShadow(embedder = { e }, photo = { ByteArray(1) }, imageRefs = { _, _, _ -> asked++; emptyMap() }, elapsed = { 0L })
        sh.score("f", listOf(pack("wd:Q1", "Charminar") to 1.0)); assertEquals(0, asked)
    }

    // ---- review follow-ups (slice B1 P2s) ----

    @Test fun aWipeLandingJustBeforeTheStoreStoresNothing() = runTest {
        e.delayMs = 0; val r = refs(this)
        // The wipe lands after every check outside the lock has passed (the first pinMoments call after the embed is the post-embed check): only the check INSIDE the lock can catch it.
        e.onEmbed = { pinMomentsHook = { gen = 1; pinMomentsHook = {} } }
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty()); advanceUntilIdle(); assertEquals(0, r.size())
        assertEquals("a stale-generation vector is not stored", 0, r.size())
    }

    @Test fun aStaleStoreNeverClearsNewerEntries() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e); assertEquals(1, r.size())
        gen = 1; links["user:b"] = "00000002"; frames["00000002"] = "f2"; links.remove("user:a")
        r.vectorsFor(pin("user:b"), e); assertEquals("the new generation's entry is kept", 1, r.size())
    }

    @Test fun aCachedVectorIsDroppedOnAGenerationChangeBeforeAnythingElseIsAsked() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e); assertEquals(1, r.size())
        gen = 1; links.clear(); momentFrameCalls = 0
        assertTrue(r.vectorsFor(pin("user:a"), e).isEmpty())
        assertEquals(0, r.size()); assertEquals("no photo lookup was needed for it", 0, momentFrameCalls)
    }

    @Test fun aSingleMomentForgetBetweenTheCheckAndTheCachedHitGivesNothing() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e); assertEquals(1, e.images)
        // calls so far: 1 + 2 (embed gates) = 3; the next vectorsFor asks revalidate (4), the first lookup (5) and the recheck before the cached vector leaves (6)
        dropFramesAtCall = momentFrameCalls + 2
        assertTrue("the Forget landed after the first check: the cached vector is not returned", r.vectorsFor(pin("user:a"), e).isEmpty())
    }

    @Test fun aForgetDuringTheEmbedGivesNothingAndStoresNothing() = runTest {
        e.delayMs = 100; val r = refs(this)
        val job = async { r.vectorsFor(pin("user:a"), e) }
        advanceTimeBy(50); frames.clear() // the single Moment is Forgotten while the model runs
        assertTrue(job.await().isEmpty()); advanceUntilIdle(); assertEquals(0, r.size())
    }

    @Test fun aForgetAfterTheVectorsWereReadMakesStillValidFalse() = runTest {
        val r = refs(this)
        val used = r.vectorsForAll(listOf(pin("user:a")), e)
        assertEquals(1, used["user:a"]!!.size); assertTrue(r.stillValid(used, e))
        frames.clear(); assertFalse(r.stillValid(used, e))
        frames["00000001"] = "f1"; assertTrue("a restored photo is the same frame", r.stillValid(used, e))
        links.clear(); assertFalse(r.stillValid(used, e))
        links["user:a"] = "00000001"; r.clear(); assertFalse("a cleared cache holds nothing the score could rest on", r.stillValid(used, e))
    }

    @Test fun theShadowDropsAScoreWhoseReferencePhotoWasForgottenWhileItRan() = runTest {
        val r = refs(this)
        var forgetBeforeFinalCheck = false
        val sh = PlaceShadow(embedder = { e }, photo = { ByteArray(1) }, imageRefs = r::vectorsForAll, refsStillValid = { used, emb -> if (forgetBeforeFinalCheck) frames.clear(); r.stillValid(used, emb) }, elapsed = { 0L }, generation = { gen })
        val hits = listOf(pin("user:a") to 10.0)
        assertEquals(ShadowOutcome.Kind.SCORED, sh.score("f", hits).kind)
        forgetBeforeFinalCheck = true
        assertEquals(ShadowOutcome.Kind.DROPPED, sh.score("f", hits).kind)
    }

    @Test fun theEmbedJobChecksTheAttemptsOwnLiveGateBeforeReadingThePhoto() = runTest {
        val r = refs(this)
        var calls = 0
        // true for the pre-embed check in vectorsFor, false when the job starts (the attempt closed or the pipeline paused meanwhile)
        assertTrue(r.vectorsFor(pin("user:a"), e) { calls++ < 1 }.isEmpty()); advanceUntilIdle()
        assertTrue("the stored photo was never read", reads.isEmpty()); assertEquals(0, e.images); assertEquals(0, r.size())
    }

    @Test fun clearEmptiesTheCacheAtOnceAndAnEmbedRunningStoresNothing() = runTest {
        val r = refs(this); r.vectorsFor(pin("user:a"), e); assertEquals(1, r.size())
        r.clear(); assertEquals(0, r.size())
        e.delayMs = 100
        val job = async { r.vectorsFor(pin("user:a"), e) }
        advanceTimeBy(50); r.clear()
        assertTrue(job.await().isEmpty()); advanceUntilIdle(); assertEquals(0, r.size())
    }

    @Test fun manyPinsAreRevalidatedOncePerScoreNotOncePerPin() = runTest {
        for (i in 1..4) { links["user:$i"] = "0000000$i"; frames["0000000$i"] = "f$i" }
        val r = refs(this)
        r.vectorsForAll((1..4).map { pin("user:$it") }, e); advanceUntilIdle()
        r.vectorsForAll((1..4).map { pin("user:$it") }, e)
        var linkReads = 0; pinMomentsHook = { linkReads++ }
        val out = r.vectorsForAll((1..4).map { pin("user:$it") }, e)
        assertEquals(4, out.values.count { it.isNotEmpty() })
        assertEquals("one read of the pin links for the whole score", 1, linkReads)
    }

    @Test fun noPinMeansTheShadowNeverAsksForReferencesOrReadsLinks() = runTest {
        var asked = 0
        val sh = PlaceShadow(embedder = { e }, photo = { ByteArray(1) }, imageRefs = { _, _, _ -> asked++; emptyMap() }, refsStillValid = { _, _ -> asked++; true }, elapsed = { 0L })
        sh.score("f", listOf(pack("wd:Q1", "Charminar") to 1.0)); assertEquals(0, asked)
    }
}
