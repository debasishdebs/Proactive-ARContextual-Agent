package com.rokid.context.ai.calibration

import com.rokid.context.ai.embed.siglip.SigLipImageEmbedder
import com.rokid.context.ai.local.NomicVision
import com.rokid.context.ai.local.OnnxImageEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Calibrates Nomic and SigLIP 2 (image channel) on the owner's REAL pins and Moments. Skipped unless CALIB_DIR names the ground-truth folder (layout: see [CalibrationFolder]); no real data is in git.
 * Environment (or -D system properties of the same name):
 *   CALIB_DIR             folder with one sub-folder per pin (ref*.jpg = the attached Moment photo, other photos = taken at that pin) and optional _none/ (photos where no pin is right). Must be outside git.
 *   CALIB_OUT             where the report is written (default: next to CALIB_DIR, file calibration-report.txt). Must be outside git.
 *   NOMIC_VISION_ONNX     nomic-embed-vision-v1.5.model_quantized.onnx   (skip Nomic when unset)
 *   SIGLIP_VISION_ONNX    vision_model_quantized.onnx of SigLIP 2        (skip SigLIP when unset)
 *   ORT_DESKTOP_JAR       the desktop ONNX Runtime jar, onnxruntime-1.31.0.jar (see build.gradle.kts); without it the native load fails and the test is skipped with that reason.
 * Run:  CALIB_DIR=... NOMIC_VISION_ONNX=... SIGLIP_VISION_ONNX=... ORT_DESKTOP_JAR=... tools/dev/run_gradle_locked.sh <repo> <log> :services:ai:testDebugUnitTest --tests '*MatchCalibrationRealDataTest*'
 * The console and the report hold counts and milli scores only. The image channel is calibrated here (text anchors need the on-device text towers; the `place shadow2` log covers them).
 */
class MatchCalibrationRealDataTest {
    private fun env(k: String): String? = (System.getenv(k) ?: System.getProperty(k))?.takeIf { it.isNotBlank() }

    @Test fun calibrateOnRealPins() {
        val dirPath = env("CALIB_DIR")
        assumeTrue("CALIB_DIR not set: calibration skipped", dirPath != null)
        val root = File(dirPath!!)
        assertFalse("the calibration folder must not sit inside the repo", CalibrationFolder.insideGitTree(root))
        val out = File(env("CALIB_OUT") ?: File(root.parentFile, "calibration-report.txt").path)
        assertFalse("the report must not be written inside the repo", CalibrationFolder.insideGitTree(out.absoluteFile.parentFile))
        val folder = CalibrationFolder.load(root)
        val report = StringBuilder("calibration pins=${folder.refs.size} probes=${folder.probes.size} noneProbes=${folder.probes.count { it.second == null }} skippedPinsWithoutRef=${folder.skippedPins}\n")
        var ran = 0
        try {
            env("NOMIC_VISION_ONNX")?.let { p ->
                val e = OnnxImageEmbedder(p, NomicVision.STORED_DIMS, preprocess = { jpeg, cfg -> val a = JvmImages.argb(jpeg); NomicVision.preprocessArgb(a.pixels, a.w, a.h, cfg) })
                val fn: suspend (ByteArray) -> FloatArray = { b -> e.embed(listOf(b)).single() }
                report.append(runBlocking { folder.run("nomic-vision-v1.5", fn) }.second); ran++
            }
            env("SIGLIP_VISION_ONNX")?.let { p ->
                val e = SigLipImageEmbedder(File(p), decode = JvmImages::argb)
                val fn: suspend (ByteArray) -> FloatArray = { b -> e.embedImage(b) }
                report.append(runBlocking { folder.run("siglip2-b16-224", fn) }.second); ran++
            }
        } catch (x: Throwable) {
            if (x is UnsatisfiedLinkError || x is NoClassDefFoundError || x is ExceptionInInitializerError) assumeNoException("no desktop ONNX Runtime on the classpath (ORT_DESKTOP_JAR)", x)
            throw x
        }
        assumeTrue("neither NOMIC_VISION_ONNX nor SIGLIP_VISION_ONNX set", ran > 0)
        out.parentFile?.mkdirs(); out.writeText(report.toString())
        println("CALIBRATION wrote ${out.length()} bytes, spaces=$ran, pins=${folder.refs.size}, probes=${folder.probes.size}")
    }
}
