package com.rokid.context.vision.local

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager

/** The phone's thermal, battery-saver and battery state, read fresh on every call. A read that fails counts as the safe answer (hot, saver on). */
class AndroidDeviceState(context: Context) : () -> DeviceState {
    private val app = context.applicationContext

    override fun invoke(): DeviceState = try {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        DeviceState(pm.currentThermalStatus, pm.isPowerSaveMode, if (level < 0 || scale <= 0) 0 else level * 100 / scale, charging)
    } catch (_: Exception) {
        DeviceState(CaptionConfig.THERMAL_SEVERE, true, 0, false)
    }
}

/** True on the app's main (UI) thread. */
fun isMainThread(): Boolean = Looper.getMainLooper()?.thread === Thread.currentThread()

/** Decodes a JPEG with EXIF rotation to RGB, longest side at most the given size (never scaled up). */
class BitmapFrameLoader : FrameLoader {
    override fun load(jpegPath: String, maxSidePx: Int): RgbFrame? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpegPath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else decode(jpegPath, bounds, maxSidePx)
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

    private fun decode(path: String, bounds: BitmapFactory.Options, maxSide: Int): RgbFrame? {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val m = Matrix()
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> m.postRotate(90f); 3 -> m.postRotate(180f); 8 -> m.postRotate(270f) }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val (tw, th) = scaledSize(bmp.width, bmp.height, maxSide)
        if (tw != bmp.width || th != bmp.height) bmp = Bitmap.createScaledBitmap(bmp, tw, th, true)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val out = ByteArray(px.size * 3)
        for (i in px.indices) { val p = px[i]; out[i * 3] = (p shr 16).toByte(); out[i * 3 + 1] = (p shr 8).toByte(); out[i * 3 + 2] = p.toByte() }
        return RgbFrame(out, bmp.width, bmp.height)
    }

    companion object {
        /** The size with the longest side at most [maxSide], aspect kept, never larger than the input. */
        fun scaledSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
            val longest = maxOf(w, h)
            if (longest <= maxSide) return w to h
            val f = maxSide.toDouble() / longest
            return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
        }
    }
}
