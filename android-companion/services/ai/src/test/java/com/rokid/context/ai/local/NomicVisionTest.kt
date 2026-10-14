package com.rokid.context.ai.local

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real libjpeg decode, so the parity numbers mean something
@Config(sdk = [34])
class NomicVisionTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("nomic/$name")!!.readBytes()
    private val pinned get() = NomicVision.PreprocessConfig.fromJson(String(res("preprocessor_config.json")))

    private fun jpeg(w: Int, h: Int, paint: (Bitmap) -> Unit): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        paint(b)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    @Test fun preprocessShapeAndRange() {
        val out = NomicVision.preprocess(jpeg(640, 480) { it.eraseColor(Color.rgb(200, 100, 50)) }, pinned)
        assertEquals(3 * 224 * 224, out.size)
        val cfg = pinned
        // global bounds of (v/255 - mean)/std over v in [0,255]
        for (c in 0 until 3) {
            val lo = (0f - cfg.mean[c]) / cfg.std[c]
            val hi = (1f - cfg.mean[c]) / cfg.std[c]
            for (i in 0 until 224 * 224) assertTrue(out[c * 224 * 224 + i] in (lo - 1e-3f)..(hi + 1e-3f))
        }
        // solid (200,100,50): channel 0 is bright, channel 2 dark
        assertTrue(out[0] > 1f && out[2 * 224 * 224] < 0f)
    }

    @Test fun followsPinnedConfigOrder() {
        assertEquals(224, pinned.resize); assertEquals(224, pinned.crop)
        assertEquals("bicubic", pinned.resample)
        assertTrue("config resizes to a square (not shortest-edge)", !pinned.resizeShortestEdge)
        assertTrue(pinned.doCenterCrop)
        assertArrayEquals(NomicVision.PreprocessConfig.PINNED.mean, pinned.mean, 0f)
        assertArrayEquals(NomicVision.PreprocessConfig.PINNED.std, pinned.std, 0f)
        assertEquals(NomicVision.PreprocessConfig.PINNED.rescale, pinned.rescale, 0f)
        // resize-then-crop: a red left edge of a wide image must survive (crop-then-resize would cut it away)
        val w = 640; val h = 480
        val px = IntArray(w * h) { if (it % w < 64) Color.RED else Color.BLUE }
        val out = NomicVision.preprocessArgb(px, w, h, pinned)
        assertTrue("left column is red-ish (R high)", out[0] > 1f && out[2 * 224 * 224] < 0f)
        // a stretch, so a vertical stripe keeps its fractional width: 64/640 of 224 = 22.4 columns
        var red = 0
        for (x in 0 until 224) if (out[112 * 224 + x] > 1f) red++
        assertTrue("red width $red", red in 21..24)
    }

    @Test fun matchesPythonPixelsForRectangularImage() {
        val ref = JSONObject(String(res("reference.json")))
        val images = ref.getJSONArray("images")
        val seen = HashSet<String>()
        for (i in 0 until images.length()) {
            val im = images.getJSONObject(i)
            seen += if (im.getInt("width") > im.getInt("height")) "landscape" else "portrait"
            val bytes = Base64.getDecoder().decode(im.getString("pixels_f16_b64"))
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val expected = FloatArray(sb.remaining()) { half(sb.get(it)) }
            val got = NomicVision.preprocess(res(im.getString("file")), pinned)
            assertEquals(expected.size, got.size)
            var worst = 0f
            for (k in got.indices) worst = maxOf(worst, kotlin.math.abs(got[k] - expected[k]))
            // Exact up to two uint8 levels (1/255/std ~ 0.015 each) on a handful of edge pixels: the reference decoder/resampler rounds a
            // few source pixels differently. Anything structural (order, stretch vs crop, filter) shows as hundreds of levels.
            val bad = got.indices.count { kotlin.math.abs(got[it] - expected[it]) > 2e-3f }
            assertTrue("${im.getString("file")} worst=$worst", worst < 0.035f)
            assertTrue("${im.getString("file")} off-by-a-level pixels=$bad", bad < got.size / 200)
        }
        assertEquals(setOf("landscape", "portrait"), seen)
    }

    private fun half(h: Short): Float = if (Build.VERSION.SDK_INT >= 26) android.util.Half.toFloat(h) else error("sdk")
}
