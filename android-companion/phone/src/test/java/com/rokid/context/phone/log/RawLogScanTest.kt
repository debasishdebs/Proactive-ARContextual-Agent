package com.rokid.context.phone.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The always-on log ratchet. Production code logs through `PcLog` (it reaches pca.log / capture_diag.log); a raw `android.util.Log` call is invisible in the handover logs.
 * `raw-log-baseline.txt` lists the files that still hold raw references and how many: a count may only go DOWN, a file that is not listed must hold none. Migrate a file, then delete its line.
 * (The sinks `PhoneLogSink` and `GlassesLogSink` are the logcat backend and stay listed.)
 */
class RawLogScanTest {
    private val root = File("..") // the Gradle test working directory is android-companion/phone

    private fun code(f: File): String = f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }
    private val rawLog = Regex("""android\.util\.Log|\bLog\.(v|d|i|w|e|wtf)\(""")

    private fun baseline(): Map<String, Int> =
        File("src/test/resources/raw-log-baseline.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { l -> l.substringBeforeLast(' ') to l.substringAfterLast(' ').toInt() }

    private fun productionFiles(): List<File> = listOf("glasses", "phone", "core", "services").flatMap { m ->
        File(root, m).walkTopDown().onEnter { it.name != "build" && it.name != "cpp" && it.name != "test" && it.name != "androidTest" }
            .filter { it.isFile && it.extension == "kt" && it.path.replace('\\', '/').contains("/src/main/") }.toList()
    }

    @Test fun rawPlatformLogReferencesOnlyGoDown() {
        val files = productionFiles()
        assertTrue("the scan sees the production tree: ${files.size}", files.size > 500)
        val base = baseline()
        val found = files.associate { it.relativeTo(root).path.replace('\\', '/') to rawLog.findAll(code(it)).count() }.filterValues { it > 0 }
        val grown = found.filter { (p, n) -> n > (base[p] ?: 0) }.map { (p, n) -> "$p has $n (allowed ${base[p] ?: 0})" }
        assertEquals("raw android.util.Log references that grew or are new: use PcLog", emptyList<String>(), grown)
        val stale = base.filter { (p, n) -> (found[p] ?: 0) < n }.keys.toList()
        assertEquals("baseline lines to lower or delete (the file got cleaner)", emptyList<String>(), stale)
    }

    @Test fun theThreeMigratedFilesAreNotInTheBaseline() {
        val gone = listOf("MarkGraph.kt", "CanvasPublisher.kt", "MemoryWipe.kt")
        assertEquals(emptyList<String>(), baseline().keys.filter { k -> gone.any { k.endsWith("/$it") } })
    }
}
