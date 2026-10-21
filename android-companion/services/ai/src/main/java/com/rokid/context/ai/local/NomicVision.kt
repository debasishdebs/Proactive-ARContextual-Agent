package com.rokid.context.ai.local

import android.graphics.BitmapFactory
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

/**
 * Image preprocessing for nomic-embed-vision-v1.5, mirroring the pinned preprocessor_config.json (CLIPImageProcessor):
 * convert to RGB -> resize -> centre-crop -> rescale -> normalise (mean/std) -> NCHW. The official config resizes to
 * exactly 224x224 (aspect NOT preserved) BEFORE cropping, so the crop is a no-op there; the order is still honoured.
 */
object NomicVision {
    const val INPUT = 224
    const val CLS_DIMS = 768

    /** The stored image-vector width (Ruling 17), part of the space fingerprint; the image embedder declares it as `imageDims`. */
    const val STORED_DIMS = 768

    /** [resample] is the PIL code from the config (3 = bicubic). [resizeShortestEdge] false = stretch to [resize] x [resize]. */
    class PreprocessConfig(
        val resize: Int,
        val crop: Int,
        val resample: String,
        val mean: FloatArray,
        val std: FloatArray,
        val rescale: Float,
        val resizeShortestEdge: Boolean = false,
        val doCenterCrop: Boolean = true,
    ) {
        companion object {
            /** Values copied from preprocessor_config.json at the pinned commit (a test checks it against the committed file). */
            val PINNED = PreprocessConfig(
                resize = 224, crop = 224, resample = "bicubic",
                mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f),
                std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f),
                rescale = 0.00392156862745098f,
            )

            fun fromJson(json: String): PreprocessConfig {
                val o = JSONObject(json)
                require(o.optBoolean("do_resize", true) && o.optBoolean("do_rescale", true) && o.optBoolean("do_normalize", true) &&
                    o.optBoolean("do_convert_rgb", true)) { "unsupported preprocessor_config" }
                val size = o.getJSONObject("size")
                val shortest = size.has("shortest_edge")
                val resize = if (shortest) size.getInt("shortest_edge") else size.getInt("height")
                if (!shortest) require(size.getInt("width") == resize) { "non-square resize unsupported" }
                val cropO = o.optJSONObject("crop_size")
                val crop = cropO?.getInt("height") ?: resize
                fun arr(k: String) = o.getJSONArray(k).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                val resample = when (val r = o.getInt("resample")) { 3 -> "bicubic"; else -> error("unsupported resample $r") }
                return PreprocessConfig(resize, crop, resample, arr("image_mean"), arr("image_std"),
                    o.getDouble("rescale_factor").toFloat(), shortest, o.optBoolean("do_center_crop", true))
            }
        }
    }

    fun fingerprint(dims: Int): String = "nomic-embed-vision-v1.5|cls|l2|d=$dims|l2"

    /** JPEG -> 3x224x224 floats (NCHW, RGB). */
    fun preprocess(jpeg: ByteArray, cfg: PreprocessConfig = PreprocessConfig.PINNED): FloatArray {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw IllegalArgumentException("undecodable image")
        try {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            return preprocessArgb(px, w, h, cfg)
        } finally {
            bmp.recycle()
        }
    }

    /** Pure core (no Android): packed ARGB pixels in, NCHW floats out. */
    fun preprocessArgb(argb: IntArray, w: Int, h: Int, cfg: PreprocessConfig): FloatArray {
        require(cfg.resample == "bicubic")
        val rgb = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            val p = argb[i]
            rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte()
        }
        var tw = cfg.resize
        var th = cfg.resize
        if (cfg.resizeShortestEdge) {
            if (w <= h) { th = (cfg.resize.toLong() * h / w).toInt() } else { tw = (cfg.resize.toLong() * w / h).toInt() }
        }
        val resized = resizeBicubic(rgb, w, h, tw, th)
        val out = FloatArray(3 * cfg.crop * cfg.crop)
        val x0 = if (cfg.doCenterCrop) (tw - cfg.crop) / 2 else 0
        val y0 = if (cfg.doCenterCrop) (th - cfg.crop) / 2 else 0
        require(x0 >= 0 && y0 >= 0) { "crop larger than resized image" }
        val plane = cfg.crop * cfg.crop
        for (y in 0 until cfg.crop) for (x in 0 until cfg.crop) {
            val s = ((y + y0) * tw + x + x0) * 3
            for (c in 0 until 3) {
                val v = (resized[s + c].toInt() and 0xFF) * cfg.rescale
                out[c * plane + y * cfg.crop + x] = (v - cfg.mean[c]) / cfg.std[c]
            }
        }
        return out
    }

    // --- PIL-compatible bicubic (a = -0.5, support scaled when downsampling), horizontal then vertical, uint8 in between ---

    private class Coeffs(val start: IntArray, val count: IntArray, val w: Array<DoubleArray>)

    private fun bicubic(x0: Double): Double {
        val x = abs(x0); val a = -0.5
        return when {
            x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0
            x < 2.0 -> (((x - 5.0) * x + 8.0) * x - 4.0) * a
            else -> 0.0
        }
    }

    private fun coeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = if (scale < 1.0) 1.0 else scale
        val support = 2.0 * filterScale
        val start = IntArray(outSize); val count = IntArray(outSize)
        val ws = Array(outSize) { DoubleArray(0) }
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            val ss = 1.0 / filterScale
            val xmin = maxOf((center - support + 0.5).toInt(), 0)
            val xmax = minOf((center + support + 0.5).toInt(), inSize) - xmin
            val k = DoubleArray(xmax)
            var sum = 0.0
            for (x in 0 until xmax) { k[x] = bicubic((x + xmin - center + 0.5) * ss); sum += k[x] }
            if (sum != 0.0) for (x in 0 until xmax) k[x] /= sum
            start[xx] = xmin; count[xx] = xmax; ws[xx] = k
        }
        return Coeffs(start, count, ws)
    }

    private fun clip8(v: Double): Byte = floor(v + 0.5).toInt().coerceIn(0, 255).toByte()

    private fun resizeBicubic(src: ByteArray, w: Int, h: Int, tw: Int, th: Int): ByteArray {
        var cur = src; var cw = w; var ch = h
        if (tw != cw) {
            val c = coeffs(cw, tw)
            val dst = ByteArray(tw * ch * 3)
            for (y in 0 until ch) for (x in 0 until tw) for (ch3 in 0 until 3) {
                var s = 0.0
                val k = c.w[x]; val st = c.start[x]
                for (i in 0 until c.count[x]) s += k[i] * (cur[(y * cw + st + i) * 3 + ch3].toInt() and 0xFF)
                dst[(y * tw + x) * 3 + ch3] = clip8(s)
            }
            cur = dst; cw = tw
        }
        if (th != ch) {
            val c = coeffs(ch, th)
            val dst = ByteArray(cw * th * 3)
            for (y in 0 until th) for (x in 0 until cw) for (ch3 in 0 until 3) {
                var s = 0.0
                val k = c.w[y]; val st = c.start[y]
                for (i in 0 until c.count[y]) s += k[i] * (cur[((st + i) * cw + x) * 3 + ch3].toInt() and 0xFF)
                dst[(y * cw + x) * 3 + ch3] = clip8(s)
            }
            cur = dst; ch = th
        }
        return cur
    }
}
