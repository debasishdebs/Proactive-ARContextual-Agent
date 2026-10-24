package com.rokid.context.phone.canvas

import com.rokid.context.ai.canvas.plan.ExprProgram
import com.rokid.context.ai.canvas.plan.ExprRole
import com.rokid.context.phone.canvas.js.JsHarness
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * X5: the boxed `expr.guard` editor. Asset-string contracts (no HTML sink, no confirm control, the param form mounts it, unknown kinds degrade),
 * the DOM smoke over the fake DOM (`expr-ui.test.js`), and the default graph checked by the real Kotlin validator (ExprProgram.check).
 * Verified under GraalVM only: no browser and no device were used.
 */
class ExprEditorUiContractTest {
    private val dir = File("src/main/assets/canvas")
    private fun text(n: String) = File(dir, n).readText()
    private fun code(n: String) = text(n).replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }

    @Test fun theDefaultGraphPassesTheRealValidator() {
        val json = Regex("DEFAULT_GRAPH_JSON = '([^']+)'").find(text("exprgraph.js"))!!.groupValues[1]
        val (program, diags) = ExprProgram.parse(ExprRole.GUARD, Json.parseToJsonElement(json))
        assertEquals(emptyList<Any>(), diags)
        val checked = program!!.check()
        assertEquals(emptyList<Any>(), checked.problems)
        assertEquals(3, checked.nodes)
    }

    @Test fun theDefaultValueGraphIsAValidNumberForTheDefaultTargetAndCompiles() {
        val json = Regex("DEFAULT_VALUE_GRAPH_JSON = '([^']+)'").find(text("exprgraph.js"))!!.groupValues[1]
        val (program, diags) = ExprProgram.parse(ExprRole.VALUE, Json.parseToJsonElement(json))
        assertEquals(emptyList<Any>(), diags)
        assertEquals(emptyList<Any>(), program!!.check().problems)
        val low = com.rokid.context.ai.canvas.plan.ExprLowering.lowerAll(listOf(com.rokid.context.ai.canvas.plan.ExprLowering.Entry("v", Json.parseToJsonElement(json), ExprRole.VALUE, "REST", "capPerMin")))
        assertEquals(emptyList<String>(), low.problems)
    }

    @Test fun theEditorSourcesHaveNoHtmlSinkAndNoConfirm() {
        for (n in listOf("exprgraph.js", "exprview.js")) {
            val c = code(n)
            for (b in listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function", "confirm(", "prompt(", "alert(", "fetch(", "XMLHttpRequest", "localStorage"))
                assertFalse("$n uses $b", c.contains(b))
        }
    }

    @Test fun theInspectorMountsTheEditorForGraphParams() {
        val f = code("floweditor.js")
        assertTrue(f.contains("mountExprEditor") && f.contains("GraphControl") && f.contains("f.control === 'graph'"))
        val p = code("paramform.js")
        assertTrue(p.contains("case 'graph'") && p.contains("f.unknown"))
        assertTrue(code("graph.js").contains("case 'graph'"))
    }

    @Test fun domSmoke() = JsHarness.assertAllPass("expr-ui.test.js")
}
