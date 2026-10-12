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
    private val literal = Regex("Regex\\(\"\"\"(.*?)\"\"\"")

    @Test fun noRegexLiteralHasAnUnescapedClosingBrace() {
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .flatMap { f -> literal.findAll(f.readText()).map { f.name to it.groupValues[1] } }
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
