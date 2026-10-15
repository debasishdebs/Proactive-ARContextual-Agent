package com.rokid.context.glasses.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The camera LED is the only notice bystanders get (spec P1.1 "Camera LED"): the app never switches it off, suppresses it
 * or works around it. LED time is reduced only by opening the camera less. This scans every glasses source (Kotlin, Java,
 * XML, manifests) for any torch, flash, light-service or LED control, case-insensitively, and proves on synthetic sources
 * that the scanner can fail.
 */
class LedSourceRulesTest {
    private val banned: List<Pair<String, Regex>> = listOf(
        "enableTorch" to Regex("enableTorch", RegexOption.IGNORE_CASE),
        "setTorchMode" to Regex("setTorchMode", RegexOption.IGNORE_CASE),
        "FLASH_MODE" to Regex("FLASH_MODE", RegexOption.IGNORE_CASE),
        "setFlashMode" to Regex("setFlashMode", RegexOption.IGNORE_CASE),
        "ALWAYS_FLASH" to Regex("CONTROL_AE_MODE_ON_ALWAYS_FLASH", RegexOption.IGNORE_CASE),
        "AUTO_FLASH" to Regex("CONTROL_AE_MODE_ON_AUTO_FLASH", RegexOption.IGNORE_CASE),
        "FLASH_STATE" to Regex("FLASH_STATE|FLASH_REQUIRED|FLASH_FIRE", RegexOption.IGNORE_CASE),
        "/sys/class/leds" to Regex("/sys/class/leds", RegexOption.IGNORE_CASE),
        "led brightness" to Regex("led[_ -]?brightness|brightness[_ -]?led", RegexOption.IGNORE_CASE),
        "LedManager" to Regex("LedManager|LedController|LedService", RegexOption.IGNORE_CASE),
        "led_ vendor" to Regex("(?<![a-z0-9])led_\\w+", RegexOption.IGNORE_CASE),
        "light service" to Regex("""LIGHTS_SERVICE|LightsManager|LightsRequest|getSystemService\(\s*\\?"light\\?"""", RegexOption.IGNORE_CASE),
    )

    /** Names of the banned patterns that [text] contains. */
    private fun hits(text: String) = banned.filter { it.second.containsMatchIn(text) }.map { it.first }

    private fun scan(root: File): List<String> =
        root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml", "cpp", "h", "c", "json", "gradle", "kts") }
            .flatMap { f -> hits(f.readText()).map { "${f.name}: $it" } }.toList()

    @Test fun noTorchFlashOrLedControlInGlassesSources() {
        val src = File("src/main")
        assertTrue("the scan must see the sources (cwd ${File(".").absolutePath})", src.walkTopDown().any { it.name == "CaptureService.kt" })
        val found = scan(src)
        assertTrue("$found", found.isEmpty())
    }

    @Test fun theScannerFailsOnASyntheticBadSource() {
        val dir = createTempDir("ledscan")
        try {
            File(dir, "Clean.kt").writeText("class Clean { fun f() = 1 }")
            assertEquals(emptyList<String>(), scan(dir))
            File(dir, "Bad.kt").writeText("fun on(c: Camera) { c.cameraControl.EnableTorch(true) }")
            assertEquals(listOf("Bad.kt: enableTorch"), scan(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theScanWouldCatchEachBannedCall() {
        assertEquals(listOf("enableTorch"), hits("camera.cameraControl.enableTorch(true)"))
        assertEquals(listOf("enableTorch"), hits("camera.cameraControl.ENABLETORCH(true)"))
        assertEquals(listOf("FLASH_MODE"), hits("ImageCapture.FLASH_MODE_ON"))
        assertEquals(listOf("setFlashMode"), hits("builder.setFlashMode(0)"))
        assertEquals(listOf("/sys/class/leds"), hits("File(\"/sys/class/leds/camera/x\")"))
        assertEquals(listOf("setTorchMode"), hits("cameraManager.setTorchMode(id, false)"))
        assertEquals(listOf("ALWAYS_FLASH"), hits("CONTROL_AE_MODE_ON_ALWAYS_FLASH"))
        assertEquals(listOf("AUTO_FLASH"), hits("CONTROL_AE_MODE_ON_AUTO_FLASH"))
        assertEquals(listOf("led brightness"), hits("writeLedBrightness(0)"))
        assertEquals(listOf("LedManager"), hits("val m = LedManager.get()"))
        assertEquals(listOf("led_ vendor"), hits("rokid_led_off()"))
        assertEquals(listOf("light service"), hits("getSystemService(\"light\")"))
        assertEquals(emptyList<String>(), hits("called_back(); val failed = ledger"))
    }
}
