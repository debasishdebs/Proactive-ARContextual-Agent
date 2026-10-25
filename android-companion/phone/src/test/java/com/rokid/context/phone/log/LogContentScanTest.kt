package com.rokid.context.phone.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Log lines carry event names, counts, enum names and durations only. This scan reads every `PcLog.d/i/w/e(` statement in the production
 * sources and rejects a message that interpolates an expression naming something that can hold user data or a secret: an exception message,
 * a frame/wire/pause/cluster id, a path, text, a question, a prompt, a URL, a token, a secret or a pairing code. (A failure is logged by class
 * name; an id is replaced by a count.) Lines logged through a `log(...)` lambda are reviewed by hand; the lambda's body is a PcLog call here.
 */
class LogContentScanTest {
    private val root = File("..")

    private val forbidden = Regex(
        """\.message\b|localizedMessage|\.cause\b|\bframeId\b|\bwireId\b|\bpauseId\b|\bclusterId\b|\bframe\.id\b|\bjob\.id\b|absolutePath|\.canonicalPath|\.path\b|\.text\b|\bquestion\b|\bprompt|\burl\b|\btoken|secret|passphrase|pairing|\bendpoint\.name\b|\bapiKey\b""",
        RegexOption.IGNORE_CASE,
    )
    private val call = Regex("""\bPcLog\.[diwe]\(""")

    private fun productionFiles(): List<File> = listOf("glasses", "phone", "core", "services").flatMap { m ->
        File(root, m).walkTopDown().onEnter { it.name != "build" && it.name != "cpp" && it.name != "test" && it.name != "androidTest" }
            .filter { it.isFile && it.extension == "kt" && it.path.replace('\\', '/').contains("/src/main/") }.toList()
    }

    /** Each PcLog call as one statement: following lines are joined until its parentheses balance (at most 6 lines). */
    internal fun statements(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val m = call.find(lines[i])
            if (m == null) { i++; continue }
            var stmt = lines[i].substring(m.range.first)
            var depth = stmt.count { it == '(' } - stmt.count { it == ')' }
            var extra = 0
            while (depth > 0 && extra < 6 && i + 1 < lines.size) {
                i++; extra++
                stmt += " " + lines[i].trim()
                depth += lines[i].count { it == '(' } - lines[i].count { it == ')' }
            }
            out += stmt
            i++
        }
        return out
    }

    /** The `$x` and `${expr}` parts of a message, class-name lookups (`javaClass.simpleName`) removed. */
    internal fun interpolations(stmt: String): List<String> =
        (Regex("""\$\{([^}]*)\}""").findAll(stmt).map { it.groupValues[1] } + Regex("""\$([A-Za-z_]\w*)""").findAll(stmt).map { it.groupValues[1] })
            .filterNot { "simpleName" in it }.toList()

    internal fun violations(name: String, lines: List<String>): List<String> =
        statements(lines).filter { s -> interpolations(s).any { forbidden.containsMatchIn(it) } }.map { "$name: ${it.trim()}" }

    @Test fun noPcLogMessageInterpolatesUserDataOrAnId() {
        val files = productionFiles()
        assertTrue("the scan sees the production tree: ${files.size}", files.size > 500)
        val bad = files.flatMap { violations(it.name, it.readLines()) }
        assertEquals("a log message must hold event names, counts and enum names only (class name for a failure)", emptyList<String>(), bad)
    }

    @Test fun theScannerCatchesEveryForm() {
        listOf(
            "PcLog.w(TAG, \"failed: \${e.message}\")",
            "PcLog.i(TAG, \"frame \$frameId stored\")",
            "PcLog.i(TAG, \"frame \${frame.id}\")".replace("frame.id", "frame.id"),
            "PcLog.i(TAG, \"at \${file.absolutePath}\")",
            "PcLog.d(TAG, \"q=\$question\")",
            "PcLog.e(TAG, \"url \$url\")",
            "PcLog.i(TAG, \"x \" +\n  \"\${r.text}\")",
        ).forEach { assertTrue(it, violations("bad", it.lines()).isNotEmpty()) }
        listOf(
            "PcLog.w(TAG, \"failed: \${e.javaClass.simpleName}\")",
            "PcLog.i(TAG, \"files=\${candidates.size} ok=\$ok\")",
            "val s = \"\$text\"",
        ).forEach { assertTrue(it, violations("ok", it.lines()).isEmpty()) }
    }
}
