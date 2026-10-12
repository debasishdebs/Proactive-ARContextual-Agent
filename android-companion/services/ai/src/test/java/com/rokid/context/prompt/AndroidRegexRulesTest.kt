package com.rokid.context.prompt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Android's ICU regex engine rejects an unescaped `}` that does not close a `{n,m}` quantifier,
 * although the JVM accepts it, so JVM tests pass and the class fails to load on the phone.
 */
class AndroidRegexRulesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val triple = Regex("Regex\\(\"\"\"(.*?)\"\"\"|\"\"\"(.*?)\"\"\"\\.toRegex\\(", RegexOption.DOT_MATCHES_ALL)
    private val plain = Regex("Regex\\((?!\"\"\")\"((?:[^\"\\\\\\n]|\\\\.)*)\"|(?<![\"])\"((?:[^\"\\\\\\n]|\\\\.)*)\"\\.toRegex\\(")

    /** Every pattern passed to Regex("...")/Regex(""" ... """)/toRegex(), with Kotlin string escapes resolved. */
    private fun regexPatterns(source: String): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        triple.findAll(source).forEach { m -> found += m.range.first to (m.groups[1] ?: m.groups[2])!!.value }
        plain.findAll(source).forEach { m -> found += m.range.first to unescape((m.groups[1] ?: m.groups[2])!!.value) }
        return found.sortedBy { it.first }.map { it.second }
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                when (val n = s[i]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); else -> sb.append(n) }
            } else sb.append(c)
            i++
        }
        return sb.toString()
    }

    @Test fun scannerFindsEveryKindOfRegexLiteral() {
        val src = """
            val a = Regex(""${'"'}x}""${'"'})
            val b = Regex("a\\d{2}}b")
            val c = "q\\}".toRegex()
            val d = ""${'"'}z}""${'"'}.toRegex()
            val e = Regex("tab\there\"q\${'$'}")
        """.trimIndent()
        assertEquals(listOf("x}", "a\\d{2}}b", "q\\}", "z}", "tab\there\"q\$"), regexPatterns(src))
        assertEquals(true, hasStrayClosingBrace("a\\d{2}}b"))
        assertEquals(false, hasStrayClosingBrace("q\\}"))
    }

    @Test fun noRegexLiteralHasAnUnescapedClosingBrace() {
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .flatMap { f -> regexPatterns(f.readText()).map { f.name to it } }
            .filter { (_, pattern) -> hasStrayClosingBrace(pattern) }
            .map { (name, pattern) -> "$name: $pattern" }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private fun hasStrayClosingBrace(p: String): Boolean {
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> {
                    val close = p.indexOf('}', i)
                    if (close < 0 || !p.substring(i + 1, close).matches(Regex("\\d+(,\\d*)?"))) return true
                    i = close
                }
                c == '}' -> return true
            }
            i++
        }
        return false
    }
}
