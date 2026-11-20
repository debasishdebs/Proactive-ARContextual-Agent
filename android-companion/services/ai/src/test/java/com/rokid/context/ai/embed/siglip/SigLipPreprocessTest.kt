package com.rokid.context.ai.embed.siglip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Kotlin preprocessing vs the HF SiglipImageProcessor tensors (golden, see make_vision_golden.py). Tolerance rationale: [SigLipPreprocess]. */
class SigLipPreprocessTest {
    private val cases = VisionCase.load()

    @Test fun goldenHasTheExpectedImages() {
        assertTrue(cases.size >= 5)
        assertTrue(cases.any { it.w != it.h })
        assertTrue(cases.any { it.name.startsWith("nonsquare_64x48") && it.w == 64 && it.h == 48 })
    }

    @Test fun matchesHfTensorPerElementWithinOneUint8Step() {
        var worst = 0f
        for (c in cases) {
            val got = SigLipPreprocess.preprocessArgb(c.argb(), c.w, c.h)
            val ref = c.refTensor()
            assertEquals(c.name, 3 * 224 * 224, got.size)
            var m = 0f; var diffs = 0
            for (i in got.indices) { val d = abs(got[i] - ref[i]); if (d > m) m = d; if (d > 1e-5f) diffs++ }
            println("SIGLIP_PRE ${c.name} ${c.w}x${c.h} maxAbsDelta=$m elementsDiffering=$diffs of ${got.size}")
            assertTrue("${c.name}: max |delta| $m > ${SigLipPreprocess.TOLERANCE}", m <= SigLipPreprocess.TOLERANCE)
            if (m > worst) worst = m
        }
        println("SIGLIP_PRE worst maxAbsDelta=$worst tolerance=${SigLipPreprocess.TOLERANCE}")
    }

    @Test fun rangeIsMinusOneToOneAndChannelOrderIsRgbChw() {
        val red = IntArray(32 * 32) { 0xFFFF0000.toInt() }
        val t = SigLipPreprocess.preprocessArgb(red, 32, 32)
        val plane = 224 * 224
        assertEquals(1f, t[0], 1e-6f); assertEquals(-1f, t[plane], 1e-6f); assertEquals(-1f, t[2 * plane], 1e-6f)
        assertTrue(t.all { it in -1f..1f })
    }

    @Test fun alphaIsIgnored() {
        val a = IntArray(16) { 0x00112233 }; val b = IntArray(16) { 0xFF112233.toInt() }
        assertTrue(SigLipPreprocess.preprocessArgb(a, 4, 4).contentEquals(SigLipPreprocess.preprocessArgb(b, 4, 4)))
    }
}
