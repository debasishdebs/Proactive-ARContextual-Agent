package com.rokid.context.phone.canvas.api

import com.rokid.context.core.model.DeviceCapability
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `/api/devices` (v0.9.8 slice D): `GET` lists the four device capabilities (`device` phone|glasses, `capability` mic|display, `enabled`, optional `constraint`); `POST /api/devices/capability
 * {device, capability, enabled}` switches one (400 for a pair that is not in the catalogue). Auth, Host/Origin policy and the body limit are the router's. A user setting only: it never reaches the classifier or capture.
 */
class DeviceRoutes(private val store: DeviceCapabilityStore) {
    suspend fun handle(req: HttpRequest): HttpResponse? {
        val path = req.path
        if (path != "/api/devices" && !path.startsWith("/api/devices/")) return null
        val m = req.method.uppercase()
        return try {
            when {
                path == "/api/devices" && m == "GET" -> HttpResponse.json(200, list())
                path == "/api/devices/capability" && m == "POST" -> set(req)
                else -> HttpResponse.error(405, "bad_request", "Method not allowed.")
            }
        } catch (e: Throwable) { HttpResponse.error(500, "internal", "Something went wrong.") }
    }

    private fun item(c: DeviceCapability) = buildJsonObject {
        put("device", c.device); put("capability", c.capability); put("enabled", c.enabled)
        c.constraint?.let { put("constraint", it) }
    }

    private fun list() = buildJsonObject { put("capabilities", JsonArray(store.capabilities.value.list().map(::item))) }

    private fun set(req: HttpRequest): HttpResponse {
        val b = runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return HttpResponse.error(400, "bad_request", "Bad request.")
        fun str(k: String) = (b[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val on = (b["enabled"] as? JsonPrimitive)?.booleanOrNull
        val device = str("device"); val cap = str("capability")
        if (device == null || cap == null || on == null || !store.set(device, cap, on)) return HttpResponse.error(400, "bad_request", "Not a device capability.")
        return HttpResponse.json(200, buildJsonObject { put("ok", true) })
    }
}
