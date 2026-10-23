package com.rokid.context.glasses.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    //
    // AUDITED DYNAMIC-FIELD SOURCES (what may appear after a `$` in a template that reaches capture_diag.log):
    //   counts and sizes (buffer.size, sent/dropped counters, KB, w/h, seq numbers), enum names (reason codes such as SkipReason, HeatLevel,
    //   UiMode), booleans, durations, Throwable.javaClass.simpleName. Closed classifications: RestartDiagnostics `reason` (ExitClassifier
    //   result) and `ui_mode` (whitelisted by UiModeStore); Heartbeat builders (numbers/enums only, `guarded` logs the class name).
    //   PlanController/AutoRevert revert reasons come from fixed local strings (hardening gap: the API takes a free String).
    //   FORBIDDEN: frame/photo/job/trace ids, paths and file names, URLs, hosts, tokens, Throwable.message, phone-supplied strings (FrameAck.reason).
    //   TraceRecorder `drop("bounds:" + e.message)` was the one free-text producer: now the exception class name.

    /** Identifier-shaped dynamic fields the plan forbids in a log template. */
    private val forbidden = listOf(
        Regex("""\$\{?frameId\b"""), Regex("""\$\{?id\b"""), Regex("""\$\{[^}]*\.frameId\b"""), Regex("""\$\{[^}]*\bid\b[^}]*}"""),
        Regex("""\.message\b"""), Regex("""absolutePath"""), Regex("""\.path\b"""), Regex("""\$\{?(photoId|jobId|traceId|askId|url|host|token|code)\b"""),
    )

    private fun isComment(l: String) = l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** The full argument text (across lines, balanced parentheses) of every `DiagLog.i/w(` or bare `log(` call. */
    private fun emittedCalls(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        for (m in Regex("""DiagLog\.[iw]\(|(?<![.\w])log\(""").findAll(text)) {
            var depth = 1; var i = m.range.last + 1
            while (i < text.length && depth > 0) { when (text[i]) { '(' -> depth++; ')' -> depth-- }; i++ }
            out += (text.substring(0, m.range.first).count { it == '\n' } + 1) to text.substring(m.range.first, i)
        }
        return out
    }

    /** Producer files whose strings reach DiagLog INDIRECTLY (built into a variable or returned to a caller that logs it). */
    private val producerFiles = listOf("Heartbeat.kt", "RestartDiagnostics.kt", "TraceRecorder.kt", "StatusLogThrottle.kt", "BatteryBudget.kt", "FeatureLogger.kt")

    private fun producerLines(name: String, text: String) =
        text.lines().withIndex().filter { (_, l) -> !isComment(l) && l.contains('"') && (l.contains('$') || l.contains(".message")) }.map { it.index + 1 to it.value.trim() }
            .let { rows -> if (name in producerFiles) rows else emptyList() }
            // TraceRecorder is mostly storage code: only its log/drop emissions are messages (its `j.id` keys are file logic, not logged)
            .filter { (_, l) -> name != "TraceRecorder.kt" || l.contains("log(") || l.contains("drop(") }

    private fun violations(name: String, text: String): List<String> {
        val calls = emittedCalls(text).filter { (_, c) -> forbidden.any { it.containsMatchIn(c) } }.map { "${it.first} ${it.second.lines().first().trim()}" }
        val indirect = producerLines(name, text).filter { (_, l) -> forbidden.any { it.containsMatchIn(l) } }.map { "${it.first} $it" }
        return calls + indirect
    }

    @Test fun scannerCatchesAFrameIdTemplate() {
        assertEquals(1, violations("X.kt", """DiagLog.i(TAG, "buffered ${'$'}frameId (1 KB)")""").size)
        assertEquals(1, violations("X.kt", """log("dropped ${'$'}{f.meta.frameId}: x")""").size)
        assertEquals(1, violations("X.kt", """log("refused: ${'$'}{e.message}")""").size)
        assertEquals(0, violations("X.kt", """log("dropped frames=${'$'}n")""").size)
    }

    @Test fun scannerCatchesMultilineAndIndirectProducers() {
        val multi = "DiagLog.i(\n    TAG,\n    \"buffered \" +\n        \"${'$'}{meta.frameId}\"\n)"
        assertEquals(1, violations("X.kt", multi).size, "a template split over lines")
        assertEquals(1, violations("Heartbeat.kt", "val line = \"hb v=2 job=${'$'}{jobId}\"\nDiagLog.i(TAG, line)").size, "an intermediate string in a producer file")
        assertEquals(1, violations("TraceRecorder.kt", "drop(\"bounds:\" + e.message)").size, "the TraceRecorder free-text producer")
        assertEquals(0, violations("Heartbeat.kt", "// \"x ${'$'}frameId\" in a comment").size)
        assertEquals(0, violations("Other.kt", "val s = \"a ${'$'}n\"").size)
    }

    @Test fun theNamedProducerFilesExistAndAreScanned() {
        for (n in listOf("Heartbeat.kt", "RestartDiagnostics.kt", "TraceRecorder.kt")) assertTrue(sources().any { it.name == n }, "$n must be audited")
    }

    @Test fun traceRecorderBoundsDropNamesTheClassNotTheMessage() {
        val t = sources().single { it.name == "TraceRecorder.kt" }.readText()
        assertTrue(t.contains("""drop("bounds:" + e.javaClass.simpleName)"""))
        assertFalse(t.contains("""drop("bounds:" + e.message)"""))
    }

    @Test fun theRuntimeBuildsTheLogDirWithoutTheFilesDirGetter() {
        val line = sources().single { it.name == "GlassesRuntime.kt" }.readLines().single { it.contains("DiagLog.install(") }
        assertFalse(line.contains("filesDir"), line)
    }

    @Test fun noDiagLogTemplateCarriesAnIdentifier() {
        val bad = sources().flatMap { f -> violations(f.name, f.readText()).map { "${f.name}:$it" } }
        assertTrue(bad.isEmpty(), "identifiers in DiagLog templates:\n" + bad.joinToString("\n"))
    }
}
