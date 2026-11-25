package com.rokid.context.ai.calibration

import com.rokid.context.ai.embed.siglip.SigLipImageEmbedder
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * JVM decode of a JPEG / PNG to packed ARGB (no EXIF rotation, like the phone's BitmapFactory path). For the real-data calibration run only. The unit-test compile classpath is android.jar, which has no
 * javax.imageio, so the JDK classes are reached by reflection (they exist at run time on a desktop JVM).
 */
internal object JvmImages {
    fun argb(bytes: ByteArray): SigLipImageEmbedder.Argb {
        val io = Class.forName("javax.imageio.ImageIO")
        val img = io.getMethod("read", InputStream::class.java).invoke(null, ByteArrayInputStream(bytes)) ?: error("undecodable image")
        val c = img.javaClass
        val w = c.getMethod("getWidth").invoke(img) as Int
        val h = c.getMethod("getHeight").invoke(img) as Int
        val px = IntArray(w * h)
        c.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java).invoke(img, 0, 0, w, h, px, 0, w)
        return SigLipImageEmbedder.Argb(px, w, h)
    }
}
