package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.ImagePut
import com.rokid.context.memory.db.ImageSwapHealth
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SpaceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spec §F.2: a swap keeps the retired space's vectors for 7 days, with Roll back; image coverage is a wave-2 flag. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EmbeddingSwapTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.embeddingDao()
    private val clock = InMemoryRetiredClock()
    private var t = 1_000L
    private val day = 24 * 60 * 60 * 1000L
    private val memories = MemoryRepository(db.memoryDao())
    private val v = floatArrayOf(1f, 0f)
    @After fun close() = db.close()

    private fun spaces() = EmbeddingSpaces(dao, clock, { t })
    private suspend fun mem(id: String) = memories.add(MemoryEntity(id, null, "text $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))
    private suspend fun EmbeddingSpaces.build(fp: String) = startBuilding(fp, "{}", 2, "[]", 10).also { put("m1", it.id, v) }
    private suspend fun frame(id: String) = db.frameDao().insertIgnore(FrameEntity(id, 1, "/p/$id", "glasses"))
    private suspend fun fvec(frame: String, space: String) = db.frameVectorDao().upsert(listOf(FrameVectorEntity(frame, space, byteArrayOf(1))))

    /** a ACTIVE (swapped in), then b swapped in: a RETIRED at t. */
    private suspend fun twoSpaces(s: EmbeddingSpaces = spaces()): Pair<String, String> {
        mem("m1")
        val a = s.build("fpA"); assertTrue(s.swapIfComplete(a.id))
        val b = s.build("fpB"); assertTrue(s.swapIfComplete(b.id))
        return a.id to b.id
    }

    @Test fun swapKeepsRetiredVectors() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id); frame("f1"); fvec("f1", a.id)
        val b = s.build("fpB"); assertTrue(s.swapIfComplete(b.id))
        assertEquals(SpaceState.RETIRED, dao.space(a.id)!!.state)
        assertEquals(1, dao.vectors(a.id).size)
        assertEquals(1, db.frameVectorDao().countForSpace(a.id))
        assertEquals(t, clock.retiredAt(a.id))
    }

    @Test fun rollbackWithin7Days() = runBlocking {
        val (a, b) = twoSpaces()
        val s = spaces()
        t += 6 * day
        assertEquals(a, s.rollbackTarget()!!.id)
        assertTrue(s.rollback(a))
        assertEquals(SpaceState.ACTIVE, dao.space(a)!!.state)
        assertEquals(SpaceState.RETIRED, dao.space(b)!!.state)
        assertEquals(1, dao.vectors(b).size) // the one that served meanwhile is kept too
        assertEquals(a, s.queryable()!!.id)
        assertEquals(t, clock.retiredAt(b))
    }

    @Test fun rollbackRefusedAfter7Days() = runBlocking {
        val (a, b) = twoSpaces()
        val s = spaces()
        t += 7 * day + 1
        assertFalse(s.rollback(a))
        assertEquals(null, s.rollbackTarget())
        assertEquals(SpaceState.ACTIVE, dao.space(b)!!.state)
        assertFalse("only a RETIRED space can be rolled back to", s.rollback(b))
    }

    @Test fun purgeAfter7Days() = runBlocking {
        val (a, b) = twoSpaces()
        val s = spaces()
        fvec("fx", a)
        t += 6 * day; s.purgeRetired()
        assertEquals(1, dao.vectors(a).size)
        t += 2 * day; s.purgeRetired()
        assertTrue(dao.vectors(a).isEmpty())
        assertEquals(0, db.frameVectorDao().countForSpace(a))
        assertEquals(1, dao.vectors(b).size) // the ACTIVE space is never purged
        assertEquals(SpaceState.RETIRED, dao.space(a)!!.state)
    }

    /** Review B M-1: a crash between the swap commit and markRetired leaves no stamp; that must start the grace, not end it. */
    @Test fun unstampedRetiredSpaceGetsStampedNotPurged() = runBlocking {
        val (a, _) = twoSpaces()
        clock.forget(a)
        val s = spaces()
        s.purgeRetired()
        assertEquals(1, dao.vectors(a).size)
        assertEquals(t, clock.retiredAt(a))
        t += 8 * day; s.purgeRetired()
        assertTrue(dao.vectors(a).isEmpty())
    }

    /** Review B M-4: the availability flow re-reads the clock, and it needs the retained vectors (a COUNT, not a load). */
    @Test fun rollbackAvailableChecksGraceAndVectorsOnEachRead() = runBlocking {
        val (a, _) = twoSpaces()
        val s = spaces()
        assertTrue(s.rollbackAvailable().first())
        t += 8 * day
        assertFalse(s.rollbackAvailable().first())
        t = 1_000L
        assertTrue(s.rollbackAvailable().first())
        db.openHelper.writableDatabase.execSQL("DELETE FROM memory_vector WHERE spaceId = '$a'")
        assertFalse(s.rollbackAvailable().first())
        assertEquals(null, s.rollbackTarget())
    }

    @Test fun coverageIgnoresDeletedPhotos() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); assertTrue(s.swapIfComplete(a.id, requireImages = true)) // no photos at all: nothing to cover
        frame("kept"); fvec("kept", a.id); fvec("deleted", a.id) // "deleted" has a vector but no stored photo
        val b = s.build("fpB")
        assertFalse("a stored photo without an image vector blocks the swap", s.swapIfComplete(b.id, requireImages = true))
        fvec("kept", b.id) // no vector is needed (or possible) for the deleted photo
        assertTrue(s.swapIfComplete(b.id, requireImages = true))
    }

    @Test fun wave1SwapNeedsTextCoverageOnly() = runBlocking {
        mem("m1")
        val s = spaces() // requireImages defaults to false: a text-only building embedder
        val a = s.build("fpA"); s.swapIfComplete(a.id)
        frame("f1"); fvec("f1", a.id)
        val b = s.build("fpB")
        assertTrue(s.swapIfComplete(b.id))
        assertEquals(b.id, s.queryable()!!.id)
    }

    @Test fun rollbackKeepsRetiredFrameVectorsSevenDays() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id); frame("f1"); fvec("f1", a.id)
        val b = s.build("fpB"); assertTrue(s.swapIfComplete(b.id, requireImages = false))
        t += 6 * day
        s.purgeRetired(t)
        assertEquals("inside the grace the retired image vectors stay", 1, db.frameVectorDao().countForSpace(a.id))
        assertTrue(s.rollback(a.id))
        assertEquals(a.id, s.queryable()!!.id)
        assertEquals(1, db.frameVectorDao().countForSpace(a.id))
    }

    @Test fun purgeAfterGraceDropsFrameVectors() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id); frame("f1"); fvec("f1", a.id)
        val b = s.build("fpB"); fvec("f1", b.id); assertTrue(s.swapIfComplete(b.id, requireImages = true))
        t += 8 * day
        s.purgeRetired(t)
        assertEquals(0, db.frameVectorDao().countForSpace(a.id))
        assertEquals("the serving space keeps its image vectors", 1, db.frameVectorDao().countForSpace(b.id))
    }

    @Test fun missingImagesListsStoredPhotosWithoutAVectorNewestFirst() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id)
        db.frameDao().insertIgnore(FrameEntity("old", 1, "/p/old", "glasses"))
        db.frameDao().insertIgnore(FrameEntity("new", 5, "/p/new", "glasses"))
        db.frameDao().insertIgnore(FrameEntity("have", 3, "/p/have", "glasses")); fvec("have", a.id)
        assertEquals(listOf("new", "old"), s.missingImages(a.id, 10))
        assertEquals(listOf("new"), s.missingImages(a.id, 1))
        assertEquals(2, s.missingImageCount(a.id))
        assertEquals(s.missingImageCount(a.id), s.missingImages(a.id, 10).size)
        assertEquals(ImagePut.STORED, s.putImage("new", a.id, floatArrayOf(1f, 0f, 0f), expectedDims = 3))
        assertEquals(listOf("old"), s.missingImages(a.id, 10))
        assertEquals("a vector for a photo that is gone is dropped", ImagePut.DROPPED, s.putImage("ghost", a.id, floatArrayOf(1f, 0f, 0f), expectedDims = 3))
    }

    @Test fun unembeddablePhotoIsNeitherListedNorCountedAndDoesNotBlockTheSwap() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id)
        frame("bad"); frame("legacy")
        db.frameMetaDao().upsert(FrameMetaEntity("bad", null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, 1))
        s.markUnembeddable("bad"); s.markUnembeddable("legacy") // legacy: no frame_meta row yet
        val b = s.build("fpB")
        frame("good"); fvec("good", b.id) // one real image vector: a library where every photo is marked would be refused
        assertEquals(emptyList<String>(), s.missingImages(b.id, 10))
        assertEquals(0, s.missingImageCount(b.id))
        assertEquals("unembeddable", db.frameMetaDao().get("bad")!!.imageVectorSpace)
        assertEquals("unembeddable", db.frameMetaDao().get("legacy")!!.imageVectorSpace)
        assertTrue(s.swapIfComplete(b.id, requireImages = true))
    }

    @Test fun unroutedPendingFramesAreLeftToTheIndexer() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id)
        frame("fresh"); frame("stuck")
        db.frameMetaDao().upsert(FrameMetaEntity("fresh", null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, 1))
        db.frameMetaDao().upsert(FrameMetaEntity("stuck", null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, 1))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity("fresh", "PENDING", 0, null, null, null, 1), FrameStateEntity("stuck", "EXTRACTED", 0, null, null, null, 1)))
        assertEquals(listOf("stuck"), s.missingImages(a.id, 10)) // fresh: PENDING and unrouted
        assertEquals("the swap does not wait for a frame the indexer owns", 1, s.missingImageCount(a.id))
        val b = s.build("fpB"); db.frameVectorDao().upsert(listOf(FrameVectorEntity("stuck", b.id, byteArrayOf(1))))
        assertTrue(s.swapIfComplete(b.id, requireImages = true))
    }

    @Test fun wrongWidthImageVectorIsRejectedAndNeverStored() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id)
        frame("f1"); frame("f2"); frame("f3")
        assertEquals("a poisoned first vector can't set the width", ImagePut.WIDTH_MISMATCH, s.putImage("f1", a.id, floatArrayOf(1f, 0f), expectedDims = 3))
        assertEquals(0, db.frameVectorDao().countForSpace(a.id))
        assertEquals(ImagePut.STORED, s.putImage("f2", a.id, floatArrayOf(1f, 0f, 0f), expectedDims = 3))
        assertEquals(ImagePut.WIDTH_MISMATCH, s.putImage("f3", a.id, floatArrayOf(1f, 0f, 0f, 0f), expectedDims = 3))
        assertEquals(1, db.frameVectorDao().countForSpace(a.id))
        assertEquals(listOf("f1", "f3"), s.missingImages(a.id, 10).sorted()) // still missing, not marked
        assertEquals(null, db.frameMetaDao().get("f1"))
    }

    @Test fun theSharedWritePointRejectsAWrongWidthToo() = runBlocking {
        frame("f1")
        assertEquals(ImagePut.WIDTH_MISMATCH, dao.putImageVector("f1", "sX", VectorCodec.encode(floatArrayOf(1f, 0f)), 3))
        assertEquals(ImagePut.STORED, dao.putImageVector("f1", "sX", VectorCodec.encode(floatArrayOf(1f, 0f, 0f)), 3))
        assertEquals(1, db.frameVectorDao().countForSpace("sX"))
    }

    @Test fun resumingABuildDropsImageVectorsOfTheWrongWidth() = runBlocking {
        mem("m1")
        val s = spaces()
        val b = s.startBuilding("fpB", "{}", 2, "[]", 10, imageDims = 3)
        frame("bad"); frame("good")
        fvec("bad", b.id) // a 1-byte blob: not 3 floats
        db.frameVectorDao().upsert(listOf(FrameVectorEntity("good", b.id, VectorCodec.encode(floatArrayOf(1f, 0f, 0f)))))
        val again = s.startBuilding("fpB", "{}", 2, "[]", 11, imageDims = 3)
        assertEquals(b.id, again.id)
        assertEquals(1, db.frameVectorDao().countForSpace(b.id))
        assertEquals(listOf("bad"), s.missingImages(b.id, 10))
    }

    @Test fun swapRefusedWhenEveryPhotoIsUnembeddableAndNoVectorExists() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id)
        frame("x"); frame("y"); s.markUnembeddable("x"); s.markUnembeddable("y")
        val b = s.build("fpB")
        assertEquals(0, s.missingImageCount(b.id))
        assertEquals(ImageSwapHealth.NO_VECTORS, s.imageSwapHealth(b.id))
        assertFalse(s.swapIfComplete(b.id, requireImages = true))
        assertTrue("a text-only swap does not look at photos", s.swapIfComplete(b.id, requireImages = false))
    }

    @Test fun swapRefusedWhenMoreThanHalfOfTheLibraryIsUnembeddable() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id)
        val b = s.build("fpB")
        repeat(10) { frame("ok$it"); fvec("ok$it", b.id) }
        repeat(11) { frame("bad$it"); s.markUnembeddable("bad$it") }
        assertEquals(ImageSwapHealth.SYSTEMIC, s.imageSwapHealth(b.id))
        assertFalse(s.swapIfComplete(b.id, requireImages = true))
        db.frameVectorDao().upsert(listOf(FrameVectorEntity("ok0", b.id, byteArrayOf(1))))
        repeat(10) { db.frameDao().insertIgnore(FrameEntity("more$it", 1, "/p", "glasses")); fvec("more$it", b.id) } // now 11 of 31 are marked
        assertEquals(ImageSwapHealth.OK, s.imageSwapHealth(b.id))
        assertTrue(s.swapIfComplete(b.id, requireImages = true))
    }
}
