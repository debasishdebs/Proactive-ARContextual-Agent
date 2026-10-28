package com.rokid.context.phone.canvas.api

import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.9.8 slice D: `/api/devices`: the four capabilities with their enabled flags and the switch. */
class DeviceRoutesTest {
    private val store = DeviceCapabilityStore()
    private val routes = DeviceRoutes(store)
    private fun call(method: String, path: String, body: String = "") = runBlocking { routes.handle(HttpRequest(method, path, emptyMap(), emptyMap(), body.toByteArray()))!! }
    private fun list() = (Json.parseToJsonElement(String(call("GET", "/api/devices").body)).jsonObject["capabilities"] as JsonArray).map { it as JsonObject }

    @Test fun otherPathsAreNotOurs() = assertNull(runBlocking { routes.handle(HttpRequest("GET", "/api/signals", emptyMap(), emptyMap(), ByteArray(0))) })

    @Test fun listShowsFourCapabilitiesAllEnabledAndTheHudConstraint() {
        val l = list()
        assertEquals(4, l.size); assertTrue(l.all { it["enabled"].toString() == "true" })
        assertEquals("\"hud\"", l.single { it["device"].toString() == "\"glasses\"" && it["capability"].toString() == "\"display\"" }["constraint"].toString())
    }

    @Test fun postSwitchesOneAndRefusesWhatIsNotInTheCatalogue() {
        assertEquals(200, call("POST", "/api/devices/capability", """{"device":"phone","capability":"mic","enabled":false}""").status)
        assertFalse(store.capabilities.value.micOn(false)); assertTrue(store.capabilities.value.micOn(true))
        assertEquals(1, list().count { it["enabled"].toString() == "false" })
        for (bad in listOf("""{"device":"watch","capability":"mic","enabled":false}""", """{"device":"phone","capability":"mic"}""", """{"device":"phone","capability":"mic","enabled":"no"}""", "nope"))
            assertEquals(bad, 400, call("POST", "/api/devices/capability", bad).status)
        assertEquals(405, call("DELETE", "/api/devices").status)
    }
}
