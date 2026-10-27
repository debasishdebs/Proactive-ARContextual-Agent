package com.rokid.context.phone.canvas.api

import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.ui.settings.capture.SignalConfigStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.9.7 A: `/api/signals`: the catalogue with its enabled flags, derived definitions (validated by the guard validator) and the on/off switch. */
class SignalRoutesTest {
    private val store = SignalConfigStore()
    private val routes = SignalRoutes(store)
    private val def = """{"steps":[{"name":"s","inputs":[{"name":"k","const":1.2}],"nodes":[{"id":"w","op":"win","agg":"mean","sec":10,"name":"stepHz"},{"id":"c","op":"cmp","f":">"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}],"out":"s"}"""
    private fun call(method: String, path: String, body: String = "") = runBlocking { routes.handle(HttpRequest(method, path, emptyMap(), emptyMap(), body.toByteArray()))!! }
    private fun obj(r: com.rokid.context.phone.canvas.server.HttpResponse) = Json.parseToJsonElement(String(r.body)).jsonObject

    @Test fun otherPathsAreNotOurs() = assertNull(runBlocking { routes.handle(HttpRequest("GET", "/api/other", emptyMap(), emptyMap(), ByteArray(0))) })

    @Test fun listShowsEveryCatalogueSignalEnabledByDefault() {
        val o = obj(call("GET", "/api/signals"))
        val sigs = o["signals"] as JsonArray
        assertTrue(sigs.size >= 9)
        assertTrue(sigs.all { (it as JsonObject)["enabled"].toString() == "true" })
        assertEquals(0, (o["derived"] as JsonArray).size)
    }

    @Test fun putValidatesAndStoresAndDeleteRemoves() {
        val ok = call("PUT", "/api/signals/derived/walky", """{"graph":$def}""")
        assertEquals(200, ok.status)
        assertEquals(1, (obj(call("GET", "/api/signals"))["derived"] as JsonArray).size)
        assertEquals(400, call("PUT", "/api/signals/derived/walky", """{"graph":${def.replace("stepHz", "nope")}}""").status)
        assertEquals(400, call("PUT", "/api/signals/derived/Bad", """{"graph":$def}""").status)
        assertEquals(200, call("DELETE", "/api/signals/derived/walky").status)
        assertEquals(0, (obj(call("GET", "/api/signals"))["derived"] as JsonArray).size)
    }

    @Test fun enabledSwitchAndBadInput() {
        assertEquals(200, call("POST", "/api/signals/enabled", """{"name":"accVar","enabled":false}""").status)
        assertEquals(setOf("accVar"), store.config.value.disabled)
        assertEquals(400, call("POST", "/api/signals/enabled", """{"name":"zzz","enabled":false}""").status)
        assertEquals(400, call("POST", "/api/signals/enabled", "not json").status)
        assertEquals(405, call("PATCH", "/api/signals").status)
    }
}
