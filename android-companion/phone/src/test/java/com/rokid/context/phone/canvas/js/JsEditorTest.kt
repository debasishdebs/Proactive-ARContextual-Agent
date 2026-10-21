package com.rokid.context.phone.canvas.js

import org.junit.Test

/** The editor stream's pure modules, run for real on GraalJS (Tasks 11b–11d add their test files here). */
class JsEditorTest {
    @Test fun graphTests() = JsHarness.assertAllPass("graph.test.js")
    @Test fun validateTests() = JsHarness.assertAllPass("validate.test.js")
    @Test fun keysTests() = JsHarness.assertAllPass("keys.test.js")
    @Test fun flowAdapterTests() = JsHarness.assertAllPass("flowadapter.test.js")
    @Test fun paramFormTests() = JsHarness.assertAllPass("paramform.test.js")
    @Test fun draftStateTests() = JsHarness.assertAllPass("draftstate.test.js")

    @Test fun aFileWithoutTestsFails() {
        val e = runCatching { JsHarness.assertAllPass("zero-tests.fixture.js") }.exceptionOrNull()
        org.junit.Assert.assertTrue("zero tests must fail, was $e", e is AssertionError)
    }
}
