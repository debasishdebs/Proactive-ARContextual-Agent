package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.plan.WindowTolerance
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.ui.settings.capture.WindowToleranceStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3d: the stored `window_min_valid_pct` setting is the compile default of every window that names none (here seen through the try-it, which compiles with it). */
class WindowToleranceSettingTest {
    @After fun reset() { WindowTolerance.provider = { 90 } }

    private fun state(pct: Int?): String {
        val body = """{"graph":${TryItMatchesRunnerTest.CALM},"signals":{"stepHz":[0,0,null,null,0,0,0,0,0,0]}}"""
        val r = runBlocking { ExprRoutes().handle(HttpRequest("POST", "/api/expr/try", emptyMap(), emptyMap(), body.toByteArray()))!! }
        return Json.parseToJsonElement(String(r.body)).jsonObject["output"]!!.jsonObject["state"]!!.jsonPrimitive.content
    }

    @Test fun theSettingIsReadAtCompileTimeFromTheStore() {
        val store = WindowToleranceStore()
        WindowTolerance.provider = { store.pct.value }
        assertEquals("8 of 10 seconds valid, setting 90: not enough", "NOT_READY", state(null))
        store.set(80); assertEquals("BOOL", state(null))
        store.set(95); assertEquals("NOT_READY", state(null))
    }
}
