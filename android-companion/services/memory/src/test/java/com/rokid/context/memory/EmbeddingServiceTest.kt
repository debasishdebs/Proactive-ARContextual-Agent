package com.rokid.context.memory

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingServiceTest {
    private val textOnly = object : EmbeddingService {
        override val fingerprint = "fp"
        override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f) }
        override suspend fun embedQuery(text: String) = floatArrayOf(1f)
    }

    @Test fun textOnlyServiceRejectsImages() = runTest {
        assertEquals(setOf(Modality.TEXT), textOnly.modalities)
        val e = runCatching { textOnly.embedImages(listOf(ByteArray(1))) }.exceptionOrNull()
        assertTrue(e is UnsupportedOperationException)
        assertEquals(1, textOnly.embedQueryFull("q").size) // defaults to embedQuery
    }
}
