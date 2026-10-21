package com.rokid.context.orchestration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.RoleBindingRepository
import com.rokid.context.ai.embed.Canaries
import com.rokid.context.ai.embed.Embedders
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.ImageDecodeException
import com.rokid.context.memory.Modality
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.EndpointEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SpaceState
import com.rokid.context.memory.retrieval.VectorMath
import com.rokid.context.orchestration.IndexMaintenance.Outcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IndexMaintenanceTest {
    private companion object { const val BAD: Byte = 99; const val IMG_DIMS = 3 }
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    private val memories = MemoryRepository(db.memoryDao())
    private var now = 1_000_000L

    /**
     * Model "fp" with [dim]; [drift] turns every vector sideways (a model silently replaced behind the same name);
     * [tilt] rotates every vector by that many radians (cosine to the original = cos(tilt)); [docDim] makes document
     * vectors come back with another size than the bound dimension. Canary calls (exactly [Canaries.TEXTS]) take the
     * next angle from [canaryTilts] when it is not empty, and throw while [failCanaries]. [spaceGone] makes
     * `forSpace` return null (the stored space's endpoint was deleted).
     */
    private val fake = object : IndexEmbedders {
        var fp = "A"; var dim = 2; var drift = false; var bound = true; var calls = 0; var tilt = 0.0; var docDim: Int? = null
        var imageCapable = false; var imageCalls = 0; var imageNetworkDown = false; var imageBroken: Exception? = null; var imageEmpty = false; var imageWidth = IMG_DIMS
        var canaryCalls = 0; val canaryTilts = ArrayDeque<Double>(); var failCanaries = false; var spaceGone = false
        fun embedder(fingerprint: String = fp, d: Int = dim) = object : Embedder {
            override val fingerprint = fingerprint
            override suspend fun embedDocuments(texts: List<String>): List<FloatArray> {
                val canary = texts == Canaries.TEXTS
                if (canary) { if (failCanaries) throw java.io.IOException("endpoint unreachable"); canaryCalls++ }
                val angle = if (canary && canaryTilts.isNotEmpty()) canaryTilts.removeFirst() else tilt
                return texts.map { t ->
                    calls++
                    val v = FloatArray(docDim ?: d) { i -> if (drift) (if (i == 1) 1f else 0f) else if (i == 0) 1f else (t.length % 5) / 10f }
                    if (angle != 0.0 && v.size >= 2) { val x = v[0]; val y = v[1]; v[0] = (x * cos(angle) - y * sin(angle)).toFloat(); v[1] = (x * sin(angle) + y * cos(angle)).toFloat() }
                    v
                }
            }
            override suspend fun embedQuery(text: String) = embedDocuments(listOf(text)).single()
            override val imageDims: Int? get() = IMG_DIMS
            override val modalities get() = if (imageCapable) setOf(Modality.TEXT, Modality.IMAGE) else setOf(Modality.TEXT)
            override suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray> {
                imageCalls++
                if (imageNetworkDown) throw java.io.IOException("endpoint unreachable")
                imageBroken?.let { throw it }
                if (imageEmpty) return emptyList()
                return jpegs.map { b -> if (b[0] == BAD) throw ImageDecodeException("cannot decode") else FloatArray(imageWidth) { i -> if (i == 0) b[0].toFloat() else 1f } }
            }
        }
        override suspend fun bound() = if (!bound) null else BoundEmbedder(embedder(), "{\"fp\":\"$fp\"}", dim)
        override suspend fun forSpace(space: EmbeddingSpaceEntity): Embedder? = if (spaceGone) null else embedder(space.fingerprint, space.dim)
    }
    private val clock = object : IndexClock {
        var at = 0L; var pending = false; var token: String? = null; var discard: DiscardRecord? = null
        var pendingTok: String? = null
        override fun pendingRebuildToken() = pendingTok
        override fun setPendingRebuildToken(token: String?) { pendingTok = token }
        override fun lastCanaryAt() = at
        override fun markCanary(at: Long) { this.at = at }
        override fun rebuildPending() = pending
        override fun setRebuildPending(pending: Boolean) { this.pending = pending }
        override fun consumedRebuild() = token
        override fun consumeRebuild(token: String) { this.token = token }
        override fun lastDiscard() = discard
        override fun recordDiscard(record: DiscardRecord?) { discard = record }
    }
    private val photoBytes = mutableMapOf<String, ByteArray>()
    private val unreadable = mutableSetOf<String>()
    private val attempts = InMemoryReadAttempts()
    private var bulk = true
    private val photoLoads = mutableListOf<List<String>>()
    private fun maintenance(maxPerRun: Int = 256, imageMaxPerRun: Int = 200) = IndexMaintenance(
        spaces, fake, clock, { now }, batch = 2, maxPerRun = maxPerRun,
        photos = { ids -> photoLoads += ids; ids.associateWith { id -> if (id in unreadable) PhotoRead.Unreadable else photoBytes[id]?.let { PhotoRead.Bytes(it) } ?: PhotoRead.Missing } },
        readAttempts = attempts,
        bulkAllowed = { bulk }, imageBatch = 2, imageMaxPerRun = imageMaxPerRun,
    )
    private suspend fun photo(id: String, ts: Long, code: Byte? = 1) {
        db.frameDao().insertIgnore(FrameEntity(id, ts, "/p/$id", "rokid_glasses"))
        if (code != null) photoBytes[id] = byteArrayOf(code)
    }
    private suspend fun imageVectors(spaceId: String) = db.frameVectorDao().forSpace(spaceId, 100, 0).map { it.frameId }.sorted()
    @After fun close() = db.close()

    private suspend fun mem(id: String) = memories.add(MemoryEntity(id, null, "text $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))

    // ---- Task 7: image backfill and the per-space image-coverage gate (Rulings 15, 18) ----

    @Test fun imageBackfillFillsStoredPhotosOnly() = runBlocking {
        mem("m1"); fake.imageCapable = true
        photo("p1", 10); photo("p2", 20)
        db.frameVectorDao().upsert(listOf(com.rokid.context.memory.db.FrameVectorEntity("expired", "space_old", byteArrayOf(1)))) // photo already deleted: no frame row
        assertEquals(Outcome.DONE, maintenance().run())
        val active = spaces.queryable()!!
        assertEquals(listOf("p1", "p2"), imageVectors(active.id))
        assertEquals("backfill writes vectors only: no routing, no salience", null, db.frameMetaDao().get("p1"))
        assertEquals(listOf("p2", "p1"), photoLoads.flatten()) // newest first
    }

    @Test fun activeSpaceCatchesUpOnImagesAfterDowntime() = runBlocking {
        mem("m1"); fake.imageCapable = true
        assertEquals(Outcome.DONE, maintenance().run())
        photo("late1", 10); photo("late2", 20); photo("late3", 30)
        assertEquals(Outcome.BACKFILL, maintenance(imageMaxPerRun = 2).run())
        assertEquals(2, imageVectors(spaces.queryable()!!.id).size)
        assertEquals(Outcome.DONE, maintenance(imageMaxPerRun = 2).run())
        assertEquals(listOf("late1", "late2", "late3"), imageVectors(spaces.queryable()!!.id))
    }

    @Test fun swapWaitsForImageCoverageWhenImageCapable() = runBlocking {
        mem("m1"); fake.imageCapable = true
        photo("p1", 10); photo("p2", 20); photo("p3", 30)
        assertEquals(Outcome.BACKFILL, maintenance(imageMaxPerRun = 2).run())
        assertNull("text is complete but one photo has no vector yet", spaces.queryable())
        assertEquals(2, imageVectors(spaces.building()!!.id).size)
        assertEquals(Outcome.DONE, maintenance(imageMaxPerRun = 2).run())
        assertEquals(listOf("p1", "p2", "p3"), imageVectors(spaces.queryable()!!.id))
    }

    @Test fun textOnlySpaceSwapsWithoutImages() = runBlocking {
        mem("m1"); photo("p1", 10); photo("p2", 20) // embedder declares TEXT only
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals("A", spaces.queryable()!!.fingerprint)
        assertEquals(0, fake.imageCalls); assertTrue("no photo is even opened", photoLoads.isEmpty())
    }

    @Test fun unembeddableDoesNotBlockSwap() = runBlocking {
        mem("m1"); fake.imageCapable = true
        photo("ok", 10); photo("bad", 20, BAD); photo("noFile", 30, code = null)
        assertEquals(Outcome.DONE, maintenance().run())
        val active = spaces.queryable()!!
        assertEquals(listOf("ok"), imageVectors(active.id))
        assertEquals("unembeddable", db.frameMetaDao().get("bad")!!.imageVectorSpace)
        assertEquals("unembeddable", db.frameMetaDao().get("noFile")!!.imageVectorSpace)
        assertEquals(0, spaces.missingImageCount(active.id))
    }

    @Test fun endpointOutageNeverMarksPhotosUnembeddable() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("p1", 10); photo("p2", 20)
        fake.imageNetworkDown = true
        try { maintenance().run(); fail("an unreachable endpoint must surface so the worker retries") } catch (_: java.io.IOException) { }
        assertNull(db.frameMetaDao().get("p1")); assertNull(spaces.queryable())
        fake.imageNetworkDown = false
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals(listOf("p1", "p2"), imageVectors(spaces.queryable()!!.id))
    }

    @Test fun backfillPausedByBulkGate() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("p1", 10); photo("p2", 20)
        bulk = false
        assertEquals("text is done, images wait: no spin", Outcome.DONE, maintenance().run())
        assertEquals(0, fake.imageCalls); assertTrue(photoLoads.isEmpty())
        assertNull("the swap waits for the images", spaces.queryable()); assertTrue(spaces.coverage(spaces.building()!!.id).complete)
        bulk = true
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals(listOf("p1", "p2"), imageVectors(spaces.queryable()!!.id))
    }

    @Test fun backfillRunsInBatchesOfTheConfiguredSizeAndIsResumable() = runBlocking {
        mem("m1"); fake.imageCapable = true
        repeat(5) { photo("p$it", it.toLong()) }
        assertEquals(Outcome.BACKFILL, maintenance(imageMaxPerRun = 3).run())
        assertEquals(listOf(2, 1), photoLoads.map { it.size }) // batches of 2, bounded to 3 per run
        while (maintenance(imageMaxPerRun = 3).run() == Outcome.BACKFILL) Unit
        assertEquals(5, imageVectors(spaces.queryable()!!.id).size)
        assertFalse(photoLoads.flatten().groupBy { it }.any { it.value.size > 1 })
    }

    @Test fun brokenEmbedderMarksNothingAndNeverSwaps() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("p1", 10); photo("p2", 20)
        fake.imageBroken = IllegalStateException("model not loaded")
        try { maintenance().run(); fail("a broken model is a retryable failure") } catch (_: IllegalStateException) { }
        assertNull(db.frameMetaDao().get("p1")); assertNull(db.frameMetaDao().get("p2"))
        assertNull(spaces.queryable()); assertEquals(2, spaces.missingImageCount(spaces.building()!!.id))
        fake.imageBroken = null
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals(listOf("p1", "p2"), imageVectors(spaces.queryable()!!.id))
    }

    @Test fun transientReadFaultIsMarkedOnlyAfterThreeSpacedAttempts() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("ok", 10); photo("locked", 20); unreadable += "locked"
        assertEquals("one photo is still waiting: more later", Outcome.BACKFILL, maintenance().run())
        assertNull(db.frameMetaDao().get("locked"))
        assertEquals(Outcome.BACKFILL, maintenance().run()) // same hour: counts once
        assertNull(db.frameMetaDao().get("locked"))
        now += IndexMaintenance.READ_RETRY_GAP_MS; assertEquals(Outcome.BACKFILL, maintenance().run())
        assertNull("two attempts are not enough", db.frameMetaDao().get("locked"))
        now += IndexMaintenance.READ_RETRY_GAP_MS; assertEquals(Outcome.DONE, maintenance().run())
        assertEquals("unembeddable", db.frameMetaDao().get("locked")!!.imageVectorSpace)
        assertEquals(listOf("ok"), imageVectors(spaces.queryable()!!.id))
    }

    @Test fun aReadFaultThatClearsLeavesThePhotoEmbeddable() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("locked", 20); unreadable += "locked"
        assertEquals(Outcome.BACKFILL, maintenance().run())
        unreadable.clear()
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals(listOf("locked"), imageVectors(spaces.queryable()!!.id)); assertNull(db.frameMetaDao().get("locked"))
    }

    @Test fun wrongWidthVectorsAreRejectedNotStoredNorMarked() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("p1", 10); photo("p2", 20)
        fake.imageWidth = IMG_DIMS + 1
        try { maintenance().run(); fail("a width the embedder does not declare is a config failure") } catch (_: IndexConfigException) { }
        assertEquals(0, db.frameVectorDao().countForSpace(spaces.building()!!.id))
        assertNull(db.frameMetaDao().get("p1")); assertNull(spaces.queryable())
    }

    @Test fun unroutedPendingFramesDoNotSpinTheRunOrBlockTheSwap() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("p1", 10); photo("fresh", 20)
        db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity("fresh", null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, 1))
        db.frameStateDao().insertIgnore(listOf(com.rokid.context.memory.db.FrameStateEntity("fresh", "PENDING", 0, null, null, null, 1)))
        assertEquals("no un-delayed MORE while the indexer still owns a frame", Outcome.DONE, maintenance().run())
        assertEquals(listOf("p1"), imageVectors(spaces.queryable()!!.id))
        assertEquals(1, photoLoads.flatten().count { it == "p1" }); assertFalse("fresh" in photoLoads.flatten())
    }

    @Test fun emptyEmbedderResultIsRetryableAndMarksNothing() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("p1", 10); photo("p2", 20)
        fake.imageEmpty = true
        repeat(3) { try { maintenance().run(); fail("a wrong-count result is the embedder's failure") } catch (_: ImageEmbedderException) { } }
        assertNull(db.frameMetaDao().get("p1")); assertNull(db.frameMetaDao().get("p2")); assertNull(spaces.queryable())
        fake.imageEmpty = false
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals(listOf("p1", "p2"), imageVectors(spaces.queryable()!!.id))
    }

    @Test fun aWholeBatchFailingToDecodeIsABrokenDecoderNotBadPhotos() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("a", 10, BAD); photo("b", 20, BAD); photo("c", 30, BAD)
        try { maintenance().run(); fail("every photo failing is systemic") } catch (_: ImageEmbedderException) { }
        listOf("a", "b", "c").forEach { assertNull(db.frameMetaDao().get(it)) }
        assertNull(spaces.queryable())
    }

    @Test fun aLibraryOfOnlyUndecodablePhotosNeverSwapsWithZeroImageVectors() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("only", 10, BAD) // a batch of one can't be told from one bad photo
        try { maintenance().run(); fail("no image vector at all is a config failure") } catch (_: IndexConfigException) { }
        assertEquals("unembeddable", db.frameMetaDao().get("only")!!.imageVectorSpace)
        assertNull(spaces.queryable())
    }

    @Test fun aWrongWidthRowInTheBuildingSpaceIsDroppedAndRefilled() = runBlocking {
        mem("m1"); fake.imageCapable = true; photo("p1", 10)
        bulk = false; maintenance().run() // text done, images wait
        val b = spaces.building()!!
        db.frameVectorDao().upsert(listOf(com.rokid.context.memory.db.FrameVectorEntity("p1", b.id, byteArrayOf(1, 2, 3, 4)))) // a 1-float row: a bad write
        bulk = true
        assertEquals(Outcome.DONE, maintenance().run())
        val rows = db.frameVectorDao().forSpace(spaces.queryable()!!.id, 10, 0)
        assertEquals(listOf(IMG_DIMS * 4), rows.map { it.blob.size })
    }

    @Test fun longTextReindexContinuesAtOnceButBackfillWaits() = runBlocking {
        repeat(5) { mem("m$it") }
        assertEquals(Outcome.MORE, maintenance(maxPerRun = 2).run()) // text backlog: immediate continuation
    }

    @Test fun noBindingIsIdle() = runBlocking {
        fake.bound = false
        assertEquals(Outcome.IDLE, maintenance().run())
        assertNull(spaces.current())
    }

    /** T19 fix round 1: unbinding EMBED clears the drift streak, so "index unstable" can't outlive the model. */
    @Test fun unbindingTheEmbedderClearsTheDriftStreak() = runBlocking {
        clock.discard = DiscardRecord("A", 0, IndexMaintenance.MAX_DISCARD_STREAK)
        fake.bound = false
        assertEquals(Outcome.IDLE, maintenance().run())
        assertNull(clock.discard); assertTrue(!clock.unstable())
    }

    @Test fun firstRunBuildsAndActivatesAtFullCoverage() = runBlocking {
        mem("m1"); mem("m2"); mem("m3")
        assertEquals(Outcome.DONE, maintenance().run())
        val active = spaces.queryable()!!
        assertEquals("A", active.fingerprint)
        assertEquals(3, spaces.coverage(active.id).have)
    }

    @Test fun aBigBacklogContinuesAcrossRunsAndOnlySwapsWhenComplete() = runBlocking {
        repeat(5) { mem("m$it") }
        assertEquals(Outcome.MORE, maintenance(maxPerRun = 2).run())
        assertNull(spaces.queryable()) // not yet complete: nothing to read, semantic falls back to lexical
        while (maintenance(maxPerRun = 2).run() == Outcome.MORE) Unit
        assertEquals(5, spaces.coverage(spaces.queryable()!!.id).have)
    }

    @Test fun bindingChangeBuildsBesideTheOldSpaceAndNewMemoriesGoIntoBoth() = runBlocking {
        mem("m1"); maintenance().run()
        val old = spaces.queryable()!!
        repeat(4) { mem("x$it") }
        fake.fp = "B"
        assertEquals(Outcome.MORE, maintenance(maxPerRun = 6).run()) // 4 into the old space, 2 into the new one
        assertEquals(old.id, spaces.queryable()!!.id)
        assertEquals(5, spaces.coverage(old.id).have)
        mem("late")
        while (maintenance(maxPerRun = 6).run() == Outcome.MORE) Unit
        val active = spaces.queryable()!!
        assertEquals("B", active.fingerprint); assertEquals(6, spaces.coverage(active.id).have)
        assertEquals(SpaceState.RETIRED, db.embeddingDao().space(old.id)!!.state)
    }

    @Test fun canaryDriftMarksTheSpaceStaleAndRebuildsAtMostDaily() = runBlocking {
        mem("m1"); maintenance().run()
        clock.at = now // F4: the last canary check is "now", so the 24 h guard is what holds the next check back
        val first = spaces.queryable()!!
        fake.drift = true
        now += 3_600_000 // within 24 h of the last canary check: not checked again
        val before = fake.calls
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals("no canary re-embedding within 24 h", before, fake.calls)
        assertEquals(first.id, spaces.queryable()!!.id)
        assertEquals(clock.at, now - 3_600_000)
        now += 25 * 3_600_000L
        assertEquals(Outcome.DONE, maintenance().run())
        val rebuilt = spaces.queryable()!!
        assertNotEquals(first.id, rebuilt.id); assertEquals("A", rebuilt.fingerprint)
        assertEquals(now, clock.at)
    }

    @Test fun canariesUseTheSpecThreshold() = runBlocking {
        mem("m1"); maintenance().run()
        val first = spaces.queryable()!!
        fake.tilt = acos(0.998) // still the same model: cosine 0.998 >= 0.995
        now += 25 * 3_600_000L
        maintenance().run()
        assertEquals(first.id, spaces.queryable()!!.id)
        fake.tilt = acos(0.99) // drifted: cosine 0.99 < 0.995 against the stored canaries
        now += 25 * 3_600_000L
        maintenance().run()
        val rebuilt = spaces.queryable()!!
        assertNotEquals(first.id, rebuilt.id)
        assertEquals(SpaceState.RETIRED, db.embeddingDao().space(first.id)?.state ?: SpaceState.RETIRED)
    }

    @Test fun canariesAreStoredAtCreation() = runBlocking {
        mem("m1"); maintenance().run()
        val stored = Canaries.decode(spaces.queryable()!!.canaryJson)
        val expected = fake.embedder().embedDocuments(Canaries.TEXTS)
        assertEquals(3, stored.size)
        stored.zip(expected).forEach { (a, b) -> assertArrayEquals(b, a, 0f) }
    }

    private val far = acos(0.9) // well below the 0.995 threshold

    @Test fun driftWhileBuildingIsDiscardedOnceAndNoNewSpaceStartsWithin24h() = runBlocking {
        repeat(4) { mem("m$it") }
        maintenance(maxPerRun = 2).run()
        val partial = spaces.building()!!
        fake.drift = true // the model changed behind its name in the middle of the build: both fresh probes agree
        assertEquals(Outcome.DONE, maintenance(maxPerRun = 8).run()) // discarded, and no continuation
        assertNull("a drifted build is never swapped in", db.embeddingDao().space(partial.id))
        assertEquals(1, clock.discard!!.streak); assertEquals(now, clock.discard!!.at)
        now += 23 * 3_600_000L
        val before = fake.calls
        assertEquals(Outcome.DONE, maintenance(maxPerRun = 8).run())
        assertNull("no new space within 24 h of a discard", spaces.building()); assertNull(spaces.current())
        assertEquals(before, fake.calls)
        now += 2 * 3_600_000L
        while (maintenance(maxPerRun = 8).run() == Outcome.MORE) Unit
        val active = spaces.queryable()!!
        assertTrue(Canaries.decode(active.canaryJson).all { it[1] == 1f }) // rebuilt with the new model's canaries
        assertNull("a successful swap ends the streak", clock.discard)
    }

    @Test fun noisyCanariesKeepTheBuildAndSwapNormally() = runBlocking {
        repeat(4) { mem("m$it") }
        maintenance(maxPerRun = 2).run()
        val partial = spaces.building()!!
        fake.canaryTilts += listOf(far, 0.0) // first fresh set mismatches, second disagrees with the first: noise, not drift
        assertEquals(Outcome.DONE, maintenance(maxPerRun = 8).run())
        assertEquals(partial.id, spaces.queryable()!!.id)
        assertNull(clock.discard)
    }

    @Test fun freshSetsThatDisagreeWithEachOtherAndWithTheStoredSetAreDrift() = runBlocking {
        repeat(4) { mem("m$it") }
        maintenance(maxPerRun = 2).run()
        val partial = spaces.building()!!
        fake.canaryTilts += listOf(far, -far) // neither fresh set matches the stored one: a changed, non-deterministic model
        assertEquals(Outcome.DONE, maintenance(maxPerRun = 8).run())
        assertNull(db.embeddingDao().space(partial.id)); assertNull(spaces.queryable())
        assertEquals(1, clock.discard!!.streak)
    }

    /**
     * Review B I-1: Roll back rebinds EMBED to the restored space's model, so the reindex that follows finds the bound
     * fingerprint equal to the active space: no build starts, and the retired newer space keeps its vectors.
     */
    @Test fun rollBackThenReindexDoesNotRebuildOrDiscardTheRetiredSpace() = runBlocking {
        mem("m1"); maintenance().run()
        val a = spaces.queryable()!!
        fake.fp = "B"
        while (maintenance().run() == Outcome.MORE) Unit
        val b = spaces.queryable()!!
        assertEquals("B", b.fingerprint)
        assertTrue(spaces.rollback(a.id))
        fake.fp = "A" // the rollback rebound EMBED to A's model before the reindex
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals(a.id, spaces.queryable()!!.id)
        assertNull(spaces.building())
        assertEquals(SpaceState.RETIRED, db.embeddingDao().space(b.id)!!.state)
        assertEquals(1, db.embeddingDao().vectorCount(b.id))
    }

    @Test fun aNewModelClearsTheUnstableState() = runBlocking {
        mem("m1")
        repeat(3) { fake.canaryTilts += listOf(0.0, far, far); maintenance().run(); now += 25 * 3_600_000L }
        assertTrue(clock.unstable())
        mem("m2"); mem("m3")
        fake.fp = "B"
        assertEquals(Outcome.MORE, maintenance(maxPerRun = 1).run()) // B's build is under way, not swapped yet
        assertTrue("a new fingerprint starts a fresh streak", !clock.unstable())
        while (maintenance().run() == Outcome.MORE) Unit
        assertEquals("B", spaces.queryable()!!.fingerprint)
    }

    @Test fun threeDriftDiscardsStopAutoRebuildingUntilAManualRebuild() = runBlocking {
        mem("m1")
        repeat(3) {
            fake.canaryTilts += listOf(0.0, far, far) // stored at creation, then two agreeing fresh sets that differ from it
            assertEquals(Outcome.DONE, maintenance().run())
            assertNull(spaces.building()); assertNull(spaces.current())
            now += 25 * 3_600_000L
        }
        assertEquals(3, clock.discard!!.streak)
        assertTrue("index unstable", clock.unstable())
        val canaries = fake.canaryCalls
        now += 25 * 3_600_000L
        assertEquals(Outcome.DONE, maintenance().run())
        assertNull("auto-rebuild has stopped", spaces.building()); assertEquals(canaries, fake.canaryCalls)
        assertEquals(Outcome.DONE, maintenance().run(rebuild = true)) // the user's Rebuild resets the streak
        assertTrue(spaces.queryable() != null); assertNull(clock.discard)
        assertTrue(!clock.unstable())
    }

    @Test fun anotherModelIsNotHeldBackByADiscardedOne() = runBlocking {
        mem("m1")
        fake.canaryTilts += listOf(0.0, far, far)
        maintenance().run()
        assertEquals("A", clock.discard!!.fingerprint)
        fake.fp = "B" // the user bound another model straight after
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals("B", spaces.queryable()!!.fingerprint)
    }

    @Test fun unreachableCanariesBeforeASwapRetryAndNeverSwap() = runBlocking {
        repeat(4) { mem("m$it") }
        maintenance(maxPerRun = 2).run()
        val partial = spaces.building()!!
        fake.failCanaries = true
        assertEquals(Outcome.RETRY, maintenance(maxPerRun = 8).run())
        assertEquals(SpaceState.BUILDING, db.embeddingDao().space(partial.id)!!.state); assertNull(spaces.queryable())
        assertEquals(0L, clock.at)
        fake.failCanaries = false
        assertEquals(Outcome.DONE, maintenance(maxPerRun = 8).run())
        assertEquals(partial.id, spaces.queryable()!!.id)
        assertEquals("a passed pre-swap check marks the clock", now, clock.at)
    }

    @Test fun aCanaryCheckThatCouldNotRunDoesNotMarkTheClock() = runBlocking {
        mem("m1"); maintenance().run()
        clock.at = now
        val first = spaces.queryable()!!
        now += 25 * 3_600_000L
        fake.failCanaries = true
        maintenance().run()
        assertEquals(now - 25 * 3_600_000L, clock.at)
        fake.failCanaries = false; fake.spaceGone = true
        maintenance().run()
        assertEquals(now - 25 * 3_600_000L, clock.at)
        assertEquals(first.id, spaces.queryable()!!.id)
        fake.spaceGone = false
        maintenance().run()
        assertEquals(now, clock.at)
    }

    @Test fun anExactlySpentBudgetWithNothingLeftIsDone() = runBlocking {
        mem("m1"); maintenance().run()
        mem("m2"); mem("m3")
        assertEquals(Outcome.DONE, maintenance(maxPerRun = 2).run())
        mem("m4"); mem("m5"); mem("m6")
        assertEquals(Outcome.MORE, maintenance(maxPerRun = 2).run())
    }

    @Test fun aRedeliveredRebuildRequestRunsOnce() = runBlocking {
        mem("m1"); maintenance().run()
        val first = spaces.queryable()!!
        assertEquals(Outcome.DONE, maintenance().run(rebuild = true, rebuildToken = "work-1"))
        val rebuilt = spaces.queryable()!!
        assertNotEquals(first.id, rebuilt.id)
        assertEquals(Outcome.DONE, maintenance().run(rebuild = true, rebuildToken = "work-1")) // WorkManager re-ran it after a crash
        assertEquals(rebuilt.id, spaces.queryable()!!.id)
        maintenance().run(rebuild = true, rebuildToken = "work-2")
        assertNotEquals(rebuilt.id, spaces.queryable()!!.id)
    }

    @Test fun aPendingRebuildFromAPauseIsHonouredOnce() = runBlocking {
        mem("m1"); maintenance().run()
        val first = spaces.queryable()!!
        clock.pending = true
        assertEquals(Outcome.DONE, maintenance().run())
        val rebuilt = spaces.queryable()!!
        assertNotEquals(first.id, rebuilt.id); assertTrue(!clock.pending)
        maintenance().run()
        assertEquals(rebuilt.id, spaces.queryable()!!.id)
    }

    @Test fun dimensionChangeRestartsTheBuild() = runBlocking {
        repeat(4) { mem("m$it") }
        maintenance(maxPerRun = 2).run()
        val partial = spaces.building()!!
        fake.dim = 3; fake.fp = "A-1024"
        while (maintenance(maxPerRun = 2).run() == Outcome.MORE) Unit
        assertNull(db.embeddingDao().space(partial.id))
        assertEquals(3, spaces.queryable()!!.dim)
    }

    @Test fun aWrongSizedVectorIsAConfigFailureThatWritesNothing() = runBlocking {
        repeat(3) { mem("m$it") }
        fake.docDim = 3 // the bound dimension is 2, but documents come back with 3
        repeat(2) {
            try { maintenance().run(); fail("expected IndexConfigException") } catch (_: IndexConfigException) { }
        }
        val building = spaces.building()!!
        assertEquals(0, spaces.coverage(building.id).have)
        assertNull(spaces.queryable())
    }

    @Test fun aWrongSizedVectorForTheOldSpaceIsAlsoAConfigFailure() = runBlocking {
        mem("m1"); maintenance().run()
        mem("m2")
        fake.docDim = 3
        try { maintenance().run(); fail("expected IndexConfigException") } catch (_: IndexConfigException) { }
    }

    @Test fun rebuildForcesANewSpaceForTheSameModel() = runBlocking {
        mem("m1"); maintenance().run()
        val first = spaces.queryable()!!
        assertEquals(Outcome.DONE, maintenance().run(rebuild = true))
        assertNotEquals(first.id, spaces.queryable()!!.id)
        assertTrue(fake.calls > 0)
    }

    /** Carried from Task 9: the stored canaries are embedded through the space's own embedder, so its document prefix is applied. */
    @Test fun canariesGoThroughTheRealEmbedderWithTheDocumentPrefix() = runBlocking {
        val inputs = mutableListOf<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/tags" -> MockResponse().setBody("""{"models":[{"name":"nomic-embed-text:latest","digest":"sha256:abc"}]}""")
                    "/api/embed" -> {
                        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["input"]!!.jsonArray.map { it.jsonPrimitive.content }
                        synchronized(inputs) { inputs += sent }
                        // The vector depends on the exact text sent, so a missing prefix would change the stored canaries.
                        MockResponse().setBody("""{"embeddings":[${sent.joinToString(",") { "[3,${it.length % 7 + 1},0]" }}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        try {
            val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
            val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
            db.endpointDao().upsert(EndpointEntity("ol", "Ollama", "OLLAMA", server.url("/").toString().trimEnd('/'), null,
                "nomic-embed-text", EndpointRepository.keyRef("ol"), "EMBEDDINGS", 1))
            bindings.set(RoleBinding(Role.EMBED, "ol", "nomic-embed-text")).getOrThrow()
            mem("m1")
            assertEquals(Outcome.DONE, IndexMaintenance(spaces, AiIndexEmbedders(Embedders(endpoints, bindings)), clock, { now }).run())
            val space = spaces.queryable()!!
            Canaries.TEXTS.forEach { t -> assertTrue("canary sent with the document prefix", "search_document: $t" in inputs) }
            val expected = Canaries.TEXTS.map { t -> VectorMath.l2(floatArrayOf(3f, (("search_document: $t").length % 7 + 1).toFloat(), 0f)) }
            Canaries.decode(space.canaryJson).zip(expected).forEach { (a, b) -> assertArrayEquals(b, a, 1e-6f) }
            assertEquals(3, Canaries.decode(space.canaryJson).size)
        } finally { server.shutdown() }
    }

    /** Phase 5 T8 carry (ruling): an empty library is fully indexed, so a bound model gets an ACTIVE space at once. */
    @Test fun anEmptyLibraryActivatesTheSpaceAtOnce() = runBlocking {
        assertEquals(Outcome.DONE, maintenance().run())
        val active = spaces.queryable()!!
        assertEquals(com.rokid.context.memory.Coverage(0, 0), spaces.coverage(active.id))
    }

    /** Phase 5 T11 carry: a run that honours a pending Rebuild consumes its request, so that request's own run rebuilds nothing. */
    @Test fun aRebuildHonouredAsPendingIsNotRunAgainByItsOwnRequest() = runBlocking {
        mem("m1"); maintenance().run()
        val first = spaces.queryable()!!
        clock.pending = true; clock.pendingTok = "w2"
        maintenance().run() // an older, normal run sees the pending Rebuild first
        val rebuilt = spaces.queryable()!!
        assertNotEquals(first.id, rebuilt.id)
        assertEquals("w2", clock.token)
        assertNull(clock.pendingTok)
        maintenance().run(rebuild = true, rebuildToken = "w2") // the REPLACE request itself
        assertEquals(rebuilt.id, spaces.queryable()!!.id)
    }
}
