package com.rokid.context.ai.calibration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The harness plumbing on SYNTHETIC files (three bytes standing in for an image's colour): no real data, no model, no decoder. */
class CalibrationFolderTest {
    private fun png(rgb: Int): ByteArray = byteArrayOf((rgb shr 16).toByte(), (rgb shr 8).toByte(), rgb.toByte())

    private fun tmp(): File = File(System.getProperty("java.io.tmpdir"), "calib-" + System.nanoTime()).also { it.mkdirs() }
    private fun put(root: File, path: String, rgb: Int) { File(root, path).also { it.parentFile.mkdirs() }.writeBytes(png(rgb)) }

    /** A fake embedder: the three bytes (r,g,b) as a 3-D direction. */
    private val embed: suspend (ByteArray) -> FloatArray = { b ->
        floatArrayOf((b[0].toInt() and 255).toFloat() + 1f, (b[1].toInt() and 255).toFloat() + 1f, (b[2].toInt() and 255).toFloat() + 1f)
    }

    @Test fun `layout is read into pins refs probes and none probes and a pin without a ref is skipped`() {
        val root = tmp()
        put(root, "Red place/ref1.png", 0xFF0000); put(root, "Red place/a.png", 0xF01010); put(root, "Red place/b.png", 0xE02020)
        put(root, "Blue place/ref.png", 0x0000FF); put(root, "Blue place/a.png", 0x1010F0)
        put(root, "No ref place/a.png", 0x00FF00)
        put(root, "_none/x.png", 0x00FF00); put(root, "_none/y.png", 0xFFFF00)
        try {
            val f = CalibrationFolder.load(root)
            assertEquals(2, f.refs.size); assertEquals(1, f.skippedPins)
            assertEquals(5, f.probes.size) // 3 pin probes + 2 none
            assertEquals(2, f.probes.count { it.second == null }); assertEquals(2, f.probes.count { it.second == "Red place" })
            val (res, text) = runBlocking { f.run("fake", embed) }
            assertEquals(3, res.probesWithTruth); assertEquals(3, res.top1Correct)
            assertEquals(1.0, res.best!!.precision, 0.0)
            assertFalse("folder names must not reach the report", text.contains("Red place") || text.contains("Blue"))
            assertTrue(text.contains("space=fake pins=2 probes=5 withTruth=3 top1Correct=3"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `a missing folder is refused and the git guard sees this repo`() {
        var refused = false
        try { CalibrationFolder.load(File("/nonexistent-calib-dir")) } catch (_: IllegalArgumentException) { refused = true }
        assertTrue(refused)
        assertTrue(CalibrationFolder.insideGitTree(File(".")))
        assertFalse(CalibrationFolder.insideGitTree(File(System.getProperty("java.io.tmpdir"), "calib-outside")))
    }
}
