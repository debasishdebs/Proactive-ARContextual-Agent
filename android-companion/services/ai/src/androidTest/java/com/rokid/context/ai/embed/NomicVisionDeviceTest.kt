package com.rokid.context.ai.embed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.context.ai.local.OnnxImageEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Controller-run on the phone; skipped when the vision model isn't downloaded. */
@RunWith(AndroidJUnit4::class)
class NomicVisionDeviceTest {
    @Test fun throughputAtLeast20PerSecond() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(File(ctx.filesDir, "models"), "nomic-embed-vision-v1.5.model_quantized.onnx")
        assumeTrue("vision model not downloaded", model.isFile)
        val jpeg = javaClass.classLoader!!.getResourceAsStream("nomic/img0.jpg")!!.readBytes()
        OnnxImageEmbedder(model.path, 256).use { e ->
            e.embed(List(8) { jpeg }) // warm-up
            val n = 64
            val t0 = System.nanoTime()
            e.embed(List(n) { jpeg })
            val perSec = n / ((System.nanoTime() - t0) / 1e9)
            assertTrue("throughput $perSec/s", perSec >= 20.0)
        }
    }
}
