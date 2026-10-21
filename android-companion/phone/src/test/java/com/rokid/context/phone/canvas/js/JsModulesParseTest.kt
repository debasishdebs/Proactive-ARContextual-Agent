package com.rokid.context.phone.canvas.js

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsModulesParseTest {
    @Test fun everyAssetModuleParses() {
        val files = File("src/main/assets/canvas").listFiles().orEmpty().filter { it.name.endsWith(".js") && !it.name.startsWith("vendor-") }.map { it.name }.sorted()
        for (required in listOf("app.js", "dom.js", "dag.js", "views.js", "boot.js")) assertTrue("missing $required in $files", required in files)
        for (f in files) assertEquals("syntax error in $f", null, JsHarness.parseError(f))
    }
}
