package com.rokid.context.ai.embed.siglip

import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Parity tests against ids produced by transformers' GemmaTokenizer (see resources/siglip/make_golden.py).
 * The real vocabulary (tokenizer.model, 4.2 MB, sha256 61a7b147...c8e2) is DATA and is not committed: set the env var
 * (or system property) SIGLIP_TOKENIZER_FILE to it, otherwise the parity tests are skipped.
 * The synthetic-model tests run everywhere.
 */
class SigLipTokenizerTest {

    private fun tokenizerFile(): File? {
        val p = System.getenv("SIGLIP_TOKENIZER_FILE") ?: System.getProperty("SIGLIP_TOKENIZER_FILE")
        return p?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
    }

    private class Golden(val text: String, val ids: LongArray)

    private fun golden(): List<Golden> {
        val raw = javaClass.getResourceAsStream("/siglip/siglip2_tokens_golden.json")!!.readBytes().toString(Charsets.UTF_8)
        return (Json.parseToJsonElement(raw) as JsonArray).map {
            val o = it.jsonObject
            Golden(o["text"]!!.jsonPrimitive.content, o["ids"]!!.jsonArray.map { x -> x.jsonPrimitive.int.toLong() }.toLongArray())
        }
    }

    @Test fun goldenFileIsWellFormed() {
        val g = golden()
        assertTrue("need >= 40 golden strings, have ${g.size}", g.size >= 40)
        for (e in g) {
            assertEquals(e.text, 64, e.ids.size)
            assertTrue(e.text, e.ids.contains(1L)) // EOS always present (also after truncation)
        }
        assertTrue(g.any { it.text.isEmpty() })
        assertTrue(g.any { it.text.length >= 200 })
    }

    @Test fun realVocabularyMatchesAllGoldenIds() {
        val f = tokenizerFile()
        assumeTrue("SIGLIP_TOKENIZER_FILE not set; skipping parity", f != null)
        val tok = SigLipTokenizer(f!!)
        val bad = ArrayList<String>()
        val g = golden()
        for (e in g) {
            val got = tok.encode(e.text)
            if (!got.ids.contentEquals(e.ids)) bad.add("'${e.text.take(40)}': want ${e.ids.take(12)} got ${got.ids.take(12)}")
            val realLen = e.ids.lastIndexOf(1L) + 1 // everything up to the final EOS is real, the rest is padding
            assertEquals(64, got.attentionMask.size)
            for (k in 0 until 64) assertEquals("mask[$k] of '${e.text.take(30)}'", if (k < realLen) 1L else 0L, got.attentionMask[k])
        }
        println("SigLipTokenizer parity: ${g.size - bad.size} of ${g.size}")
        assertTrue("mismatches:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun realVocabularyEncodesAPhraseInUnderFiveMilliseconds() {
        val f = tokenizerFile()
        assumeTrue("SIGLIP_TOKENIZER_FILE not set; skipping perf", f != null)
        val tok = SigLipTokenizer(f!!)
        val phrase = "this is a photo of the old hot wheels race track box on my desk near the window"
        repeat(300) { tok.encode(phrase) } // warm up (also loads the vocabulary)
        val runs = 200
        val t0 = System.nanoTime()
        repeat(runs) { tok.encode(phrase) }
        val ms = (System.nanoTime() - t0) / 1e6 / runs
        println("SigLipTokenizer encode: %.3f ms per 16-word phrase".format(ms))
        assertTrue("encode took $ms ms", ms < 5.0)
    }

    // ---- synthetic model: runs without the real vocabulary ------------------------------------------------------

    private fun varint(o: ByteArrayOutputStream, v: Int) {
        var x = v
        while (x >= 0x80) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }
        o.write(x)
    }

    private fun piece(o: ByteArrayOutputStream, text: String, score: Float, type: Int) {
        val p = ByteArrayOutputStream()
        val b = text.toByteArray(Charsets.UTF_8)
        p.write(0x0A); varint(p, b.size); p.write(b)
        p.write(0x15)
        val bits = java.lang.Float.floatToIntBits(score)
        for (s in 0 until 4) p.write((bits ushr (8 * s)) and 0xFF)
        p.write(0x18); varint(p, type)
        o.write(0x0A); varint(o, p.size()); o.write(p.toByteArray())
    }

    /** ids: 0 pad, 1 eos, 2 bos, 3 unk, 4 <m> user-defined, 5.. normal, then 256 byte pieces. */
    private fun tinyModel(): ByteArray {
        val o = ByteArrayOutputStream()
        piece(o, "<pad>", 0f, 3); piece(o, "<eos>", 0f, 3); piece(o, "<bos>", 0f, 3); piece(o, "<unk>", 0f, 2)
        piece(o, "<m>", 0f, 4)
        piece(o, "▁", -1f, 1); piece(o, "a", -2f, 1); piece(o, "b", -3f, 1); piece(o, "c", -4f, 1)
        piece(o, "ab", -5f, 1); piece(o, "bc", -6f, 1); piece(o, "▁a", -7f, 1);
        for (k in 0 until 256) piece(o, "<0x%02X>".format(k), 0f, 6)
        return o.toByteArray()
    }

    @Test fun syntheticMergesPicksBestScoreThenLeftmost() {
        val tok = SigLipTokenizer.fromBytes(tinyModel(), maxLength = 8)
        // "abc": candidates ab(-5) and bc(-6): ab wins -> [ab, c]
        assertArrayEquals(longArrayOf(ID_AB, ID_C, 1, 0, 0, 0, 0, 0), tok.encode("ABC").ids) // also lowercases
        // "a a": "a", "▁a" (merge -7)
        assertArrayEquals(longArrayOf(ID_A, ID_SP_A, 1, 0, 0, 0, 0, 0), tok.encode("a a").ids)
    }

    @Test fun syntheticByteFallbackAddedTokensEosPaddingAndTruncation() {
        val tok = SigLipTokenizer.fromBytes(tinyModel(), maxLength = 6)
        val byteBase = 12L
        // "é" is not in the vocab: UTF-8 C3 A9 as two byte pieces
        assertArrayEquals(longArrayOf(byteBase + 0xC3, byteBase + 0xA9, 1, 0, 0, 0), tok.encode("é").ids)
        assertArrayEquals(longArrayOf(ID_A, 4, ID_B, 1, 0, 0), tok.encode("a<m>b").ids)
        assertArrayEquals(longArrayOf(1, 0, 0, 0, 0, 0), tok.encode("").ids)
        assertArrayEquals(longArrayOf(1, 1, 0, 0, 0, 0), tok.encode("<eos>").ids) // typed EOS is a token, plus the real EOS
        // truncation keeps EOS last: 8 'a's -> 5 tokens + EOS
        val t = tok.encode("aaaaaaaa")
        assertArrayEquals(longArrayOf(ID_A, ID_A, ID_A, ID_A, ID_A, 1), t.ids)
        assertArrayEquals(longArrayOf(1, 1, 1, 1, 1, 1), t.attentionMask)
        assertArrayEquals(longArrayOf(1, 0, 0, 0, 0, 0), tok.encode("").attentionMask)
    }

    @Test fun rejectsGarbageModel() {
        val bad = runCatching { SigLipTokenizer.fromBytes(ByteArray(0)).encode("a") }
        assertTrue(bad.isFailure)
    }

    private companion object {
        // ids in tinyModel(): 5 "▁", 6 a, 7 b, 8 c, 9 ab, 10 bc, 11 "▁a", then byte pieces from 12
        const val ID_A = 6L; const val ID_B = 7L; const val ID_C = 8L; const val ID_AB = 9L; const val ID_SP_A = 11L
    }
}
