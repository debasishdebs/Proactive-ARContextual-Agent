package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SwapDialogCountsTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    @After fun close() = db.close()

    private suspend fun mem(id: String, frameId: String? = null, review: ReviewState = ReviewState.OK) =
        db.memoryDao().insert(MemoryEntity(id, frameId, "text $id", "note", "", MemoryTier.SHORT, 0.8, review, false, 1, false, ""))

    @Test fun countsReducedVsLost() = runBlocking {
        val a = spaces.startBuilding("fpA", "{}", 2, "[]", 10); mem("m0")
        spaces.put("m0", a.id, floatArrayOf(1f, 0f)); spaces.swapIfComplete(a.id)
        val fv = db.frameVectorDao()
        // stored photo: not counted at all
        db.frameDao().insertIgnore(FrameEntity("stored", 1, "/p", "glasses"))
        listOf("stored", "caption", "source", "cluster", "invalidated", "plain").forEach { fv.upsert(listOf(FrameVectorEntity(it, a.id, byteArrayOf(1)))) }
        mem("cap", frameId = "caption")
        mem("src"); db.frameSourceDao().insertAll(listOf(FrameSourceEntity("src", "source", null, "FACT")))
        mem("clu")
        db.frameClusterDao().upsert(FrameClusterEntity("c1", 1, null, null, "[]", "clu"))
        db.frameMetaDao().upsert(FrameMetaEntity("cluster", null, null, "UNKNOWN", false, null, null, null, null, null, null, "c1", null, null, 1))
        mem("gone", frameId = "invalidated", review = ReviewState.INVALIDATED) // a forgotten caption doesn't keep the photo findable
        assertEquals(SwapImpact(reduced = 3, lost = 2), spaces.swapImpact())
    }
}
