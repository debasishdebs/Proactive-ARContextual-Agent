package com.rokid.context.phone.canvas

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A source scan of the web UI bundle: the CSP forbids inline script and style, and model output is untrusted (no HTML sinks). */
class CanvasAssetsTest {
    private val dir = File("src/main/assets/canvas")
    private val names = listOf("index.html", "app.js", "dom.js", "dag.js", "views.js", "style.css")
    private fun text(n: String) = File(dir, n).readText()
    private val js get() = names.filter { it.endsWith(".js") }
    private val all get() = dir.listFiles().orEmpty().filter { it.isFile }

    @Test fun allFilesPresent() {
        for (n in names) assertTrue("missing $n", File(dir, n).isFile)
    }

    @Test fun noExternalUrls() {
        for (f in all) {
            val t = f.readText()
            assertFalse("${f.name} has http://", t.contains("http://"))
            assertFalse("${f.name} has https://", t.contains("https://"))
        }
    }

    @Test fun noHtmlSinks() {
        val banned = listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function")
        for (f in all) for (b in banned) assertFalse("${f.name} uses $b", f.readText().contains(b))
    }

    @Test fun noInlineScriptOrHandlers() {
        val html = text("index.html")
        val inline = Regex("<script(?![^>]*\\ssrc=)[^>]*>", RegexOption.IGNORE_CASE)
        assertFalse("inline script", inline.containsMatchIn(html))
        assertFalse("event handler attribute", Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("style attribute", Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        // The scripts must not set handlers or inline style text either.
        for (n in js) {
            val t = text(n)
            assertFalse("$n sets an on* attribute", Regex("setAttribute\\(\\s*['\"]on", RegexOption.IGNORE_CASE).containsMatchIn(t))
            assertFalse("$n sets style text", Regex("setAttribute\\(\\s*['\"]style['\"]").containsMatchIn(t))
        }
    }

    @Test fun scriptsAreModules() {
        val tags = Regex("<script[^>]*>", RegexOption.IGNORE_CASE).findAll(text("index.html")).map { it.value }.toList()
        assertTrue(tags.isNotEmpty())
        for (t in tags) assertTrue("not a module: $t", t.contains("type=\"module\""))
    }

    @Test fun tokenNeverInQueryString() {
        for (f in all) {
            val t = f.readText()
            assertFalse("${f.name} ?token", t.contains("?token"))
            assertFalse("${f.name} &token", t.contains("&token"))
        }
    }

    @Test fun jsUnderSizeBudget() {
        val lines = js.sumOf { text(it).lines().size }
        assertEquals(true, lines <= 2000)
    }
}
