package com.rokid.context.phone.canvas.api

import com.rokid.context.capture.plan.SignalCatalog
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.ui.settings.capture.SignalConfigStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `/api/signals` (v0.9.7 A): `GET` lists the catalogue with `enabled` flags and the derived definitions; `POST /api/signals/enabled {name, enabled}` switches a catalogue signal for
 * guards; `PUT /api/signals/derived/<name> {graph}` saves a derived signal (validated by the guard validator, 400 with `problems` otherwise); `DELETE /api/signals/derived/<name>`.
 * Auth, Host/Origin policy and the body limit are the router's. A derived signal is only ever evaluated in SHADOW/TRIAL (the nodes that use it are not liveable).
 */
class SignalRoutes(private val store: SignalConfigStore) {
    suspend fun handle(req: HttpRequest): HttpResponse? {
        val path = req.path
        if (path != "/api/signals" && !path.startsWith("/api/signals/")) return null
        val m = req.method.uppercase()
        return try {
            when {
                path == "/api/signals" && m == "GET" -> HttpResponse.json(200, list())
                path == "/api/signals/enabled" && m == "POST" -> enabled(req)
                path.startsWith("/api/signals/derived/") && m == "PUT" -> put(path.removePrefix("/api/signals/derived/"), req)
                path.startsWith("/api/signals/derived/") && m == "DELETE" -> { store.deleteDerived(path.removePrefix("/api/signals/derived/")); HttpResponse.json(200, buildJsonObject { put("ok", true) }) }
                else -> HttpResponse.error(405, "bad_request", "Method not allowed.")
            }
        } catch (e: Throwable) { HttpResponse.error(500, "internal", "Something went wrong.") }
    }

    private fun body(req: HttpRequest): JsonObject? = runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()

    private fun list(): JsonObject {
        val c = store.config.value
        return buildJsonObject {
            put("signals", JsonArray(SignalCatalog.all.map { s -> buildJsonObject { put("name", s.name); put("type", s.type.name.lowercase()); put("unit", s.unit); put("enabled", s.name !in c.disabled) } }))
            put("derived", JsonArray(c.derived.map { d -> buildJsonObject { put("name", d.name); put("ref", "d_" + d.name); put("graph", d.graph) } }))
        }
    }

    private fun enabled(req: HttpRequest): HttpResponse {
        val b = body(req) ?: return HttpResponse.error(400, "bad_request", "Bad request.")
        val name = (b["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val on = (b["enabled"] as? JsonPrimitive)?.booleanOrNull
        if (name == null || on == null || !store.setEnabled(name, on)) return HttpResponse.error(400, "bad_request", "Not a catalogue signal.")
        return HttpResponse.json(200, buildJsonObject { put("ok", true) })
    }

    private fun put(name: String, req: HttpRequest): HttpResponse {
        val b = body(req) ?: return HttpResponse.error(400, "bad_request", "Bad request.")
        val problems = store.saveDerived(name, b["graph"])
        return if (problems.isEmpty()) HttpResponse.json(200, buildJsonObject { put("ok", true) })
        else HttpResponse.error(400, "invalid_signal", "The derived signal is not valid.", problems)
    }
}
