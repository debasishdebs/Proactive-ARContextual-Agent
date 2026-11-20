package com.rokid.context.ai.embed.siglip

import com.rokid.context.ai.local.OrtTextSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * ONNX parity for the SigLIP 2 text tower. Skipped unless env SIGLIP_TEXT_ONNX (text_model_quantized.onnx; url + sha256 in [SigLipTextEmbedder]) AND SIGLIP_TOKENIZER_FILE (tokenizer.model)
 * point at files; neither is in git. Needs a DESKTOP ONNX Runtime on the test classpath (ORT_DESKTOP_JAR, see build.gradle.kts) or it is skipped with that reason. The cross-modal test
 * additionally needs SIGLIP_VISION_ONNX.
 *
 * Reference: python onnxruntime on the SAME int8 file, HF tokenizer ids, one text per run (make_text_golden.py). The cross-modal reference is the SigLIP logit formula
 * exp(logit_scale) * cos + logit_bias computed from python onnxruntime embeddings of both towers: PyTorch/HF SiglipModel was not available offline, so this is NOT an int8-vs-fp32 check.
 */
class SigLipTextParityTest {
    private fun file(name: String): File? = (System.getenv(name) ?: System.getProperty(name))?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    private class Golden(val texts: List<Triple<String, LongArray, FloatArray>>, val b8: DoubleArray, val ball: DoubleArray,
                         val captions: List<Pair<String, FloatArray>>, val imageNames: List<String>, val scale: Double, val bias: Double, val logits: Array<DoubleArray>)

    private fun golden(): Golden {
        val raw = Golden::class.java.getResourceAsStream("/siglip/siglip2_text_golden.json")!!.readBytes().toString(Charsets.UTF_8)
        val o = Json.parseToJsonElement(raw).jsonObject
        fun fl(e: kotlinx.serialization.json.JsonElement) = e.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
        return Golden(
            o["texts"]!!.jsonArray.map { t -> val m = t.jsonObject; Triple(m["text"]!!.jsonPrimitive.content, m["ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }.toLongArray(), fl(m["embedding_l2"]!!)) },
            o["batch8_vs_single_cos"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
            o["batchAll_vs_single_cos"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
            o["captions"]!!.jsonArray.map { c -> c.jsonObject["text"]!!.jsonPrimitive.content to fl(c.jsonObject["embedding_l2"]!!) },
            o["image_names"]!!.jsonArray.map { it.jsonPrimitive.content },
            o["logit_scale"]!!.jsonPrimitive.double, o["logit_bias"]!!.jsonPrimitive.double,
            o["logits"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray() }.toTypedArray(),
        )
    }

    private inline fun <T> nativeOrSkip(block: () -> T): T = try { block() } catch (x: Throwable) {
        if (x is UnsatisfiedLinkError || x is NoClassDefFoundError || x is ExceptionInInitializerError) assumeNoException("no desktop ONNX Runtime on the classpath (ORT_DESKTOP_JAR)", x)
        throw x
    }

    @Test fun kotlinTextPipelineMatchesPythonReference() = runBlocking {
        val model = file("SIGLIP_TEXT_ONNX"); val tokFile = file("SIGLIP_TOKENIZER_FILE")
        assumeTrue("SIGLIP_TEXT_ONNX / SIGLIP_TOKENIZER_FILE not set: parity skipped", model != null && tokFile != null)
        val g = golden()
        assertTrue(g.texts.size >= 30)
        val tok = SigLipTokenizer(tokFile!!)
        val e = SigLipTextEmbedder(model!!, tok)
        val out = nativeOrSkip { e.embedTexts(g.texts.map { it.first }) }
        var min = 1.0; var sum = 0.0
        g.texts.forEachIndexed { i, (text, ids, ref) ->
            assertTrue("ids differ for '$text'", ids.contentEquals(tok.encode(text).ids))
            assertEquals(768, out[i].size)
            assertEquals(1.0, Math.sqrt(out[i].sumOf { it.toDouble() * it }), 1e-4)
            val c = cosine(out[i], ref)
            println("SIGLIP_TEXT_PARITY cos=${"%.6f".format(c)} '${text.take(40).replace("\n", " ")}'")
            assertTrue("'${text.take(30)}': cos $c", c >= 0.999)
            if (c < min) min = c; sum += c
        }
        println("SIGLIP_TEXT_PARITY n=${g.texts.size} min cos=$min mean cos=${sum / g.texts.size}")
        e.close()
    }

    @Test fun batchDependenceOfTheInt8TextTower() = runBlocking {
        val model = file("SIGLIP_TEXT_ONNX"); val tokFile = file("SIGLIP_TOKENIZER_FILE")
        assumeTrue("SIGLIP_TEXT_ONNX / SIGLIP_TOKENIZER_FILE not set", model != null && tokFile != null)
        val g = golden()
        val tok = SigLipTokenizer(tokFile!!)
        val single = SigLipTextEmbedder(model!!, tok)
        val s = nativeOrSkip { single.embedTexts(g.texts.map { it.first }) }
        SigLipTextEmbedder(model, tok, batchSize = 8).use { b8 ->
            val v = b8.embedTexts(g.texts.map { it.first })
            val cs = s.indices.map { cosine(v[it], s[it]) }
            println("SIGLIP_TEXT_BATCH kotlin batch-of-8 vs single: min=${cs.min()} mean=${cs.average()}; python (golden) min=${g.b8.min()} mean=${g.b8.average()}")
            assertTrue(cs.min() < 0.9999) // the dependence is real, which is why the default batch is 1
        }
        SigLipTextEmbedder(model, tok, batchSize = g.texts.size).use { b ->
            val v = b.embedTexts(g.texts.map { it.first })
            val cs = s.indices.map { cosine(v[it], s[it]) }
            println("SIGLIP_TEXT_BATCH kotlin batch-of-${g.texts.size} vs single: min=${cs.min()} mean=${cs.average()}; python min=${g.ball.min()} mean=${g.ball.average()}")
        }
        single.close()
    }

    @Test fun outputsByNameAndInputsOfTheRealModel() {
        val model = file("SIGLIP_TEXT_ONNX")
        assumeTrue("SIGLIP_TEXT_ONNX not set", model != null)
        nativeOrSkip { OrtTextSession(model!!.path, "pooler_output", 768).close() }
        try { OrtTextSession(model!!.path, "nope", 768).close(); throw AssertionError("missing output must fail") } catch (_: IllegalArgumentException) {}
        try { OrtTextSession(model!!.path, "pooler_output", 768, idsName = "attention_mask").close(); throw AssertionError("missing input must fail") } catch (_: IllegalArgumentException) {}
        // Output 0 (last_hidden_state) is a [n,64,768] token map, NOT the embedding: asking for it as a 768-wide row would read token 0 of the first row only.
        val tok = SigLipTokenizer(file("SIGLIP_TOKENIZER_FILE") ?: return)
        val ids = tok.encode("charminar").ids
        val ref = golden().texts.first { it.first == "Charminar" }.third
        OrtTextSession(model!!.path, "last_hidden_state", 768).use { s ->
            val wrong = s.run(ids, 1, 64)
            val c = cosine(wrong, ref)
            println("SIGLIP_TEXT_PARITY last_hidden_state token0 vs reference cos=$c (must be far below 0.999)")
            assertTrue(c < 0.99)
        }
    }

    @Test fun crossModalOrderingMatchesPythonLogitsOnTheSyntheticSet() = runBlocking {
        val tm = file("SIGLIP_TEXT_ONNX"); val tokFile = file("SIGLIP_TOKENIZER_FILE"); val vm = file("SIGLIP_VISION_ONNX")
        assumeTrue("SIGLIP_TEXT_ONNX / SIGLIP_TOKENIZER_FILE / SIGLIP_VISION_ONNX not all set", tm != null && tokFile != null && vm != null)
        val g = golden()
        val cases = VisionCase.load()
        assertEquals(g.imageNames, cases.map { it.name })
        val vision = SigLipImageEmbedder(vm!!)
        val embedder = SigLipImageTextEmbedder(vision, SigLipTextEmbedder(tm!!, SigLipTokenizer(tokFile!!)))
        val imgs = nativeOrSkip { runBlocking { vision.embedArgb(cases.map { SigLipImageEmbedder.Argb(it.argb(), it.w, it.h) }) } }
        val caps = embedder.embedTexts(g.captions.map { it.first })
        // The caption vectors themselves must match python's.
        caps.forEachIndexed { i, c -> assertTrue("caption ${g.captions[i].first}", cosine(c, g.captions[i].second) >= 0.999) }
        val scale = Math.exp(g.scale)
        val kLogits = Array(imgs.size) { i -> DoubleArray(caps.size) { j -> scale * cosine(imgs[i], caps[j]) + g.bias } }
        var exactRows = 0; var maxAbs = 0.0
        for (i in kLogits.indices) {
            val ko = kLogits[i].indices.sortedByDescending { kLogits[i][it] }; val po = g.logits[i].indices.sortedByDescending { g.logits[i][it] }
            if (ko == po) exactRows++
            for (j in kLogits[i].indices) maxAbs = maxOf(maxAbs, Math.abs(kLogits[i][j] - g.logits[i][j]))
            println("SIGLIP_CROSS image=${cases[i].name} kotlinOrder=$ko pythonOrder=$po")
            val margin = g.logits[i][po[0]] - g.logits[i][po[1]]
            if (margin > 0.5) assertEquals("top caption of ${cases[i].name} (python margin $margin)", po[0], ko[0])
        }
        for (j in caps.indices) {
            val ko = kLogits.indices.maxByOrNull { kLogits[it][j] }!!; val po = g.logits.indices.maxByOrNull { g.logits[it][j] }!!
            println("SIGLIP_CROSS caption='${g.captions[j].first}' kotlinBestImage=${cases[ko].name} pythonBestImage=${cases[po].name}")
        }
        println("SIGLIP_CROSS rows with identical full ordering: $exactRows/${kLogits.size}; max |kotlin logit - python logit| = $maxAbs")
        assertTrue("logits drifted: $maxAbs", maxAbs < 1.5)
        embedder.close()
    }
}
