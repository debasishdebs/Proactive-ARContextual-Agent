package com.rokid.context.glasses.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source scans over the glasses production tree (same discovery as MarkNoLogTest). */
class GlassesLogSourceScanTest {
    private val main: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "src/main/java/com/rokid/context/glasses") }.first { it.isDirectory }
    private fun sources() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun diagFileIsGoneAndNothingNewUsesIt() {
        val bad = sources().filter { Regex("""\bDiagFile\b""").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue(bad.isEmpty(), "DiagFile was replaced by GlassesLogSink: $bad")
    }

    @Test fun productionNeverCallsTheTestOnlyReset() {
        val bad = sources().filter { it.readText().contains("testOnlyReset") }.map { it.name }
        assertTrue(bad.isEmpty(), "$bad")
    }

    @Test fun captureDiagLogIsKeptThroughDeleteAllSoNothingOutsideTheLogPackageTouchesIt() {
        val bad = sources().filter { it.parentFile.name != "log" && it.name != "DiagLog.kt" }
            .filter { Regex("""capture_diag|GlassesLogSink|GlassesLogging""").containsMatchIn(it.readText()) }.map { it.name }
        // the single installer call lives in the DiagLog shim; GlassesRuntime goes through the shim
        assertEquals(emptyList<String>(), bad)
    }

    // ---- privacy audit of the DiagLog templates (LGC3, plan 3.3) ----

    /** Identifier-shaped dynamic fields the plan forbids in a log template. */
    private val forbidden = listOf(
        Regex("""\$\{?frameId\b"""), Regex("""\$\{?id\b"""), Regex("""\$\{[^}]*\.frameId\b"""), Regex("""\$\{[^}]*\bid\b[^}]*}"""),
        Regex("""\.message\b"""), Regex("""absolutePath"""), Regex("""\.path\b"""), Regex("""\$\{?(photoId|jobId|traceId|askId|url|host|token|code)\b"""),
    )

    /** Every line that emits through DiagLog or through a `log(` lambda that GlassesRuntime/CaptureService bind to DiagLog. */
    private fun emissions(text: String) = text.lines().withIndex().filter { (_, l) -> Regex("""DiagLog\.[iw]\(|(^|[^.\w])log\(""").containsMatchIn(l) && l.contains("\"") }

    private fun violations(text: String) = emissions(text).filter { (_, l) -> forbidden.any { it.containsMatchIn(l) } }.map { it.index + 1 to it.value.trim() }

    @Test fun scannerCatchesAFrameIdTemplate() {
        assertEquals(1, violations("""DiagLog.i(TAG, "buffered ${'$'}frameId (1 KB)")""").size)
        assertEquals(1, violations("""log("dropped ${'$'}{f.meta.frameId}: x")""").size)
        assertEquals(1, violations("""log("refused: ${'$'}{e.message}")""").size)
        assertEquals(0, violations("""log("dropped frames=${'$'}n")"""). size)
    }

    @Test fun noDiagLogTemplateCarriesAnIdentifier() {
        val bad = sources().flatMap { f -> violations(f.readText()).map { "${f.name}:${it.first} ${it.second}" } }
        assertTrue(bad.isEmpty(), "identifiers in DiagLog templates:\n" + bad.joinToString("\n"))
    }
}
