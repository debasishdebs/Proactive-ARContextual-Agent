package com.rokid.context.phone.canvas

import com.rokid.context.phone.canvas.js.JsHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP9: the browser side of the laptop Python helper. The asset strings (what the page says and what it must never contain) and a DOM smoke over the fake DOM
 * (`src/test/js/helper-ui.test.js`): textarea, helper card, grant and status, per-run confirmation STATUS (waiting with a countdown, declined, expired; no control),
 * the STOPPED_FORGET status shown distinctly, and results through `textContent` only.
 */
class HelperUiContractTest {
    private val dir = File("src/main/assets/canvas")
    private fun text(n: String) = File(dir, n).readText()
    /** The module's code without comments, so a banned word in a comment does not count and one in code does. */
    private fun code(n: String) = text(n).replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }

    @Test fun theHelperModuleHasThePhrasesTheUserReads() {
        val t = text("helperview.js")
        for (s in listOf(
            "Waiting for your tap on the phone", "STOPPED_FORGET", "not_confirmed", "frame_forget", "declined", "expired", "Nothing was sent",
            "/api/helper-status", "py.fn", "Laptop helper", "not paired", "on the phone",
        )) assertTrue("helperview.js lacks '$s'", t.contains(s))
    }

    @Test fun theViewsUseTheHelperModule() {
        val v = text("views.js")
        assertTrue(v.contains("from './helperview.js'"))
        for (s in listOf("statusLabel(", "errorText(", "helperCard(", "loadHelperStatus(", "hasPython(")) assertTrue("views.js does not call $s", v.contains(s))
        assertFalse("the raw status code is not what the run header shows any more", v.contains("h('strong', null, String(run.status))"))
        assertFalse("the raw error code is not what a node card shows any more", v.contains("'error: ' + it.error"))
    }

    @Test fun theHelperModuleIsStatusOnlyWithNoControlAndNoWrite() {
        val c = code("helperview.js")
        // no control of any kind is built here
        for (tag in listOf("button", "input", "select", "textarea", "form", "'a'")) assertFalse("helperview.js builds a $tag", Regex("""h\(\s*['"]${tag.trim('\'')}['"]""").containsMatchIn(c))
        assertFalse(c.contains("btn("))
        assertFalse(Regex("""addEventListener|onclick|onchange""", RegexOption.IGNORE_CASE).containsMatchIn(c))
        // the only request is the status read
        assertFalse("a write", Regex("""\bapi\(\s*['"](POST|PUT|PATCH|DELETE)""").containsMatchIn(c))
        assertFalse("a write", Regex("""['"](POST|PUT|PATCH|DELETE)['"]""").containsMatchIn(c))
        assertEquals(listOf("/api/helper-status"), Regex("""/api/[A-Za-z0-9/_:-]+""").findAll(c).map { it.value }.toSet().toList())
        // nothing in the bundle talks to the helper's own routes or the job routes
        for (f in dir.listFiles()!!.filter { it.isFile && !it.name.startsWith("vendor-") && it.extension == "js" }) {
            val s = f.readText()
            assertFalse("${f.name} reaches /api/helper/", s.contains("/api/helper/"))
            assertFalse("${f.name} reaches /api/helper-jobs", s.contains("/api/helper-jobs"))
            assertFalse("${f.name} sends to a confirm route", Regex("""/api/[A-Za-z0-9/_-]*(confirm|decline)""").containsMatchIn(s))
        }
        // textContent only: the shared DOM helpers are the sink
        assertFalse(c.contains("innerHTML") || c.contains("outerHTML") || c.contains("insertAdjacentHTML") || c.contains("dangerouslySetInnerHTML"))
        assertTrue(text("helperview.js").contains("from './dom.js'"))
    }

    @Test fun theStylesNameTheHelperStates() {
        val css = text("style.css")
        for (cls in listOf(".helper-card", ".helper-waiting", ".forgotten")) assertTrue("style.css lacks $cls", css.contains(cls))
    }

    @Test fun thePythonFieldsAreTextareasWithTheirCap() {
        val p = text("paramform.js")
        assertTrue(p.contains("'source'") && p.contains("'outSchema'") && p.contains("'textarea'"))
        val e = text("floweditor.js")
        assertTrue("the textarea carries the descriptor's cap", Regex("""<textarea[^>]*maxLength=\$\{spec\.maxLen\}""").containsMatchIn(e))
    }

    @Test fun theDeviceChecklistHasThePythonHelperSection() {
        val doc = File("../../docs/superpowers/specs/2026-10-21-canvas-device-checklist.md").readText()
        assertTrue(doc.contains("Python helper"))
        for (s in listOf("Waiting for your tap on the phone", "decline", "expire", "Forget", "no Confirm button in the browser", "STOPPED_FORGET", "Disconnect", "grant")) {
            assertTrue("the checklist lacks '$s'", doc.contains(s, ignoreCase = true))
        }
        assertTrue("the section comes before 'Report back'", doc.indexOf("Python helper") < doc.indexOf("Report back"))
    }

    // ---- the DOM smoke ----
    @Test fun domSmoke() = JsHarness.assertAllPass("helper-ui.test.js")
}
