package com.rokid.context.ai.embed

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.local.ImageSession
import com.rokid.context.ai.local.NomicBundle
import com.rokid.context.ai.local.NomicText
import com.rokid.context.ai.local.NomicVision
import com.rokid.context.ai.local.OnnxImageEmbedder
import com.rokid.context.memory.ImageDecodeException
import com.rokid.context.memory.Modality
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EndpointEmbedderTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before @After fun reset() = OnnxImageEmbedder.resetUnavailableForTest()

    private val client = EmbeddingClient(native = { _, texts, _ -> texts.map { FloatArray(NomicText.FULL_DIMS) { i -> (i % 7) + 10f } } })

    private fun endpoint(gguf: File, model: String) = Endpoint("l", "L", EndpointKind.LLAMACPP_LOCAL, gguf.path, null, listOf(model), setOf(Capability.EMBEDDINGS), 1)
    private fun nomicSpec(gguf: File, imageDim: Int? = NomicVision.STORED_DIMS) =
        EmbeddingSpec("l", EndpointKind.LLAMACPP_LOCAL, gguf.path, null, NomicText.CATALOG_ID, null, null, 256, true, NomicText.DOC, NomicText.QUERY, 2_000, imageDim)

    private class FakeSession : ImageSession {
        val sizes = ArrayList<Int>()
        override fun run(batch: FloatArray, n: Int): FloatArray { sizes += n; return FloatArray(n * 768) { (it % 768) + 1f } }
        override fun close() {}
    }

    private fun embedderWith(gguf: File, imageDim: Int?, fake: FakeSession = FakeSession(), pre: (ByteArray, NomicVision.PreprocessConfig) -> FloatArray = { _, _ -> FloatArray(3 * 224 * 224) }) =
        EndpointEmbedder(nomicSpec(gguf, imageDim), endpoint(gguf, NomicText.CATALOG_ID), null, client,
            imageEmbedderFor = { p, d -> OnnxImageEmbedder(p, d, sessionFactory = { fake }, preprocess = pre) })

    private fun withVision(): File = File(tmp.newFolder(), "nomic-embed-text-v1.5.Q8_0.gguf").also { it.writeBytes(byteArrayOf(1)) }.also { NomicBundle.visionPath(it.path).writeBytes(byteArrayOf(1)) }
    private fun withoutVision(): File = File(tmp.newFolder(), "nomic-embed-text-v1.5.Q8_0.gguf").also { it.writeBytes(byteArrayOf(1)) }

    @Test fun nomicDeclaresImageWhenVisionFilePresent() {
        val e = embedderWith(withVision(), NomicVision.STORED_DIMS)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), e.modalities)
        assertEquals(NomicVision.STORED_DIMS, e.imageDims)
        assertEquals(NomicBundle.fingerprint(NomicVision.STORED_DIMS), e.fingerprint)
    }

    @Test fun noVisionFileTextOnlySameFingerprint() {
        val withV = embedderWith(withVision(), NomicVision.STORED_DIMS)
        val without = embedderWith(withoutVision(), NomicVision.STORED_DIMS)
        assertEquals(setOf(Modality.TEXT), without.modalities)
        assertNull(without.imageDims)
        assertEquals("the fingerprint never flips with the file", withV.fingerprint, without.fingerprint)
        runBlocking { try { without.embedImages(listOf(byteArrayOf(1))); fail("no vision file") } catch (_: Exception) { /* retryable, not a decode failure */ } }
    }

    @Test fun unavailableRuntimeIsTextOnly() = runBlocking {
        val gguf = withVision()
        val e = EndpointEmbedder(nomicSpec(gguf), endpoint(gguf, NomicText.CATALOG_ID), null, client,
            imageEmbedderFor = { p, d -> OnnxImageEmbedder(p, d, sessionFactory = { error("no ort") }, preprocess = { _, _ -> FloatArray(3 * 224 * 224) }) })
        assertTrue(Modality.IMAGE in e.modalities)
        try { e.embedImages(listOf(byteArrayOf(1))); fail() } catch (x: Exception) { assertFalse(x is ImageDecodeException) }
        assertEquals("a runtime that cannot start drops IMAGE", setOf(Modality.TEXT), e.modalities)
    }

    @Test fun nativeLoadErrorDegradesToTextOnly() = runBlocking {
        val gguf = withVision()
        val e = EndpointEmbedder(nomicSpec(gguf), endpoint(gguf, NomicText.CATALOG_ID), null, client,
            imageEmbedderFor = { p, d -> OnnxImageEmbedder(p, d, sessionFactory = { throw UnsatisfiedLinkError("libonnxruntime") }, preprocess = { _, _ -> FloatArray(3 * 224 * 224) }) })
        try { e.embedImages(listOf(byteArrayOf(1))); fail() } catch (x: com.rokid.context.ai.ProviderConfigException) { /* typed, retryable */ }
        assertEquals(setOf(Modality.TEXT), e.modalities)
        assertNull(e.imageDims)
    }

    @Test fun embedImagesUsesStoredDims() = runBlocking {
        val fake = FakeSession()
        val e = embedderWith(withVision(), NomicVision.STORED_DIMS, fake)
        val out = e.embedImages(List(3) { byteArrayOf(it.toByte()) })
        assertEquals(3, out.size)
        out.forEach { assertEquals(NomicVision.STORED_DIMS, it.size); assertEquals(1.0, Math.sqrt(it.sumOf { x -> x.toDouble() * x }), 1e-4) }
        assertEquals(listOf(3), fake.sizes)
        val e256 = embedderWith(withVision(), 256)
        assertEquals(256, e256.imageDims); assertEquals(256, e256.embedImages(listOf(byteArrayOf(1))).single().size)
    }

    @Test fun undecodablePhotoIsATypedDecodeFailure() = runBlocking {
        val e = embedderWith(withVision(), NomicVision.STORED_DIMS, pre = { _, _ -> throw ImageDecodeException("bad") })
        try { e.embedImages(listOf(byteArrayOf(1))); fail() } catch (_: ImageDecodeException) { /* expected */ }
    }

    @Test fun bgeUnchanged() = runBlocking {
        val gguf = tmp.newFile("bge-small-en-v1.5-q8_0.gguf")
        val spec = EmbeddingSpec("l", EndpointKind.LLAMACPP_LOCAL, gguf.path, null, "bge-small-en-v1.5-q8", null, null, 4, true, "", "", 2_000)
        val e = EndpointEmbedder(spec, endpoint(gguf, "bge-small-en-v1.5-q8"), null, EmbeddingClient(native = { _, t, _ -> t.map { floatArrayOf(3f, 4f, 0f, 0f) } }))
        assertEquals(spec.fingerprint, e.fingerprint)
        assertEquals(setOf(Modality.TEXT), e.modalities)
        assertNull(e.imageDims)
    }
}
