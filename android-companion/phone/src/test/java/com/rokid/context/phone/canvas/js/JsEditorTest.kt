package com.rokid.context.phone.canvas.js

import org.junit.Test

/** The editor stream's pure modules, run for real on GraalJS (Tasks 11b–11d add their test files here). */
class JsEditorTest {
    @Test fun graphTests() = JsHarness.assertAllPass("graph.test.js")
    @Test fun validateTests() = JsHarness.assertAllPass("validate.test.js")
    @Test fun keysTests() = JsHarness.assertAllPass("keys.test.js")
}
