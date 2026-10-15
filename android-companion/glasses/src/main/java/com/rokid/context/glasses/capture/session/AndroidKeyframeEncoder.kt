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
 * NV21 copy to a q75 JPEG whose long side is at most 640 px (spec P1.1). Never upscaled: a frame already within the cap goes
 * straight through [YuvImage] (rotated first when needed); a larger one is rotated, then scaled down. Only keyframes are encoded.
 */
object AndroidKeyframeEncoder : KeyframeEncoder {
    const val QUALITY = 75
    private const val INTERMEDIATE_QUALITY = 92

    override fun encode(frame: Nv21Frame): EncodedKeyframe? {
        val yuv = YuvImage(frame.data, ImageFormat.NV21, frame.w, frame.h, null)
        val (ow, oh) = YuvCopy.outputSize(frame.w, frame.h, frame.rotation)
        val shrink = YuvCopy.needsShrink(frame.w, frame.h, frame.rotation)
        if (frame.rotation % 360 == 0 && !shrink) {
            val jpeg = compress(yuv, Rect(0, 0, frame.w, frame.h), QUALITY) ?: return null
            return EncodedKeyframe(jpeg, frame.w, frame.h)
        }
        val raw = compress(yuv, Rect(0, 0, frame.w, frame.h), INTERMEDIATE_QUALITY) ?: return null
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val rotated = if (frame.rotation % 360 != 0) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(frame.rotation.toFloat()) }, true) else decoded
        val out = if (shrink) Bitmap.createScaledBitmap(rotated, ow, oh, true) else rotated
        val bytes = ByteArrayOutputStream().use { s -> out.compress(Bitmap.CompressFormat.JPEG, QUALITY, s); s.toByteArray() }
        val width = out.width; val height = out.height
        if (out !== rotated) out.recycle()
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()
        return if (bytes.isEmpty()) null else EncodedKeyframe(bytes, width, height)
    }

    private fun compress(yuv: YuvImage, r: Rect, q: Int): ByteArray? =
        ByteArrayOutputStream().use { s -> if (yuv.compressToJpeg(r, q, s)) s.toByteArray() else null }
}
