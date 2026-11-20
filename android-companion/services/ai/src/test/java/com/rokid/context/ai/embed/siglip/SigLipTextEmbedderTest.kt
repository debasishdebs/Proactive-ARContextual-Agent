package com.rokid.context.ai.embed.siglip

import com.rokid.context.ai.local.ImageSession
import com.rokid.context.ai.local.TextSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SigLipTextEmbedderTest {
    private class FakeText : TextSession {
        val batches = ArrayList<Int>(); val seen = ArrayList<LongArray>(); var closed = false
        override fun run(ids: LongArray, n: Int, seqLen: Int): FloatArray {
            batches += n; seen += ids.copyOf()
            return FloatArray(n * 768) { (it % 768 + 1).toFloat() + it / 768 }
        }
        override fun close() { closed = true }
    }

    /** Ids = [first char code, EOS, 0...]: lets a test see what reached the session. */
    private class FakeTok(override val maxLength: Int = 64) : TextTokenizer {
        override fun encode(text: String): EncodedText {
            val ids = LongArray(maxLength); ids[0] = (text.firstOrNull() ?: ' ').code.toLong(); ids[1] = 1
            return EncodedText(ids, LongArray(maxLength) { if (it < 2) 1L else 0L })
        }
    }

    @Test fun identityAndWidth() {
        val e = SigLipTextEmbedder(File("t.onnx"), FakeTok())
        assertEquals(768, e.dim)
        assertEquals("siglip2-b16-224@ba1f3b0|text_quantized|lower|eos|pad64|pooler|l2|d=768", e.spaceId)
        assertTrue(SigLipTextEmbedder(File("t.onnx"), FakeTok(), variant = "text_fp16").spaceId != e.spaceId)
        assertTrue(SigLipTextEmbedder(File("t.onnx"), FakeTok(32)).spaceId != e.spaceId)
    }

    @Test fun defaultIsOneTextPerRunBecauseTheInt8ModelIsBatchDependent() = runBlocking {
        val fake = FakeText(); var created = 0
        val e = SigLipTextEmbedder(File("t.onnx"), FakeTok(), sessionFactory = { created++; fake })
        assertEquals(0, created)
        assertFalse(e.isLoaded)
        val out = e.embedTexts(List(3) { "a$it" })
        assertEquals(listOf(1, 1, 1), fake.batches); assertEquals(1, created); assertTrue(e.isLoaded)
        out.forEach { v -> assertEquals(768, v.size); assertEquals(1.0, Math.sqrt(v.sumOf { it.toDouble() * it }), 1e-4) }
        assertTrue(fake.seen.all { it.size == 64 })
        assertEquals(emptyList<FloatArray>(), e.embedTexts(emptyList()))
        e.close(); assertTrue(fake.closed); assertFalse(e.isLoaded)
    }

    @Test fun batchSizeIsHonouredWhenAskedFor() = runBlocking {
        val fake = FakeText()
        val e = SigLipTextEmbedder(File("t.onnx"), FakeTok(), batchSize = 8, sessionFactory = { fake })
        assertEquals(19, e.embedTexts(List(19) { "x$it" }).size)
        assertEquals(listOf(8, 8, 3), fake.batches)
        assertEquals(8L * 64, fake.seen[0].size.toLong())
        assertEquals('x'.code.toLong(), fake.seen[0][64]) // row 1 starts at offset 64
    }

    @Test fun embedTextIsTheSameVectorAsEmbedTexts() = runBlocking {
        val e = SigLipTextEmbedder(File("t.onnx"), FakeTok(), sessionFactory = { FakeText() })
        assertTrue(e.embedText("hello").contentEquals(e.embedTexts(listOf("hello")).single()))
    }

    @Test fun factoryAsksForPoolerOutputWidth768ByDefault() = runBlocking {
        var asked: Triple<String, String, Int>? = null
        val e = SigLipTextEmbedder(File("t.onnx"), FakeTok(), ortFactory = { p, o, w -> asked = Triple(p, o, w); FakeText() })
        e.embedText("x")
        assertEquals(Triple("t.onnx", "pooler_output", 768), asked)
    }

    @Test fun closeThenEmbedReloadsTheSession() = runBlocking {
        var created = 0
        val e = SigLipTextEmbedder(File("t.onnx"), FakeTok(), sessionFactory = { created++; FakeText() })
        e.embedText("a"); e.close(); e.embedText("b")
        assertEquals(2, created)
    }

    @Test fun aWrongTokenizerLengthIsRefused() = runBlocking {
        val bad = object : TextTokenizer {
            override val maxLength = 64
            override fun encode(text: String) = EncodedText(LongArray(5), LongArray(5))
        }
        val e = SigLipTextEmbedder(File("t.onnx"), bad, sessionFactory = { FakeText() })
        try { e.embedText("a"); throw AssertionError("must fail") } catch (_: IllegalStateException) {}
    }

    @Test fun aSessionFailurePropagatesAndTheNextCallRetries() = runBlocking {
        var n = 0
        val e = SigLipTextEmbedder(File("t.onnx"), FakeTok(), sessionFactory = { if (n++ == 0) throw IllegalStateException("boom") else FakeText() })
        try { e.embedText("a"); throw AssertionError("must fail") } catch (_: IllegalStateException) {}
        assertEquals(768, e.embedText("a").size)
    }
}

class SigLipImageTextEmbedderTest {
    private class FakeText : TextSession {
        var created = 0; var closed = 0
        override fun run(ids: LongArray, n: Int, seqLen: Int) = FloatArray(n * 768) { (it % 768 + 1).toFloat() }
        override fun close() { closed++ }
    }
    private class FakeVision : ImageSession {
        var closed = 0
        override fun run(batch: FloatArray, n: Int) = FloatArray(n * 768) { (it % 768 + 2).toFloat() }
        override fun close() { closed++ }
    }
    private val tok = object : TextTokenizer {
        override val maxLength = 64
        override fun encode(text: String) = EncodedText(LongArray(64), LongArray(64))
    }
    private val img = SigLipImageEmbedder.Argb(IntArray(16) { 0xFF336699.toInt() }, 4, 4)

    private class Rig(tok: TextTokenizer, img: SigLipImageEmbedder.Argb) {
        var visionCreated = 0; var textCreated = 0
        val vs = ArrayList<FakeVision>(); val ts = ArrayList<FakeText>()
        val e = SigLipImageTextEmbedder(
            SigLipImageEmbedder(File("v.onnx"), sessionFactory = { visionCreated++; FakeVision().also { vs += it } }, decode = { img }),
            SigLipTextEmbedder(File("t.onnx"), tok, sessionFactory = { textCreated++; FakeText().also { ts += it } }),
        )
    }

    @Test fun spaceIdAndDim() {
        val r = Rig(tok, img)
        assertEquals(768, r.e.dim)
        assertEquals("siglip2-b16-224@ba1f3b0|vision_quantized+text_quantized|pooler|bilinear224+lower.eos.pad64|l2|d=768", r.e.spaceId)
        val other = SigLipImageTextEmbedder(SigLipImageEmbedder(File("v"), variant = "vision_fp16"), SigLipTextEmbedder(File("t"), tok))
        assertTrue(other.spaceId != r.e.spaceId)
    }

    @Test fun towersLoadIndependentlyAndLazily() = runBlocking {
        val r = Rig(tok, img)
        assertEquals(0, r.visionCreated); assertEquals(0, r.textCreated)
        assertEquals(768, r.e.embedImage(ByteArray(1)).size)
        assertEquals(1, r.visionCreated); assertEquals(0, r.textCreated) // the text tower was never touched
        assertEquals(768, r.e.embedText("charminar").size)
        assertEquals(1, r.visionCreated); assertEquals(1, r.textCreated)
        assertEquals(3, r.e.embedTexts(listOf("a", "b", "c")).size)
        assertEquals(1, r.textCreated)
    }

    @Test fun releaseOfOneTowerLeavesTheOtherAndReloadsOnDemand() = runBlocking {
        val r = Rig(tok, img)
        r.e.embedImage(ByteArray(1)); r.e.embedText("x")
        r.e.releaseText()
        assertEquals(1, r.ts[0].closed); assertEquals(0, r.vs[0].closed)
        r.e.embedImage(ByteArray(1)); assertEquals(1, r.visionCreated) // vision still loaded
        r.e.embedText("y"); assertEquals(2, r.textCreated) // text reloaded
        r.e.releaseVision()
        assertEquals(1, r.vs[0].closed); assertEquals(2, r.ts.size) // second text session still open
        assertEquals(0, r.ts[1].closed)
        r.e.embedImage(ByteArray(1)); assertEquals(2, r.visionCreated)
        r.e.close()
        assertEquals(1, r.ts[1].closed); assertEquals(1, r.vs[1].closed)
    }

    @Test fun releaseWithNothingLoadedIsHarmless() {
        val r = Rig(tok, img)
        r.e.releaseText(); r.e.releaseVision(); r.e.close()
        assertEquals(0, r.visionCreated + r.textCreated)
    }

    @Test fun aTextFailureDoesNotBreakTheImageHalf() = runBlocking {
        val e = SigLipImageTextEmbedder(
            SigLipImageEmbedder(File("v"), sessionFactory = { FakeVision() }, decode = { img }),
            SigLipTextEmbedder(File("t"), tok, sessionFactory = { throw IllegalStateException("no text model") }),
        )
        try { e.embedText("a"); throw AssertionError("must fail") } catch (_: IllegalStateException) {}
        assertEquals(768, e.embedImage(ByteArray(1)).size)
    }

    @Test fun textAndImageVectorsAreUnitLength() = runBlocking {
        val r = Rig(tok, img)
        for (v in listOf(r.e.embedImage(ByteArray(1)), r.e.embedText("a"))) assertEquals(1.0, Math.sqrt(v.sumOf { it.toDouble() * it }), 1e-4)
    }
}
