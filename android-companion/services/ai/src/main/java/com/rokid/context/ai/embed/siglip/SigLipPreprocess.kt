package com.rokid.context.ai.embed.siglip

import kotlin.math.abs


/**
 * SigLIP 2 B/16-224 image preprocessing, mirroring the pinned preprocessor_config.json (SiglipImageProcessor):
 * RGB -> STRETCH to 224x224 (aspect not kept) with PIL BILINEAR (resample = 2; a triangle filter whose support grows with the downscale factor, so it antialiases),
 * horizontal pass then vertical pass with uint8 rounding between them -> x * 1/255 -> (x - 0.5) / 0.5 -> CHW float32 in [-1, 1].
 *
 * Pure JVM: packed ARGB ints in (alpha ignored, exactly like decoding to RGB), so it is unit-testable without Android. The Bitmap/JPEG entry point is in [SigLipImageEmbedder].
 *
 * Accuracy against the reference: this port copies PIL's 8-bit fixed-point resample (22-bit coefficients, integer accumulate, same support and bounds), so it is expected to be
 * bit-exact. (A first version with double arithmetic differed by one uint8 step on ~1% of elements of two images, which the int8 model amplified; see the parity test.)
 * The golden test still allows per-element |delta| <= [TOLERANCE] = one uint8 step after normalisation (2/255 = 0.00784) plus slack, and prints the real maximum.
 */
object SigLipPreprocess {
    const val SIDE = 224
    const val RESCALE = 0.00392156862745098 // 1/255, preprocessor_config.json

    /**
     * u8 -> float exactly as HF does it, BIT for bit: the rescale is a float64 multiply cast to float32, then (v - 0.5f) / 0.5f in float32. This matters: the int8 vision model is
     * hypersensitive to 1-ulp input differences (measured: cosine 0.992-0.997 between two bit-different but equal-to-1e-7 tensors), so the tensor must match the reference bits.
     */
    fun norm(u8: Int): Float = ((u8 * RESCALE).toFloat() - MEAN) / STD
    const val MEAN = 0.5f
    const val STD = 0.5f

    /** One uint8 step after normalisation is 2/255 = 0.00784; allow one step. */
    const val TOLERANCE = 2f / 255f + 1e-4f

    /** Packed ARGB (w x h, row-major) -> 3 x 224 x 224 floats, CHW, RGB. */
    fun preprocessArgb(argb: IntArray, w: Int, h: Int): FloatArray {
        require(w > 0 && h > 0 && argb.size >= w * h) { "bad image size" }
        val u8 = resizeBilinearU8(argb, w, h)
        val plane = SIDE * SIDE
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) for (c in 0 until 3) {
            out[c * plane + i] = norm(u8[i * 3 + c].toInt() and 0xFF)
        }
        return out
    }

    /** The stretched 224x224 RGB image as HWC uint8 (what PIL's `resize((224,224), BILINEAR)` gives); exposed so the test can compare the grid before normalisation. */
    fun resizeBilinearU8(argb: IntArray, w: Int, h: Int): ByteArray {
        val rgb = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            val p = argb[i]
            rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte()
        }
        var cur = rgb; var cw = w; var ch = h
        if (SIDE != cw) { // PIL skips a pass whose size is unchanged
            val c = coeffs(cw, SIDE)
            val dst = ByteArray(SIDE * ch * 3)
            for (y in 0 until ch) for (x in 0 until SIDE) for (k3 in 0 until 3) {
                var s = HALF
                val k = c.w[x]; val st = c.start[x]
                for (i in k.indices) s += k[i] * (cur[(y * cw + st + i) * 3 + k3].toInt() and 0xFF)
                dst[(y * SIDE + x) * 3 + k3] = clip8(s)
            }
            cur = dst; cw = SIDE
        }
        if (SIDE != ch) {
            val c = coeffs(ch, SIDE)
            val dst = ByteArray(cw * SIDE * 3)
            for (y in 0 until SIDE) for (x in 0 until cw) for (k3 in 0 until 3) {
                var s = HALF
                val k = c.w[y]; val st = c.start[y]
                for (i in k.indices) s += k[i] * (cur[((st + i) * cw + x) * 3 + k3].toInt() and 0xFF)
                dst[(y * cw + x) * 3 + k3] = clip8(s)
            }
            cur = dst; ch = SIDE
        }
        return cur
    }

    private class Coeffs(val start: IntArray, val w: Array<IntArray>)

    /** PIL's triangle filter: support 1.0, scaled by the downscale factor (never below 1). */
    private fun triangle(x0: Double): Double { val x = abs(x0); return if (x < 1.0) 1.0 - x else 0.0 }

    private fun coeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = if (scale < 1.0) 1.0 else scale
        val support = 1.0 * filterScale
        val start = IntArray(outSize)
        val ws = Array(outSize) { IntArray(0) }
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            val ss = 1.0 / filterScale
            val xmin = maxOf((center - support + 0.5).toInt(), 0)
            val xmax = minOf((center + support + 0.5).toInt(), inSize) - xmin
            val k = DoubleArray(xmax)
            var sum = 0.0
            for (x in 0 until xmax) { k[x] = triangle((x + xmin - center + 0.5) * ss); sum += k[x] }
            if (sum != 0.0) for (x in 0 until xmax) k[x] /= sum
            start[xx] = xmin; ws[xx] = IntArray(xmax) { fixed(k[it]) }
        }
        return Coeffs(start, ws)
    }

    // PIL's 8-bit path: coefficients normalised in double, then stored as int with 22 fractional bits (round half away from zero), integer accumulate from 1 << 21, >> 22, clip.
    private const val PRECISION = 22
    private const val HALF = 1 shl (PRECISION - 1)
    private fun fixed(k: Double): Int = (if (k < 0) -0.5 + k * (1 shl PRECISION) else 0.5 + k * (1 shl PRECISION)).toInt()

    private fun clip8(v: Int): Byte = (v shr PRECISION).coerceIn(0, 255).toByte()
}
