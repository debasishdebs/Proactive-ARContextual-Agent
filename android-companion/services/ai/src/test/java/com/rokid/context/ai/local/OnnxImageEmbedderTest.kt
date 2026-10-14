package com.rokid.context.ai.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.ProviderConfigException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnnxImageEmbedderTest {
    @Before @After fun reset() = OnnxImageEmbedder.resetUnavailableForTest()

    private val fakePre: (ByteArray, NomicVision.PreprocessConfig) -> FloatArray =
        { b, _ -> FloatArray(3 * 224 * 224).also { it[0] = b[0].toFloat() } }

    private class Fake : ImageSession {
        val batches = ArrayList<Int>()
        var closed = false
        // row i: element 0 = the marker carried by image i (so order is checkable), rest = i+1 ramp
        override fun run(batch: FloatArray, n: Int): FloatArray {
            batches += n
            val per = 3 * 224 * 224
            return FloatArray(n * 768) { idx -> val i = idx / 768; val d = idx % 768; if (d == 0) batch[i * per] else (d + 1).toFloat() }
        }
        override fun close() { closed = true }
    }

    @Test fun batchesOfEight() = runBlocking {
        val fake = Fake(); var created = 0
        val e = OnnxImageEmbedder("m.onnx", 256, sessionFactory = { created++; fake }, preprocess = fakePre)
        val out = e.embed(List(19) { byteArrayOf(it.toByte()) })
        assertEquals(listOf(8, 8, 3), fake.batches)
        assertEquals(1, created)
        assertEquals(19, out.size)
        out.forEach { v ->
            assertEquals(256, v.size)
            assertEquals(1.0, Math.sqrt(v.sumOf { it.toDouble() * it }), 1e-4)
        }
        // order preserved: image 7 and 8 differ only through their element-0 marker
        assertTrue(abs(out[7][0] - out[8][0]) > 0f)
        e.close(); assertTrue(fake.closed)
    }

    @Test fun failureAfterRetryMarksUnavailable() = runBlocking {
        var attempts = 0
        val e = OnnxImageEmbedder("secret/path/m.onnx", 256, sessionFactory = { attempts++; error("boom /secret/path") }, preprocess = fakePre)
        try { e.embed(listOf(byteArrayOf(1))); fail() } catch (x: ProviderConfigException) {
            assertTrue(!x.message!!.contains("secret"))
        }
        assertEquals(2, attempts)
        assertTrue(OnnxImageEmbedder.isUnavailable)
        // a fresh embedder in the same process does not try again
        val e2 = OnnxImageEmbedder("m.onnx", 256, sessionFactory = { attempts++; Fake() }, preprocess = fakePre)
        try { e2.embed(listOf(byteArrayOf(1))); fail() } catch (_: ProviderConfigException) {}
        assertEquals(2, attempts)
    }

    @Test fun retrySucceedsOnSecondAttempt() = runBlocking {
        var attempts = 0
        val e = OnnxImageEmbedder("m.onnx", 768, sessionFactory = { if (attempts++ == 0) error("x") else Fake() }, preprocess = fakePre)
        assertEquals(1, e.embed(listOf(byteArrayOf(1))).size)
        assertTrue(!OnnxImageEmbedder.isUnavailable)
    }
}
