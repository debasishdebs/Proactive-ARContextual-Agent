package com.rokid.context.phone.canvas

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A source scan of the web UI bundle: the CSP forbids inline script and style, and model output is untrusted (no HTML sinks). */
class CanvasAssetsTest {
    private val dir = File("src/main/assets/canvas")
    private val names = listOf(
        "index.html", "app.js", "dom.js", "views.js", "boot.js", "style.css", "photos.js",
        "graph.js", "validate.js", "keys.js", "flowadapter.js", "paramform.js", "draftstate.js",
        "editorlogic.js", "editorflow.js", "floweditor.js", "jsx-shim.js",
        "format.js", "diffview.js", "runlogic.js", "versions.js", "liveview.js", "asks.js",
        "pareto.js", "replayview.js", "paretoview.js", "helperview.js", "exprgraph.js", "exprview.js",
    )
    private fun text(n: String) = File(dir, n).readText()
    private val js get() = names.filter { it.endsWith(".js") }
    /** Our own files: vendored `vendor-*` files are pinned by SHA-256 (VendorLockTest), not scanned. */
    private val all get() = dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith("vendor-") }

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
        val banned = listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function", "dangerouslySetInnerHTML")
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

    @Test fun scriptsAreModulesExceptPinnedVendor() {
        val tags = Regex("<script[^>]*>", RegexOption.IGNORE_CASE).findAll(text("index.html")).map { it.value }.toList()
        val classic = listOf("vendor-react.js", "vendor-react-dom.js", "jsx-shim.js", "vendor-xyflow.js", "vendor-htm.js")
        assertTrue(tags.size > classic.size)
        classic.forEachIndexed { i, src ->
            assertTrue("script $i must be classic defer $src, was ${tags[i]}", tags[i].contains("src=\"$src\"") && tags[i].contains("defer") && !tags[i].contains("type="))
        }
        for (t in tags.drop(classic.size)) assertTrue("not a module: $t", t.contains("type=\"module\""))
        assertTrue("first module comes after the classic scripts", tags[classic.size].contains("type=\"module\""))
        val css = Regex("<link[^>]*stylesheet[^>]*>", RegexOption.IGNORE_CASE).findAll(text("index.html")).map { it.value }.toList()
        assertTrue("vendor-xyflow.css before style.css", css.indexOfFirst { it.contains("vendor-xyflow.css") } in 0 until css.indexOfFirst { it.contains("style.css\"") })
    }

    @Test fun shimLeaksNoGlobalsIntoTheClassicScope() {
        val t = text("jsx-shim.js").trim()
        assertTrue("jsx-shim.js must be one IIFE (a top-level const would collide with later classic scripts)", t.startsWith("(") && t.endsWith(")();") || t.endsWith("}());"))
    }

    @Test fun noDangerousInnerHtmlInOwnCode() {
        for (f in all) assertFalse("${f.name} uses dangerouslySetInnerHTML", f.readText().contains("dangerouslySetInnerHTML"))
    }

    @Test fun tokenNeverInQueryString() {
        for (f in all) {
            val t = f.readText()
            assertFalse("${f.name} ?token", t.contains("?token"))
            assertFalse("${f.name} &token", t.contains("&token"))
        }
    }

    @Test fun jsUnderSizeBudget() {
        val lines = all.filter { it.name.endsWith(".js") }.sumOf { it.readText().lines().size }
        assertEquals(true, lines <= 4200) // X5 added the boxed expression editor (exprgraph.js + exprview.js, ~650 lines)
    }

    @Test fun checkReadsLiveEditorValues() {
        val f = text("floweditor.js")
        assertTrue("Check must send the text as typed now", f.contains("texts.current"))
        assertTrue(text("editorlogic.js").contains("export function checkPayload"))
    }

    @Test fun floweditorTouchesReactOnlyInsideMount() {
        val f = text("floweditor.js")
        val top = f.substring(0, f.indexOf("export function mountEditor"))
        assertFalse("floweditor.js must not read globals at import time", top.contains("globalThis") || top.contains("React."))
        for (n in listOf("editorlogic.js", "editorflow.js", "photos.js", "graph.js", "validate.js", "keys.js", "flowadapter.js", "paramform.js", "draftstate.js", "format.js", "diffview.js", "runlogic.js", "pareto.js")) {
            val t = text(n)
            assertFalse("$n (pure) must not touch React", Regex("globalThis|React\\.[a-z]|ReactDOM|useState|createElement|document\\.").containsMatchIn(t))
        }
    }

    @Test fun inspectorRolesMatchTheRoleEnum() {
        val kt = File("../services/ai/src/main/java/com/rokid/context/ai/Role.kt").readText()
        val roles = Regex("^\\s+([A-Z_]+)\\(Capability", RegexOption.MULTILINE).findAll(kt).map { it.groupValues[1] }.toList()
        assertTrue("could not read roles from Role.kt", roles.size >= 6)
        val js = text("editorlogic.js")
        val listed = Regex("ROLES = \\[([^\\]]*)]").find(js)!!.groupValues[1].split(",").map { it.trim().trim('\'') }.filter { it.isNotEmpty() }
        assertEquals("ROLES in editorlogic.js drifted from Role.kt (EMBED is not allowed on ai.prompt)", roles.filter { it != "EMBED" }.sorted(), listed.sorted())
    }

    @Test fun oldHandBuiltDagIsGone() {
        assertFalse(File(dir, "dag.js").exists())
        assertFalse(File("src/test/js/dag.test.js").exists())
        for (f in all) assertFalse("${f.name} still imports dag.js", f.readText().contains("dag.js"))
    }

    @Test fun photosNeverPutTheTokenInAUrl() {
        val t = text("photos.js")
        assertTrue(t.contains("/api/frames/"))
        assertFalse(t.contains("token"))
    }

    @Test fun pollStopsOnlyOnRunState() {
        val v = text("views.js")
        val line = v.lines().first { it.contains("pollAction(") }
        assertTrue(line.contains("drawnStatus !== String(run.status)"))
        assertFalse(line.contains("editing"))
    }
}
