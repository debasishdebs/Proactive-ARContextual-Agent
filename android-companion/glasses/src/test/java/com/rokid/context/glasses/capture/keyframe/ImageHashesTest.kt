package com.rokid.context.glasses.capture.keyframe

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageHashesTest {
    private fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    /** A smooth, structured scene: [seed] changes the pattern, [shift] moves it sideways. */
    private fun scene(seed: Int, shift: Int = 0): Thumb {
        val w = 160; val h = 120
        val luma = ByteArray(w * h) { i ->
            val x = i % w + shift; val y = i / w
            val v = 128 + 70 * kotlin.math.sin((x + seed * 37) / (9.0 + seed * 5)) * kotlin.math.cos((y - seed * 11) / (7.0 + seed * 3))
            v.toInt().coerceIn(0, 255).toByte()
        }
        return Thumb(w, h, luma)
    }

    private fun noise(seed: Int): Thumb {
        val r = Random(seed)
        return Thumb(160, 120, ByteArray(160 * 120) { r.nextInt(256).toByte() })
    }

    private fun boxBlur(t: Thumb, radius: Int): Thumb {
        val out = ByteArray(t.luma.size)
        for (y in 0 until t.h) for (x in 0 until t.w) {
            var s = 0; var n = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until t.w && yy in 0 until t.h) { s += t.luma[yy * t.w + xx].toInt() and 0xFF; n++ }
            }
            out[y * t.w + x] = (s / n).toByte()
        }
        return Thumb(t.w, t.h, out)
    }

    @Test fun identicalThumbsDistance0() {
        val a = scene(1); val b = scene(1)
        assertEquals(0, hamming(ImageHashes.dHash(a), ImageHashes.dHash(b)))
        assertEquals(0, hamming(ImageHashes.pHash(a), ImageHashes.pHash(b)))
    }

    @Test fun shiftedThumbSmallDistance() {
        val a = scene(1); val b = scene(1, shift = 2)
        assertTrue(hamming(ImageHashes.dHash(a), ImageHashes.dHash(b)) <= 10)
        assertTrue(hamming(ImageHashes.pHash(a), ImageHashes.pHash(b)) <= 10)
    }

    @Test fun differentSceneLargeDistance() {
        val a = scene(1); val b = scene(2)
        assertTrue(hamming(ImageHashes.dHash(a), ImageHashes.dHash(b)) > 10)
        assertTrue(hamming(ImageHashes.pHash(a), ImageHashes.pHash(b)) > 10)
    }

    @Test fun blurLowerForBlurred() {
        val sharp = noise(3)
        assertTrue(ImageHashes.blur(boxBlur(sharp, 2)) < ImageHashes.blur(sharp))
        assertEquals(0f, ImageHashes.blur(Thumb(160, 120, ByteArray(160 * 120) { 90 })))
    }
}
