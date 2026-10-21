package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.forget.ForgetService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Data safety: no image-vector writer or work list may bring back the vector of a forgotten (tombstoned) photo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageVectorTombstoneTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    @After fun close() = db.close()

    private val blob = ByteArray(8) // 2 floats
    private suspend fun space(): String = spaces.startBuilding("fp", "{}", 2, "[]", 1).id
    private suspend fun photo(id: String, meta: Boolean = true) {
        db.frameDao().insertIgnore(FrameEntity(id, 1, "/$id.jpg", "rokid_glasses"))
        if (meta) db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.9f, null, null, null, null, 1))
    }
    private suspend fun tombstone(id: String) { db.forgetDao().insertTombstone(ForgetTombstoneEntity(id, 1)) }
    private suspend fun vectors(space: String, id: String) = db.frameVectorDao().forFrames(space, listOf(id))

    @Test fun putImageCheckedDropsATombstonedFrame() = runBlocking {
        val s = space(); photo("f1"); tombstone("f1")
        assertEquals(ImagePut.DROPPED, db.embeddingDao().putImageChecked("f1", s, blob, 2))
        assertTrue(vectors(s, "f1").isEmpty())
        photo("f2")
        assertEquals(ImagePut.STORED, db.embeddingDao().putImageChecked("f2", s, blob, 2))
    }

    @Test fun aTombstonedFrameIsNotListedAsMissing() = runBlocking {
        val s = space(); photo("f1"); photo("f2"); tombstone("f1")
        assertEquals(listOf("f2"), db.embeddingDao().missingImageIds(s, 10))
        assertEquals(1, db.embeddingDao().missingImageCount(s))
    }

    @Test fun backfillThatReadBytesBeforeForgetFactStoresNoVector() = runBlocking {
        val s = space(); photo("f1")
        val proposals = ProposalRepository(db.proposalDao(), db.actionDao(), db)
        db.memoryDao().insert(MemoryEntity("m1", "f1", "t", "caption", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "t"))
        assertEquals(listOf("f1"), db.embeddingDao().missingImageIds(s, 10)) // the backfill lists it and reads the bytes ...
        ForgetService(db, proposals) { 5 }.forgetFact("m1") // ... Forget fact commits: tombstone, the frame row and meta stay
        assertEquals(ImagePut.DROPPED, db.embeddingDao().putImageChecked("f1", s, blob, 2)) // ... then the write lands
        assertTrue(vectors(s, "f1").isEmpty())
        assertTrue("forgotten photo must not reappear in photo search", db.frameVectorDao().pageDesc(s, Long.MAX_VALUE, 10).isEmpty())
    }

    @Test fun liveFramesWriteRefusesOnEitherCheckAlone() = runBlocking {
        val s = space()
        photo("noMeta", meta = false)                               // meta missing, no tombstone
        photo("tomb"); tombstone("tomb")                            // meta present, tombstone
        photo("ok")
        val rows = listOf("noMeta", "tomb", "ok").map { FrameVectorEntity(it, s, blob) }
        assertEquals(1, db.embeddingDao().putImageVectorsForLiveFrames(rows, 2))
        assertTrue(vectors(s, "noMeta").isEmpty()); assertTrue(vectors(s, "tomb").isEmpty()); assertEquals(1, vectors(s, "ok").size)
    }
}
