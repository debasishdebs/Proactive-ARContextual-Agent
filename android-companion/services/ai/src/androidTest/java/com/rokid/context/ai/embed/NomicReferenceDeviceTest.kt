package com.rokid.context.ai.embed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Controller-run device parity test (not part of the JVM suite): the on-device Nomic text (GGUF) and vision (ONNX) outputs
 * must match tools/nomic_reference.py (PyTorch) with cosine >= 0.99 for every text and image, after the same transforms.
 * Skips when the model bundle isn't downloaded, or when no runtime is wired in [NomicDeviceRuntime.provider] yet
 * (the F2/F3 implementations plug in there at integration).
 */
@RunWith(AndroidJUnit4::class)
class NomicReferenceDeviceTest {
    @Test fun deviceOutputsMatchPyTorchReference() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val models = File(ctx.filesDir, "models")
        assumeTrue("Nomic bundle not downloaded", File(models, "nomic-embed-text-v1.5.Q8_0.gguf").isFile && File(models, "nomic-embed-vision-v1.5.model_quantized.onnx").isFile)
        val runtime = NomicDeviceRuntime.provider?.invoke(models)
        assumeTrue("Nomic runtime not wired", runtime != null)
        val ref = JSONObject(javaClass.classLoader!!.getResourceAsStream("nomic/reference.json")!!.bufferedReader().readText())
        val texts = ref.getJSONArray("texts")
        for (i in 0 until texts.length()) {
            val t = texts.getJSONObject(i)
            // text: mean-pool 768 -> LayerNorm (no affine, eps 1e-5) -> first 256 -> L2
            val expected = textTransform(floats(t.getJSONArray("raw768")))
            val c = cosine(expected, textTransform(runtime!!.rawText(t.getString("text"))))
            assertTrue("text $i cosine $c", c >= 0.99)
        }
        val images = ref.getJSONArray("images")
        for (i in 0 until images.length()) {
            val im = images.getJSONObject(i)
            val jpeg = javaClass.classLoader!!.getResourceAsStream("nomic/" + im.getString("file"))!!.readBytes()
            // vision: CLS 768 -> L2 -> first 256 -> L2 (D=256 here; 768 is just the first step)
            val expected = visionTransform(floats(im.getJSONArray("raw768")))
            val c = cosine(expected, visionTransform(runtime!!.rawImage(jpeg)))
            assertTrue("image $i cosine $c", c >= 0.99)
        }
    }

    interface NomicDeviceRuntime {
        /** Raw 768 mean-pooled text output (prefix included in [prefixed]). */
        fun rawText(prefixed: String): FloatArray
        /** Raw 768 CLS output for a JPEG, through the app's own preprocessing. */
        fun rawImage(jpeg: ByteArray): FloatArray

        companion object { @JvmStatic var provider: ((File) -> NomicDeviceRuntime)? = null }
    }

    private fun floats(a: org.json.JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

    private fun l2(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0.0) { s, x -> s + x * x }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun textTransform(raw: FloatArray): FloatArray {
        val mean = raw.average().toFloat()
        val variance = raw.fold(0.0) { s, x -> s + (x - mean) * (x - mean) } / raw.size
        val inv = (1.0 / sqrt(variance + 1e-5)).toFloat()
        return l2(FloatArray(256) { (raw[it] - mean) * inv })
    }

    private fun visionTransform(raw: FloatArray) = l2(l2(raw).copyOf(256))

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return d / sqrt(na * nb)
    }
}
