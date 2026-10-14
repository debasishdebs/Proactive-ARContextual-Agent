package com.rokid.context.glasses.capture.session

import com.rokid.context.glasses.capture.keyframe.Nv21Frame
import com.rokid.context.glasses.capture.keyframe.Thumb
import java.nio.ByteBuffer

/**
 * Pixel copies out of a YUV_420_888 frame, on plain buffers so they run (and are tested) on the JVM.
 * The Y plane is read with its own strides; nothing here touches an ImageProxy.
 */
object YuvCopy {
    const val THUMB_WIDTH = 160
    const val KEYFRAME_WIDTH = 640

    /**
     * Grey thumbnail by Y-plane subsampling (no JPEG, no bitmap): about [THUMB_WIDTH] px wide, each cell the mean of four
     * samples inside its 4x4 source block. Reads about 77k bytes of a 640x480 frame.
     */
    fun thumb(y: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int): Thumb {
        val step = maxOf(1, w / THUMB_WIDTH)
        val tw = w / step
        val th = h / step
        val out = ByteArray(tw * th)
        val lim = y.limit()
        fun at(x: Int, yy: Int): Int {
            val i = yy * rowStride + x * pixelStride
            return if (i < lim) y.get(i).toInt() and 0xFF else 0
        }
        // Four samples per cell at 1/4 and 1/2 of the block, clamped to the frame.
        val q1 = step / 4
        val q2 = step / 2
        for (ty in 0 until th) {
            val y1 = minOf(ty * step + q1, h - 1); val y2 = minOf(ty * step + q2, h - 1)
            for (tx in 0 until tw) {
                val x1 = minOf(tx * step + q1, w - 1); val x2 = minOf(tx * step + q2, w - 1)
                out[ty * tw + tx] = ((at(x1, y1) + at(x2, y1) + at(x1, y2) + at(x2, y2)) / 4).toByte()
            }
        }
        return Thumb(tw, th, out)
    }

    /** Full NV21 (Y, then interleaved V,U) from the three planes, with row and pixel strides. */
    fun nv21(
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        v: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        w: Int, h: Int, rotation: Int,
    ): Nv21Frame {
        val out = ByteArray(w * h * 3 / 2)
        val yb = y.duplicate(); val ub = u.duplicate(); val vb = v.duplicate()
        var dst = 0
        for (row in 0 until h) {
            if (yPixelStride == 1) {
                val start = row * yRowStride
                if (start + w <= yb.limit()) { yb.position(start); yb.get(out, dst, w); dst += w; continue }
            }
            for (col in 0 until w) {
                val i = row * yRowStride + col * yPixelStride
                out[dst++] = if (i < yb.limit()) yb.get(i) else 0
            }
        }
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            val vi = row * vRowStride + col * vPixelStride
            val ui = row * uRowStride + col * uPixelStride
            out[dst++] = if (vi < vb.limit()) vb.get(vi) else 0
            out[dst++] = if (ui < ub.limit()) ub.get(ui) else 0
        }
        return Nv21Frame(w, h, rotation, out)
    }

    /** True when the keyframe is not [KEYFRAME_WIDTH] wide after rotation (the encoder then scales it and the session counts it). */
    fun needsScale(w: Int, h: Int, rotation: Int): Boolean = rotatedWidth(w, h, rotation) != KEYFRAME_WIDTH

    /** Width of the frame after [rotation] (what the keyframe's 640 is measured on). */
    fun rotatedWidth(w: Int, h: Int, rotation: Int): Int = if (rotation % 180 == 0) w else h
}
