package com.rokid.context.phone.canvas

import com.rokid.context.phone.canvas.js.JsHarness
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composite node UI: the DOM smoke over the fake DOM (`composite-ui.test.js`) and the wiring into the editor. */
class CompositeUiContractTest {
    private fun text(n: String) = File("src/main/assets/canvas/$n").readText()

    @Test fun domSmoke() = JsHarness.assertAllPass("composite-ui.test.js")

    @Test fun theEditorHostsTheCompositeAndReusesTheSharedPrimitives() {
        val f = text("floweditor.js")
        assertTrue(f.contains("mountComposite") && f.contains("composite"))
        val c = text("compositeview.js")
        assertTrue("reuses paramform fields", c.contains("from './paramform.js'") && c.contains("fields("))
        assertTrue("reuses the dom helpers", c.contains("from './dom.js'"))
        assertTrue("uses the expression editor's box classes", c.contains("expr-box") && c.contains("expr-pill"))
        assertTrue(text("flowadapter.js").contains("composite"))
    }
}
