package com.rokid.context.phone.canvas.js

import org.junit.Test

class JsDomTest {
    @Test fun domTests() = JsHarness.assertAllPass("dom.test.js")
    @Test fun photosTests() = JsHarness.assertAllPass("photos.test.js")
    @Test fun bootTests() = JsHarness.assertAllPass("app-boot.test.js")
}
