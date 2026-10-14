package com.rokid.context.glasses.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The camera LED is the only notice bystanders get (spec P1.1 "Camera LED"): the app never switches it off, suppresses it
 * or works around it. LED time is reduced only by opening the camera less. This scans the glasses sources for any torch,
 * flash or LED control.
 */
class LedSourceRulesTest {
    private val banned = listOf("enableTorch", "FLASH_MODE", "/sys/class/leds", "setTorchMode")

    private fun sources(): List<File> =
        File("src/main").walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java") || it.name.endsWith(".xml")) }.toList()

    private fun hits(text: String) = banned.filter { it in text }

    @Test fun noTorchFlashOrLedControlInGlassesSources() {
        val files = sources()
        assertTrue("the scan must see the sources (cwd ${File(".").absolutePath})", files.any { it.name == "CaptureService.kt" })
        val found = files.flatMap { f -> hits(f.readText()).map { "${f.name}: $it" } }
        assertTrue("$found", found.isEmpty())
    }

    @Test fun theScanWouldCatchEachBannedCall() {
        assertEquals(listOf("enableTorch"), hits("camera.cameraControl.enableTorch(true)"))
        assertEquals(listOf("FLASH_MODE"), hits("ImageCapture.FLASH_MODE_ON"))
        assertEquals(listOf("/sys/class/leds"), hits("File(\"/sys/class/leds/camera/brightness\")"))
        assertEquals(listOf("setTorchMode"), hits("cameraManager.setTorchMode(id, false)"))
    }
}
