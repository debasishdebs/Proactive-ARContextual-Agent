package com.rokid.context.phone.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Log lines carry event names, counts, enum/state names and durations only, and this is enforced by CONSTRUCTION: the message argument of every
 * `PcLog.d/i/w/e(tag, msg)` call, and of every call to an injected sink named `log(` / `warn(`, must be ONE string literal. A template may interpolate only
 * an expression that [safeExpr] accepts (a count or size, a class name, an enum/state name, a short reviewed list of names, a reviewed helper).
 * Everything else is a violation: `+` concatenation, `.format(`, `String.format`, a variable message built elsewhere, `.message`, `.localizedMessage`,
 * `.cause`, `stackTraceToString`, `toString()`. Fix an offender by logging a literal event name plus `e.javaClass.simpleName`.
 * The only variable messages are the reviewed forwarders in [FORWARDERS]: `{ PcLog.i(TAG, it) }` adapters. The text they forward comes from the injected `log(` / `warn(` calls
 * of the owning classes (hundreds of status lines); those are scanned with the lighter rule [lenientProblem]: still ONE literal (no concatenation, no variable message), the
 * ALWAYS_BAD forms and [DENY] names refused, but their interpolations are not held to the short allow-list.
 */
class LogContentScanTest {
    private val root = File("..")

    private fun productionFiles(): List<File> = listOf("glasses", "phone", "core", "services").flatMap { m ->
        File(root, m).walkTopDown().onEnter { it.name != "build" && it.name != "cpp" && it.name != "test" && it.name != "androidTest" }
            .filter { it.isFile && it.extension == "kt" && it.path.replace('\\', '/').contains("/src/main/") }.toList()
    }

    /** `PcLog.x(tag, MSG)`: the message is the second argument. `log(MSG)` / `warn(MSG)` of an injected sink: the first. */
    private val pcLogCall = Regex("""\bPcLog\.(?:[diwe]|milestone)\(""")
    private val sinkCall = Regex("""(?<![\w.])(?:log|warn)\(""")

    /** Reviewed `{ PcLog.i(TAG, it) }` adapters (file name to the variable they forward). */
    private val FORWARDERS = mapOf(
        "DiagLog.kt" to setOf("msg"), "GlassesPauses.kt" to setOf("it"), "ForegroundFallback.kt" to setOf("it"), "PlanGraph.kt" to setOf("it"),
        "VoiceGraph.kt" to setOf("it"), "GlassesRelauncher.kt" to setOf("it"), "HubGraph.kt" to setOf("it"), "Hub.kt" to setOf("m"),
        "GlassesWatchdog.kt" to setOf("it"), "AutoStopController.kt" to setOf("it"), "GlassesWearEstimate.kt" to setOf("it"), "AutoStopWake.kt" to setOf("it"), "StateBroadcaster.kt" to setOf("it"), "CxrGlassesLink.kt" to setOf("it"), "SessionSlot.kt" to setOf("it"),
        "AndroidSpeaker.kt" to setOf("it.wire"), "GlassesRuntime.kt" to setOf("\"ptt: \$it\""),
        "WatchLinkState.kt" to setOf("it"), // WatchDropLog: only "watch drop reason=<fixed token> n=<count>"
    )

    /** Reviewed variable messages of injected `log(` sinks (file name to the one expression): fixed-format counters and states, or call-site constants. */
    private val SINK_VARIABLES = mapOf(
        "TraceRecorder.kt" to setOf("r.line()"), "ContextHub.kt" to setOf("stats.line(_decision.value.state)"), "CanvasServer.kt" to setOf("line"),
        "FeatureLogger.kt" to setOf("classifier.features()"), "VoicePipeline.kt" to setOf("why"),
        "RestartDiagnostics.kt" to setOf("formatExits(it, now)"), // fixed format: "exits n=<count> [reason= status= imp= pssKB= rssKB= ago=]..." (numbers only)
    )

    /** Names that hold a count, an enum/state name, a wire channel name or a fixed call-site constant (each reviewed; `what`/`how`/`why`/`cmd`/`channel` are literals at their call sites). */
    private val SAFE_NAMES = setOf(
        "appended", "attempt", "attempts", "backfill", "capacity", "channel", "cls", "cmd", "code", "deleted", "described", "done", "extracted", "failed", "how", "left", "leftovers",
        "loadedDistractors", "memories", "missing", "n", "objects", "ok", "parsed", "pending", "purged", "reason", "rejected", "remote", "report", "resumed", "saved", "scanned", "source",
        "state", "stored", "streak", "swept", "transient", "types", "versionNo", "w", "wearing", "what", "why", "written", "health", "catalogId", "origin", "wearCheck", "s", "o.error", "d.reason",
        "PROTOCOL_VERSION", "MAX_ATTEMPTS", "MAX_RUNS", "RetryPolicy.MAX_ATTEMPTS", "PipelineStatsStore.K_V2_SIDECAR_LOST", "linkState()", "stage(eval)",
        "call.key.name", "callClass.name", "key.name", "kind.name", "outcome.name", "e.kind", "e.retryable", "e.remote", "e.errno", "r.code", "r.errorCode", "r.isSuccess", "r.origin",
        "done.verdict", "reply.truncated", "it.pipeSize", "it.capacityBytes", "h.bufferSize", "h.glassesBatteryPct", "h.resumedAtMs.size", "measured.glassesMinusPhoneMs",
        "m.pipeSize", "sink.pipeSize", "counts.routed", "counts.fast", "counts.dup", "counts.clustered", "w != null", "intent == null", "format(records, System.currentTimeMillis(), AutoStopWake.exempt(context), live)", "code?.name ?: \"NONE\"",
        "clock() - t0", "(now - it.ts).coerceAtLeast(0L)", "job.attemptCount + 1", "runAttemptCount + 1", "results.size - failed.size", "loadedLabelled.size",
        "file.exists()", "file.length()", "r768.queries", "r768.smallQueries", "c::class.simpleName", "bytes?.size ?: 0", "eval.problems().size",
        // Memory-index lifecycle lines (IndexMaintenance, EmbeddingWorker, NightIndexWorker): locals that hold a space id (space_<uuid>), a model id or its hash prefix, a count,
        // a boolean, a duration phrase or a fixed reason/phrase built in those classes; never memory text, captions, queries or photo content.
        "key", "role", "total", "pct", "waited", "spaceId", "model", "verb", "textTotal", "textTodo", "imagesTotal", "imagesTodo", "before", "prev", "textHave", "imageCounts", "unembeddable",
        "duration", "tail", "missingPhotos", "paused", "gate", "pendingRebuild", "rebuild", "waitS", "consecutive", "attemptNo",
        "reps.values.sumOf { it.size }", "counts.closed.joinToString(\",\").ifEmpty { \"-\" }", "runCatching { l.isWearingCheckOn() }",
    )
    private val SAFE_PATTERNS = listOf(
        Regex("""[\w.?]+\.(size|length|count\(\))"""),                         // a count
        Regex("""[\w.?]+\.javaClass\.simpleName|[\w.?]+\?\.javaClass\?\.simpleName"""), // a class name
        Regex("""(com\.rokid\.context\.ai\.)?SafeLog\.describe\(\w+\)"""),     // reviewed helper: class, kind, retryable, http code
        Regex("""WearingStatus\.describe\(\w+\)"""),                           // reviewed helper: a fixed word
    )
    private val ALWAYS_BAD = Regex("""\.message\b|localizedMessage|\.cause\b|stackTraceToString|toString\(\)|\.format\(|String\.format""")

    /** Names that can hold user data, a secret or an id; refused in a lenient (injected `log(`) message. */
    private val DENY = Regex("""\b(frameId|wireId|pauseId|clusterId|absolutePath|canonicalPath|question|prompt\w*|url|token\w*|secret\w*|passphrase|pairing\w*|apiKey|spoken|transcript|answer\w*|text|utterance|query)\b""", RegexOption.IGNORE_CASE)

    private fun safeExpr(e: String): Boolean {
        val x = e.trim()
        if (ALWAYS_BAD.containsMatchIn(x)) return false
        return x in SAFE_NAMES || SAFE_PATTERNS.any { it.matches(x) }
    }

    /** Arguments of the call whose `(` has just been consumed: split at top level, honouring strings, templates and brackets. Null when unbalanced. */
    internal fun splitArgs(s: String): List<String>? {
        val args = mutableListOf<String>(); val cur = StringBuilder(); var depth = 0; var i = 0; var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                cur.append(c)
                if (c == '\\' && i + 1 < s.length) { cur.append(s[i + 1]); i++ }
                else if (c == '"') inStr = false
                else if (c == '$' && i + 1 < s.length && s[i + 1] == '{') {
                    var j = i + 2; var d = 1
                    while (j < s.length && d > 0) { if (s[j] == '{') d++ else if (s[j] == '}') d--; j++ }
                    cur.append(s, i + 1, j); i = j - 1
                }
            } else when (c) {
                '"' -> { inStr = true; cur.append(c) }
                '(', '[', '{' -> { depth++; cur.append(c) }
                ')', ']', '}' -> { if (depth == 0) { args += cur.toString().trim(); return args }; depth--; cur.append(c) }
                ',' -> if (depth == 0) { args += cur.toString().trim(); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
            i++
        }
        return null
    }

    /** Parses a string literal starting at [start] (the opening quote). Returns its template expressions and the index after the closing quote, or null when it is not a well-formed literal. */
    private fun parseLiteral(s: String, start: Int): Pair<List<String>, Int>? {
        if (start >= s.length || s[start] != '"') return null
        val exprs = mutableListOf<String>(); var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> i += 2
                c == '"' -> return exprs to i + 1
                c == '$' && i + 1 < s.length && s[i + 1] == '{' -> {
                    var j = i + 2; var d = 1
                    while (j < s.length && d > 0) {
                        when (s[j]) {
                            '"' -> { j = (parseLiteral(s, j)?.second ?: return null); continue }
                            '{' -> d++
                            '}' -> d--
                        }
                        j++
                    }
                    if (d != 0) return null
                    exprs += s.substring(i + 2, j - 1); i = j
                }
                c == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_') -> {
                    var j = i + 1; while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    exprs += s.substring(i + 1, j); i = j
                }
                else -> i++
            }
        }
        return null
    }

    /** The template expressions when [msg] is exactly one string literal, else null. */
    private fun literalExprs(msg: String): List<String>? = parseLiteral(msg, 0)?.takeIf { it.second == msg.length }?.first

    /** Why [msg] is not allowed, or null when it is a literal with safe interpolations (or a reviewed forwarder variable / enum event). */
    internal fun problem(file: String, msg: String, viaPcLog: Boolean): String? {
        if (viaPcLog && msg in (FORWARDERS[file] ?: emptySet())) return null
        if (!viaPcLog && msg in (SINK_VARIABLES[file] ?: emptySet())) return null
        if (!viaPcLog && Regex("""SpeakerEvent\.[A-Z_]+""").matches(msg)) return null
        val exprs = literalExprs(msg) ?: return "message is not a single string literal"
        if (!viaPcLog) return exprs.filter { ALWAYS_BAD.containsMatchIn(it) || DENY.containsMatchIn(it) }.let { if (it.isEmpty()) null else "interpolates ${it.joinToString { x -> "`$x`" }}" }
        val bad = exprs.filterNot(::safeExpr)
        return if (bad.isEmpty()) null else "interpolates ${bad.joinToString { "`$it`" }}"
    }

    internal fun violations(file: String, text: String): List<String> {
        val out = mutableListOf<String>()
        if (file == "PcLog.kt") return out // the logger itself: `log(level, tag, msg)` forwards
        fun scan(rx: Regex, argIndex: Int, viaPcLog: Boolean) {
            for (m in rx.findAll(text)) {
                val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
                if (!viaPcLog && Regex("""(fun|val|var)\s+$""").containsMatchIn(before)) continue
                val args = splitArgs(text.substring(m.range.last + 1)) ?: run { out += "$file: unbalanced call ${m.value}"; null } ?: continue
                if (args.size != argIndex + 1 && !viaPcLog || args.size <= argIndex) continue // a sink call has exactly one argument (log(level, tag, msg) ports and DB `log(id, ..)` helpers are other APIs)
                problem(file, args[argIndex], viaPcLog)?.let { out += "$file: ${args[argIndex].take(120)} -> $it" }
            }
        }
        scan(pcLogCall, 1, true)
        scan(sinkCall, 0, false)
        return out
    }

    @Test fun everyLogMessageIsALiteralWithSafeInterpolations() {
        val files = productionFiles()
        assertTrue("the scan sees the production tree: ${files.size}", files.size > 500)
        val bad = files.flatMap { violations(it.name, it.readText()) }
        assertEquals("a log message must be a literal event name with counts, enum names or a class name", emptyList<String>(), bad)
    }

    @Test fun theScannerRejectsEveryBypassForm() {
        listOf(
            "PcLog.w(TAG, \"failed: \" + e.message)",
            "PcLog.w(TAG, \"failed: \" + detail)",
            "PcLog.w(TAG, \"%s\".format(e.message))",
            "PcLog.w(TAG, String.format(\"%s\", e.message))",
            "PcLog.i(TAG, \"x \$text\")",
            "PcLog.i(TAG, \"x \${r.text}\")",
            "PcLog.w(TAG, msg)",
            "PcLog.w(TAG, built)",
            "PcLog.w(TAG, \"x \${e.message}\")",
            "PcLog.w(TAG, \"x \${e.localizedMessage}\")",
            "PcLog.w(TAG, \"x \${e.cause}\")",
            "PcLog.w(TAG, \"x \${e.stackTraceToString()}\")",
            "PcLog.w(TAG, \"x \${obj.toString()}\")",
            "PcLog.i(TAG, \"frame \$frameId\")",
            "PcLog.i(TAG, \"at \${file.absolutePath}\")",
            "PcLog.i(TAG, \"q=\" +\n  question)",
            "PcLog.e(TAG, \"url \$url\")",
            "log(\"heard \$spoken\")",
            "warn(\"x \" + detail)",
            "log(message)",
        ).forEach { assertTrue(it, violations("Bad.kt", it).isNotEmpty()) }
    }

    @Test fun theScannerAcceptsTheSafeForms() {
        listOf(
            "PcLog.w(TAG, \"failed: \${e.javaClass.simpleName}\")",
            "PcLog.i(TAG, \"files=\${candidates.size} ok=\$ok\")",
            "PcLog.w(TAG, \"x\", e)",
            "PcLog.i(TAG, \"run failed: \${SafeLog.describe(e)}\")",
            "log(SpeakerEvent.INIT_FAILED)",
            "private val log: (String) -> Unit = { PcLog.i(TAG, \"fixed\") }",
            "fun log(x: String) = Unit",
        ).forEach { assertEquals(it, emptyList<String>(), violations("Ok.kt", it)) }
        assertEquals(emptyList<String>(), violations("Hub.kt", "PcLog.i(TAG, m)"))      // a reviewed forwarder
        assertTrue(violations("Other.kt", "PcLog.i(TAG, m)").isNotEmpty())              // the same text elsewhere is not
    }
}
