package com.rokid.context.phone.canvas.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rokid.context.phone.canvas.server.HttpResponse
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo ready to serve: [bytes] of an image of [contentType]. Held in memory only. */
class FrameImage(val bytes: ByteArray, val contentType: String)

/** Image bytes for the frame routes: the content type, the size limits, thumbnails and the HTTP wrapper. Decryption happens before this. */
object FrameImagesHttp {
    const val THUMB_LONG_SIDE = 160
    private const val QUALITY = 80
    /** The largest photo file the canvas will open (a keyframe is a few dozen KB). */
    const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    /** The largest image the thumbnail decoder will open (width x height). */
    const val MAX_PIXELS = 48_000_000L

    /** 404 `not_found` when the photo is gone, else the image. The router adds `Cache-Control: no-store`, `nosniff` and the CSP. */
    fun response(img: FrameImage?): HttpResponse =
        if (img == null) HttpResponse.error(404, "not_found", "Not found.")
        else HttpResponse(200, img.contentType, img.bytes, mapOf("Cache-Control" to "no-store", "Cross-Origin-Resource-Policy" to "same-origin"))

    /** The image type of [b] from its magic bytes, or null when it is not a JPEG, PNG or WebP (nothing else is ever served as a photo). */
    fun sniff(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        else -> null
    }

    /**
     * A JPEG whose long side is [longSide] px (never enlarged). The decoder first subsamples by the largest power of 2 that
     * keeps the long side at or above [longSide], then the result is scaled to exactly [longSide]. Null when [jpeg] is not an image or has more than [maxPixels] pixels.
     */
    fun thumbnail(jpeg: ByteArray, longSide: Int = THUMB_LONG_SIDE, maxPixels: Long = MAX_PIXELS): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val long = max(bounds.outWidth, bounds.outHeight)
        if (long <= 0 || bounds.outWidth.toLong() * bounds.outHeight > maxPixels) return null
        var sample = 1
        while (long / (sample * 2) >= longSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = longSide.toFloat() / max(decoded.width, decoded.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).roundToInt()), max(1, (decoded.height * scale).roundToInt()), true) else decoded
        return try {
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }
}
