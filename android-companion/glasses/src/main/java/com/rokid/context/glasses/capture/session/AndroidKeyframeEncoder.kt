package com.rokid.context.glasses.capture.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import com.rokid.context.glasses.capture.keyframe.Nv21Frame
import java.io.ByteArrayOutputStream

/**
 * NV21 copy to a 640 px wide JPEG at q75 (spec P1.1). A frame that is already 640 wide after rotation and needs none
 * goes straight through [YuvImage]; anything else is rotated, then scaled to 640 wide ([EncodedKeyframe.scaled] = true so
 * the session counts it). Only keyframes are encoded.
 */
object AndroidKeyframeEncoder : KeyframeEncoder {
    const val QUALITY = 75
    private const val INTERMEDIATE_QUALITY = 92

    override fun encode(frame: Nv21Frame): EncodedKeyframe? {
        val yuv = YuvImage(frame.data, ImageFormat.NV21, frame.w, frame.h, null)
        val scaled = YuvCopy.needsScale(frame.w, frame.h, frame.rotation)
        if (frame.rotation % 360 == 0 && !scaled) {
            val jpeg = compress(yuv, Rect(0, 0, frame.w, frame.h), QUALITY) ?: return null
            return EncodedKeyframe(jpeg, frame.w, false)
        }
        val raw = compress(yuv, Rect(0, 0, frame.w, frame.h), INTERMEDIATE_QUALITY) ?: return null
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val rotated = if (frame.rotation % 360 != 0) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(frame.rotation.toFloat()) }, true) else decoded
        val out = if (rotated.width != YuvCopy.KEYFRAME_WIDTH) {
            val h = (rotated.height * (YuvCopy.KEYFRAME_WIDTH.toFloat() / rotated.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, YuvCopy.KEYFRAME_WIDTH, h, true)
        } else rotated
        val bytes = ByteArrayOutputStream().use { s -> out.compress(Bitmap.CompressFormat.JPEG, QUALITY, s); s.toByteArray() }
        val width = out.width
        if (out !== rotated) out.recycle()
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()
        return if (bytes.isEmpty()) null else EncodedKeyframe(bytes, width, scaled)
    }

    private fun compress(yuv: YuvImage, r: Rect, q: Int): ByteArray? =
        ByteArrayOutputStream().use { s -> if (yuv.compressToJpeg(r, q, s)) s.toByteArray() else null }
}
