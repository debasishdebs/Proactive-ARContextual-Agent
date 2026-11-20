package com.rokid.context.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImageTextEmbedderTest {
    private class Fake(override val modalities: Set<Modality>, override val imageDims: Int?) : EmbeddingService {
        override val fingerprint = "fp-1"
        override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f) }
        override suspend fun embedQuery(text: String) = floatArrayOf(1f)
        override suspend fun embedQueryFull(text: String) = floatArrayOf(3f, 4f, 9f, 9f)
        var batchCalls = 0
        override suspend fun embedQueriesFull(texts: List<String>): List<FloatArray> { batchCalls++; return texts.map { floatArrayOf(3f, 4f, 9f, 9f) } }
        override suspend fun embedImages(jpegs: List<ByteArray>) = jpegs.map { floatArrayOf(0f, 1f) }
    }

    @Test fun `text-only or width-less embedders give no image-text embedder`() {
        assertNull(EmbeddingServiceImageText.of(Fake(setOf(Modality.TEXT), null)))
        assertNull(EmbeddingServiceImageText.of(Fake(setOf(Modality.TEXT, Modality.IMAGE), null)))
    }

    @Test fun `adapter cuts the text vector to the image width and normalises`() = runBlocking {
        val e = EmbeddingServiceImageText.of(Fake(setOf(Modality.TEXT, Modality.IMAGE), 2))
        assertNotNull(e)
        assertEquals("fp-1", e!!.spaceId); assertEquals(2, e.dim)
        assertEquals(listOf(0.6f, 0.8f), e.embedText("x").toList())
        assertEquals(listOf(0f, 1f), e.embedImage(ByteArray(1)).toList())
    }

    @Test fun `embedTexts is one batched call to the embedder`() = runBlocking {
        val f = Fake(setOf(Modality.TEXT, Modality.IMAGE), 2)
        val e = EmbeddingServiceImageText.of(f)!!
        assertEquals(listOf(listOf(0.6f, 0.8f), listOf(0.6f, 0.8f)), e.embedTexts(listOf("a", "b")).map { it.toList() })
        assertEquals(1, f.batchCalls)
    }
}
