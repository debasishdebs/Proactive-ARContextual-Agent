package io.github.pca.nexusport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LogContentScanTest for the Nexus modules: the message of every `log(` / `Log.x(tag, ...)` call is ONE string literal whose `${}` pieces are counts, booleans, enum names or result-code names.
 * No card text, title, answer, transcript or exception message can reach a log by construction (no `+`, no `.format`, no `.message`, no `toString()`, no variable message).
 */
class NexusLogContentScanTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val files = listOf("nexus-port", "nexus-bridge").flatMap { m ->
        File(root, "$m/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val call = Regex("""(?<![\w.])(?:log|Log\.[diwe])\(""")
    private val safeInterp = Regex("""^(\w+(\.\w+)*\.name|NexusResult\.name\(\w+\)|\w+|mask and NexusFeatures\.TTS != 0|\w+\.size)$""")
    private val bannedNames = setOf("title", "text", "body", "answer", "transcript", "message", "cmd", "speech", "event", "it")

    private fun problems(): List<String> = buildList {
        for (f in files) {
            val src = f.readText()
            for (m in call.findAll(src)) {
                val line = src.substring(0, m.range.first).count { it == '\n' } + 1
                val rest = src.substring(m.range.last + 1)
                val arg = rest.substringBefore('\n').trim()
                // Log.i("TAG", "msg"): take the second argument
                val msg = if (m.value.startsWith("Log.")) arg.substringAfter(",").trim() else arg
                // a declaration or a forward of the sink itself (`log: (String) -> Unit`, `log = { ... }`) is not a call with a message
                if (!msg.startsWith("\"")) {
                    if (!(msg.startsWith("it)") || msg.startsWith("m)"))) add("${f.name}:$line message is not a string literal: $msg")
                    continue
                }
                val lit = msg.removePrefix("\"").let { s -> s.substring(0, closing(s)) }
                if ('+' in msg.substring(lit.length + 1).takeWhile { it != ')' }) add("${f.name}:$line concatenation")
                for (i in Regex("""\$\{([^}]*)\}""").findAll(lit)) {
                    val e = i.groupValues[1].trim()
                    if (!safeInterp.matches(e) || e in bannedNames) add("${f.name}:$line unsafe interpolation \${$e}")
                }
                if (Regex("""\$[A-Za-z]""").containsMatchIn(lit)) add("${f.name}:$line bare \$name interpolation")
            }
        }
    }

    private fun closing(s: String): Int { var i = 0; while (i < s.length && !(s[i] == '"' && (i == 0 || s[i - 1] != '\\'))) i++; return i }

    @Test fun logMessagesAreLiteralsWithCountsAndNamesOnly() { assertEquals(emptyList<String>(), problems()) }

    @Test fun thereAreLogCallsAtAllSoTheScanIsNotVacuous() {
        val n = files.sumOf { call.findAll(it.readText()).count() }
        kotlin.test.assertTrue(n >= 8, "scanned $n calls")
    }
}
