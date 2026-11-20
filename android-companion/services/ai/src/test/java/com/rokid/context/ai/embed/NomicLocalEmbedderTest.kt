package com.rokid.context.ai.embed

import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.local.NomicText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The on-device path: bge stays exactly as before; the Nomic catalog model always goes through LayerNorm. */
class NomicLocalEmbedderTest {
    private class Calls { val texts = mutableListOf<String>(); val normalize = mutableListOf<Boolean>() }

    private fun client(calls: Calls, raw: (String) -> FloatArray) = EmbeddingClient(native = { _, texts, normalize ->
        calls.normalize += normalize; calls.texts += texts; texts.map(raw)
    })

    private fun local(model: String) = Endpoint("l", "L", EndpointKind.LLAMACPP_LOCAL, "/m/$model.gguf", null, listOf(model), setOf(Capability.EMBEDDINGS), 1)

    @Test fun bgeBehaviourUnchanged() = runBlocking {
        val calls = Calls()
        val spec = EmbeddingSpec("l", EndpointKind.LLAMACPP_LOCAL, "/m/bge.gguf", null, "bge-small-en-v1.5-q8", null, null, 4, true, "", "", 2_000)
        val emb = EndpointEmbedder(spec, local("bge-small-en-v1.5-q8"), null, client(calls) { floatArrayOf(3f, 4f, 0f, 0f) })
        val v = emb.embedDocuments(listOf("milk")).single()
        assertArrayEquals(floatArrayOf(0.6f, 0.8f, 0f, 0f), v, 1e-6f)
        assertEquals(listOf("milk"), calls.texts) // no prefix
        assertEquals(listOf(true), calls.normalize) // native L2 stays on
        assertEquals(spec.fingerprint, emb.fingerprint) // same sha-256 space identity as before
        assertArrayEquals(v, emb.embedQueryFull("milk").let { it }, 1e-6f)
    }

    @Test fun nomicGoesThroughLayerNormAndShortens() = runBlocking {
        val calls = Calls()
        val raw = FloatArray(NomicText.FULL_DIMS) { (it % 7).toFloat() + 10f }
        val spec = EmbeddingSpec("l", EndpointKind.LLAMACPP_LOCAL, "/m/n.gguf", null, NomicText.CATALOG_ID, null, null, 256, true, NomicText.DOC, NomicText.QUERY, 2_000)
        val emb = EndpointEmbedder(spec, local(NomicText.CATALOG_ID), null, client(calls) { raw })
        val doc = emb.embedDocuments(listOf("milk")).single()
        assertEquals(listOf("search_document: milk"), calls.texts)
        assertEquals(listOf(false), calls.normalize)
        assertArrayEquals(NomicText.shorten(NomicText.layerNorm(raw), 256), doc, 1e-6f)
        assertEquals(256, doc.size)
        assertEquals(1.0, sqrt(doc.sumOf { (it * it).toDouble() }), 1e-5)
        assertEquals(com.rokid.context.ai.local.NomicBundle.fingerprint(), emb.fingerprint)
        val q = emb.embedQuery("milk"); assertEquals(256, q.size)
        assertEquals("search_query: milk", calls.texts.last())
        val full = emb.embedQueryFull("milk")
        assertEquals(768, full.size); assertArrayEquals(NomicText.shorten(NomicText.layerNorm(raw), 768), full, 1e-6f)
        assertTrue(emb.modalities == setOf(com.rokid.context.memory.Modality.TEXT))
    }

    @Test fun nomicBatchesQueriesInOneNativeCallWithTheSameVectors() = runBlocking {
        val calls = Calls(); var batches = 0
        val raw = FloatArray(NomicText.FULL_DIMS) { (it % 7).toFloat() + 10f }
        val c = EmbeddingClient(native = { _, texts, normalize -> batches++; calls.normalize += normalize; calls.texts += texts; texts.map { raw } })
        val spec = EmbeddingSpec("l", EndpointKind.LLAMACPP_LOCAL, "/m/n.gguf", null, NomicText.CATALOG_ID, null, null, 256, true, NomicText.DOC, NomicText.QUERY, 2_000)
        val emb = EndpointEmbedder(spec, local(NomicText.CATALOG_ID), null, c)
        val out = emb.embedQueriesFull(listOf("a", "b", "c"))
        assertEquals(1, batches); assertEquals(listOf("search_query: a", "search_query: b", "search_query: c"), calls.texts)
        assertEquals(3, out.size)
        out.forEach { assertArrayEquals(NomicText.shorten(NomicText.layerNorm(raw), 768), it, 1e-6f) }
    }
}
