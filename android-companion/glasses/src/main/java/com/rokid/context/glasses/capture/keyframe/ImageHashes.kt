package com.rokid.context.glasses.capture.keyframe

import kotlin.math.PI
import kotlin.math.cos

/** A 160 px wide grey thumbnail built from the camera's Y plane. Internal to the glasses: never stored, never sent. */
class Thumb(val w: Int, val h: Int, val luma: ByteArray) {
    init { require(luma.size == w * h) { "luma size" } }
}

/** Perceptual hashes and a sharpness score over a [Thumb] (spec section P1.1 "Keyframe test"). */
object ImageHashes {
    private const val DCT_N = 32
    private val COS: Array<DoubleArray> = Array(8) { u -> DoubleArray(DCT_N) { x -> cos((2 * x + 1) * u * PI / (2 * DCT_N)) } }

    /** Difference hash: a 9x8 area-averaged grid, one bit per left-vs-right neighbour. */
    fun dHash(t: Thumb): Long {
        val g = resample(t, 9, 8)
        var bits = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            bits = (bits shl 1) or (if (g[y * 9 + x] > g[y * 9 + x + 1]) 1L else 0L)
        }
        return bits
    }

    /** Perceptual hash: 32x32 grid, 2D DCT, the low 8x8 coefficients against their median (DC left out of the median). */
    fun pHash(t: Thumb): Long {
        val g = resample(t, DCT_N, DCT_N)
        // Separable DCT-II, only the 8 lowest frequencies per axis.
        val rows = Array(DCT_N) { y -> DoubleArray(8) { u -> var s = 0.0; for (x in 0 until DCT_N) s += g[y * DCT_N + x] * COS[u][x]; s } }
        val c = DoubleArray(64)
        for (v in 0 until 8) for (u in 0 until 8) { var s = 0.0; for (y in 0 until DCT_N) s += rows[y][u] * COS[v][y]; c[v * 8 + u] = s }
        val sorted = c.copyOfRange(1, 64).also { it.sort() }
        val median = sorted[sorted.size / 2]
        var bits = 0L
        for (i in 0 until 64) bits = (bits shl 1) or (if (c[i] > median) 1L else 0L)
        return bits
    }

    /** Variance of the 4-neighbour Laplacian over the interior. Low = blurred. */
    fun blur(t: Thumb): Float {
        if (t.w < 3 || t.h < 3) return 0f
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 1 until t.h - 1) for (x in 1 until t.w - 1) {
            val i = y * t.w + x
            val l = 4 * (t.luma[i].toInt() and 0xFF) - (t.luma[i - 1].toInt() and 0xFF) - (t.luma[i + 1].toInt() and 0xFF) -
                (t.luma[i - t.w].toInt() and 0xFF) - (t.luma[i + t.w].toInt() and 0xFF)
            sum += l; sumSq += l.toDouble() * l; n++
        }
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    /** Area-averaged [tw]x[th] grid (row-major). */
    private fun resample(t: Thumb, tw: Int, th: Int): DoubleArray {
        val out = DoubleArray(tw * th)
        for (ty in 0 until th) {
            val y0 = ty * t.h / th; val y1 = maxOf(y0 + 1, (ty + 1) * t.h / th)
            for (tx in 0 until tw) {
                val x0 = tx * t.w / tw; val x1 = maxOf(x0 + 1, (tx + 1) * t.w / tw)
                var s = 0L
                for (y in y0 until y1) for (x in x0 until x1) s += t.luma[y * t.w + x].toInt() and 0xFF
                out[ty * tw + tx] = s.toDouble() / ((y1 - y0) * (x1 - x0))
            }
        }
        return out
    }
}
