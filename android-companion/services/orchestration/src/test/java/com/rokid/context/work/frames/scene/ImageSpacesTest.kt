package com.rokid.context.work.frames.scene

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.Modality
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.IndexConfigException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageSpacesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val spaces = EmbeddingSpaces(db.embeddingDao())

    private fun embedder(dims: Int?) = object : Embedder {
        override val fingerprint = "fp"
        override val modalities = setOf(Modality.TEXT, Modality.IMAGE)
        override val imageDims = dims
        override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
        override suspend fun embedQuery(text: String) = floatArrayOf(1f, 0f)
    }

    private suspend fun activeSpace() {
        db.memoryDao().insert(MemoryEntity("m1", null, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))
        val s = spaces.startBuilding("fp", "{}", 2, "[]", 1); spaces.put("m1", s.id, floatArrayOf(1f, 0f)); spaces.swapIfComplete(s.id)
    }

    @Test fun theTargetWidthIsTheDeclaredImageDimsNotTheTextDim() = runBlocking {
        activeSpace()
        val t = SpaceImageSpaces(spaces, { embedder(768) }).targets().single()
        assertEquals(768, t.imageDims)
        db.close()
    }

    @Test fun anImageEmbedderWithoutImageDimsIsAConfigError() = runBlocking {
        activeSpace()
        try { SpaceImageSpaces(spaces, { embedder(null) }).targets(); fail("must declare imageDims") } catch (_: IndexConfigException) { }
        db.close()
    }
}
