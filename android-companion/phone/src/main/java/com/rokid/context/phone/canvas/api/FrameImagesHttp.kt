package com.rokid.context.phone.canvas.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.rokid.context.phone.canvas.server.HttpResponse
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Image bytes for the frame routes: thumbnails and the HTTP wrapper. Decryption happens before this (FrameInputs). */
object FrameImagesHttp {
    const val THUMB_LONG_SIDE = 160
    private const val QUALITY = 80

    /** 404 `not_found` when the photo is gone, else the JPEG. */
    fun response(bytes: ByteArray?): HttpResponse =
        if (bytes == null) HttpResponse.error(404, "not_found", "Not found.") else HttpResponse(200, "image/jpeg", bytes)

    /**
     * A JPEG whose long side is [longSide] px (never enlarged). The decoder first subsamples by the largest power of 2 that
     * keeps the long side at or above [longSide], then the result is scaled to exactly [longSide]. Null when [jpeg] is not an image.
     */
    fun thumbnail(jpeg: ByteArray, longSide: Int = THUMB_LONG_SIDE): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val long = max(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
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
