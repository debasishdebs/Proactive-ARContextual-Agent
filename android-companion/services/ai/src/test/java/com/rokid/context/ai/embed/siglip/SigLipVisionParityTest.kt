package com.rokid.context.ai.embed.siglip

import com.rokid.context.ai.local.OrtImageSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * ONNX parity for the SigLIP 2 vision tower. Skipped unless env SIGLIP_VISION_ONNX (or system property) points at
 * vision_model_quantized.onnx (url + sha256 in [SigLipImageEmbedder]); the model is NOT in git. It also needs a DESKTOP ONNX Runtime on the test classpath
 * (the repo only ships onnxruntime-android): set ORT_DESKTOP_JAR to com.microsoft.onnxruntime:onnxruntime:1.31.0's jar (see build.gradle.kts); without it the
 * native load fails and the test is skipped with that reason.
 *
 * Reference embeddings in the golden JSON come from python onnxruntime (pooler_output, L2) run on the HF SiglipImageProcessor tensors.
 *  (a) reference tensor -> Kotlin ORT path: isolates the session / output-by-name code (expect ~1.0).
 *  (b) Kotlin-preprocessed source -> Kotlin ORT path: the whole pipeline (>= 0.999).
 */
class SigLipVisionParityTest {
    private fun modelFile(): File? {
        val p = System.getenv("SIGLIP_VISION_ONNX") ?: System.getProperty("SIGLIP_VISION_ONNX")
        return p?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
    }

    private fun rssMb(): Long = try {
        // `$PPID` of the child shell is this JVM.
        val p = ProcessBuilder("sh", "-c", "ps -o rss= -p \$PPID").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().toLong() / 1024
    } catch (_: Throwable) { -1 }

    @Test fun kotlinOrtPathMatchesPythonReference() = runBlocking {
        val model = modelFile()
        assumeTrue("SIGLIP_VISION_ONNX not set: parity skipped", model != null)
        val cases = VisionCase.load().filter { it.refEmbedding != null }
        assertTrue(cases.size >= 5)
        val e = SigLipImageEmbedder(model!!)
        val rss0 = rssMb()
        val t0 = System.nanoTime()
        val fromRef = try { e.embedTensors(cases.map { it.refTensor() }) } catch (x: Throwable) {
            if (x is UnsatisfiedLinkError || x is NoClassDefFoundError || x is ExceptionInInitializerError) assumeNoException("no desktop ONNX Runtime on the classpath (ORT_DESKTOP_JAR)", x)
            throw x
        }
        val coldMs = (System.nanoTime() - t0) / 1_000_000
        val rss1 = rssMb()

        val full = e.embedArgb(cases.map { SigLipImageEmbedder.Argb(it.argb(), it.w, it.h) })

        var minA = 1.0; var minB = 1.0
        cases.forEachIndexed { i, c ->
            val ref = c.refEmbedding!!
            assertEquals(768, ref.size); assertEquals(768, fromRef[i].size); assertEquals(768, full[i].size)
            assertEquals(1.0, Math.sqrt(full[i].sumOf { it.toDouble() * it }), 1e-4)
            val a = cosine(fromRef[i], ref); val b = cosine(full[i], ref)
            println("SIGLIP_PARITY ${c.name} cos(refTensor->kotlinORT)=$a cos(kotlinPre->kotlinORT)=$b")
            assertTrue("${c.name}: ref-tensor path cos $a", a >= 0.9999)
            assertTrue("${c.name}: full path cos $b", b >= 0.999)
            if (a < minA) minA = a; if (b < minB) minB = b
        }
        println("SIGLIP_PARITY min cos refTensor=$minA full=$minB")

        // Batch dependence (informational): the int8 model quantises activations per tensor, so a batch of all images gives a different vector per image than one at a time.
        SigLipImageEmbedder(model, batchSize = cases.size).use { b ->
            val batched = b.embedTensors(cases.map { it.refTensor() })
            println("SIGLIP_PARITY batch-of-${cases.size} vs single-image reference cos: " + cases.indices.joinToString { "%.4f".format(cosine(batched[it], cases[it].refEmbedding!!)) })
        }

        // The silent bug: the default (output 0 = last_hidden_state, token 0) is NOT the embedding.
        OrtImageSession(model.path).use { s ->
            val per = 3 * 224 * 224
            val wrong = s.run(cases[0].refTensor(), 1)
            val cw = cosine(wrong, cases[0].refEmbedding!!)
            println("SIGLIP_PARITY output0-token0 vs reference cos=$cw (must be far below 0.999)")
            assertTrue(cw < 0.99); assertEquals(768, wrong.size); assertEquals(3 * 224 * 224, per)
        }
        try { OrtImageSession(model.path, outputName = "nope").close(); throw AssertionError("missing output name must fail") } catch (_: IllegalArgumentException) {}

        // Latency / memory (JVM on this Mac: NOT representative of a phone).
        val imgs = cases.map { SigLipImageEmbedder.Argb(it.argb(), it.w, it.h) }
        val t1 = System.nanoTime(); val reps = 16
        repeat(reps) { e.embedArgb(listOf(imgs[it % imgs.size])) }
        val perMs = (System.nanoTime() - t1) / 1_000_000.0 / reps
        println("SIGLIP_PERF firstSessionPlusFirstBatch=${coldMs}ms warmSingleImage=${"%.1f".format(perMs)}ms (preprocess+ORT) rssBefore=${rss0}MB rssAfterLoad=${rss1}MB rssNow=${rssMb()}MB")
        e.close()
    }
}
