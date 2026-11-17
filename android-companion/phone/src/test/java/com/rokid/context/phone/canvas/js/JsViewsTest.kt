package com.rokid.context.phone.canvas.js

import org.junit.Test

/** The views stream (Task 12): pure modules, the non-editor views over a fake DOM, and the real app.js booted over stubs. */
class JsViewsTest {
    @Test fun formatTests() = JsHarness.assertAllPass("format.test.js")
    @Test fun diffViewTests() = JsHarness.assertAllPass("diffview.test.js")
    @Test fun runLogicTests() = JsHarness.assertAllPass("runlogic.test.js")
    @Test fun viewsSmokeTests() = JsHarness.assertAllPass("views-smoke.test.js")
    @Test fun appRoutesTests() = JsHarness.assertAllPass("app-routes.test.js")
    @Test fun appPairBootTests() = JsHarness.assertAllPass("app-pair.test.js")
    @Test fun paretoTests() = JsHarness.assertAllPass("pareto.test.js")
    @Test fun replayViewTests() = JsHarness.assertAllPass("replay-views.test.js")
    @Test fun devicesViewTests() = JsHarness.assertAllPass("devices-view.test.js")
    @Test fun signalsViewTests() = JsHarness.assertAllPass("signals-view.test.js")
    @Test fun planPublishTests() = JsHarness.assertAllPass("planpublish.test.js")
}
