package com.rokid.context.ai.embed.siglip

import com.rokid.context.ai.local.ImageSession
import com.rokid.context.ai.local.OrtImageSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer

class SigLipImageEmbedderTest {
    private class Fake : ImageSession {
        val batches = ArrayList<Int>(); var closed = false; var created = 0
        override fun run(batch: FloatArray, n: Int): FloatArray { batches += n; return FloatArray(n * 768) { (it % 768 + 1).toFloat() + it / 768 } }
        override fun close() { closed = true }
    }

    private val img = SigLipImageEmbedder.Argb(IntArray(16) { 0xFF336699.toInt() }, 4, 4)

    @Test fun identityAndWidth() {
        val e = SigLipImageEmbedder(File("m.onnx"))
        assertEquals(768, e.dim)
        assertEquals("siglip2-b16-224@ba1f3b0|vision_quantized|pooler|bilinear224|l2|d=768", e.spaceId)
        assertTrue(SigLipImageEmbedder(File("m.onnx"), variant = "vision_fp16").spaceId != e.spaceId)
    }

    @Test fun batchesOfEightL2AndLazySingleSession() = runBlocking {
        val fake = Fake(); var created = 0
        val e = SigLipImageEmbedder(File("m.onnx"), batchSize = 8, sessionFactory = { created++; fake }, decode = { img })
        assertEquals(0, created)
        val out = e.embedImages(List(19) { ByteArray(1) })
        assertEquals(listOf(8, 8, 3), fake.batches); assertEquals(1, created); assertEquals(19, out.size)
        out.forEach { v -> assertEquals(768, v.size); assertEquals(1.0, Math.sqrt(v.sumOf { it.toDouble() * it }), 1e-4) }
        assertEquals(768, e.embedImage(ByteArray(1)).size)
        e.close(); assertTrue(fake.closed)
    }

    @Test fun defaultIsOneImagePerRunBecauseTheInt8ModelIsBatchDependent() = runBlocking {
        val fake = Fake()
        val e = SigLipImageEmbedder(File("m.onnx"), sessionFactory = { fake }, decode = { img })
        e.embedImages(List(3) { ByteArray(1) })
        assertEquals(listOf(1, 1, 1), fake.batches)
    }

    @Test fun factoryAsksForPoolerOutputWidth768ByDefault() {
        // The default factory is the by-name OrtImageSession; assert the constants it is built with (the real session is covered by the env-gated parity test).
        assertEquals("pooler_output", SigLipImageEmbedder.OUTPUT_NAME); assertEquals(768, SigLipImageEmbedder.DIM)
    }

    // --- OrtImageSession.rows: the default (Nomic) reading of output 0 must be byte-identical to the code before output selection existed ---

    /** The pre-change loop, verbatim, on a FloatBuffer. */
    private fun legacy(buf: FloatBuffer, shape: LongArray, n: Int): FloatArray {
        val d = 768
        val out = FloatArray(n * d)
        val stride = if (shape.size == 3) (shape[1] * shape[2]).toInt() else d
        for (i in 0 until n) { buf.position(i * stride); buf.get(out, i * d, d) }
        return out
    }

    @Test fun nomicReadingIsUnchangedForThreeAndTwoDimOutputs() {
        val rnd = java.util.Random(3)
        for ((shape, n) in listOf(longArrayOf(3, 197, 768) to 3, longArrayOf(5, 768) to 5, longArrayOf(1, 4, 768) to 1)) {
            val flat = FloatArray(shape.fold(1L) { a, b -> a * b }.toInt()) { rnd.nextFloat() }
            val want = legacy(FloatBuffer.wrap(flat), shape, n)
            val got = OrtImageSession.rows(flat, shape, n, 768)
            assertTrue(want.contentEquals(got))
        }
    }

    @Test fun threeDimOutputIsTokenZeroNotAnEmbeddingSoSigLipMustSelectByName() {
        // SigLIP's output 0 is [n,196,768]; rows() of it is just patch token 0 of each image, not the pooled embedding.
        val flat = FloatArray(2 * 196 * 768) { (it / 768).toFloat() }
        val r = OrtImageSession.rows(flat, longArrayOf(2, 196, 768), 2, 768)
        assertEquals(0f, r[0], 0f); assertEquals(196f, r[768], 0f)
    }

    @Test(expected = IllegalArgumentException::class) fun shortOutputIsRejected() {
        OrtImageSession.rows(FloatArray(10), longArrayOf(2, 768), 2, 768)
    }
}
