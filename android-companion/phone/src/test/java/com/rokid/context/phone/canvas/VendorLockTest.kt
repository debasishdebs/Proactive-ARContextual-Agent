package com.rokid.context.phone.canvas

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vendored web libraries are pinned by SHA-256 and byte count in `vendor-lock.txt`; the lock itself is never served. */
class VendorLockTest {
    private val dir = File("src/main/assets/canvas")
    private data class Line(val name: String, val spec: String, val url: String, val bytes: Long, val sha: String, val licence: String)

    private fun lock(): List<Line> = File(dir, "vendor-lock.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
        val p = it.trim().split(Regex("\\s+"))
        assertEquals("lock line needs 6 fields: $it", 6, p.size)
        Line(p[0], p[1], p[2], p[3].toLong(), p[4], p[5])
    }

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun vendorFiles() = dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("vendor-") && it.name != "vendor-lock.txt" && it.name != "vendor-licenses.txt" }

    @Test fun everyVendorFileIsLocked() {
        val lines = lock().associateBy { it.name }
        val files = vendorFiles()
        assertTrue("no vendor files", files.isNotEmpty())
        for (f in files) {
            val l = lines[f.name] ?: throw AssertionError("${f.name} has no lock line")
            assertEquals("${f.name} bytes", l.bytes, f.length())
            assertEquals("${f.name} sha256", l.sha, sha256(f))
        }
    }

    @Test fun everyLockLineHasAFile() {
        for (l in lock()) assertTrue("${l.name} is locked but missing", File(dir, l.name).isFile)
    }

    @Test fun licencesListed() {
        val t = File(dir, "vendor-licenses.txt").readText()
        for (n in listOf("React", "@xyflow/react", "htm")) assertTrue("licence text for $n", t.contains(n))
    }

    @Test fun lockNotServed() {
        // Same rule as CanvasRouter.STATIC_PATH.
        val rule = Regex("^/[a-z0-9_-]+\\.(html|js|css|svg)$")
        assertFalse(rule.matches("/vendor-lock.txt"))
        assertFalse(rule.matches("/vendor-licenses.txt"))
        for (f in vendorFiles()) assertTrue("${f.name} must be servable", rule.matches("/${f.name}"))
    }
}
