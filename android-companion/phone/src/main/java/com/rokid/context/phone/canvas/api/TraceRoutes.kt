package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.trace.CollectSession
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Trace and "Collect for tuning" routes (Developer mode; the router has done auth). [handle] returns null for a path that is not one of them. Messages are fixed texts.
 *
 * - `GET /api/traces` -> the stored traces (no manifest, no events); `GET /api/traces/{id}`
 * - `GET /api/collect` -> `{active, untilMs}`; `POST /api/collect/start` `{minutes?}` (1 to 720; default 20); `POST /api/collect/stop`
 *
 * The [CollectSession] is the hub's: a start or stop moves its flow and the hub sends the control; this class never talks to the glasses.
 */
class TraceRoutes(
    private val dao: CanvasSlice3Dao,
    private val collect: () -> CollectSession?,
    private val developerMode: () -> Boolean,
) {
    private val ID = Regex("^[A-Za-z0-9:_-]{1,64}$")

    suspend fun handle(req: HttpRequest): HttpResponse? {
        val seg = req.path.removePrefix("/api").split('/').filter { it.isNotEmpty() }
        val head = seg.firstOrNull()
        if (head != "traces" && head != "collect") return null
        val m = req.method.uppercase()
        if (!developerMode()) return HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        return try {
            when {
                head == "traces" && seg.size == 1 && m == "GET" -> HttpResponse.json(200, JsonArray(dao.liveTraces().map(::row)))
                head == "traces" && seg.size == 2 && m == "GET" ->
                    HttpResponse.json(200, row(seg[1].takeIf { ID.matches(it) }?.let { dao.trace(it) }?.takeIf { !it.tombstoned } ?: throw NoSuchElementException()))
                head == "collect" && seg.size == 1 && m == "GET" -> status()
                head == "collect" && seg.size == 2 && seg[1] == "start" && m == "POST" -> start(req)
                head == "collect" && seg.size == 2 && seg[1] == "stop" && m == "POST" -> { (collect() ?: return unavailable()).stop(); status() }
                else -> HttpResponse.error(404, "not_found", "Not found.")
            }
        } catch (e: NoSuchElementException) { HttpResponse.error(404, "not_found", "Not found.")
        } catch (e: IllegalArgumentException) { HttpResponse.error(400, "bad_request", "Bad request.")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { HttpResponse.error(500, "internal", "Something went wrong.") }
    }

    private fun unavailable() = HttpResponse.error(503, "unavailable", "The glasses link is not available.")

    private fun status(): HttpResponse {
        val c = collect() ?: return unavailable()
        val ctl = c.control()
        return HttpResponse.json(200, buildJsonObject { put("active", ctl.collect); put("untilMs", ctl.collectUntilMs?.let { JsonPrimitive(it) } ?: JsonNull) })
    }

    private fun start(req: HttpRequest): HttpResponse {
        val c = collect() ?: return unavailable()
        val minutes = if (req.body.isEmpty()) 20L else {
            val o = runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: throw IllegalArgumentException("body")
            val v = o["minutes"]
            if (v == null || v is JsonNull) 20L else (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("minutes")
        }
        require(minutes in 1..720) { "minutes" }
        c.start(minutes * 60_000L)
        return status()
    }

    private fun row(t: CanvasTraceEntity): JsonElement = buildJsonObject {
        put("traceId", t.traceId); put("source", t.source); put("status", t.status); put("revision", t.revision); put("exact", t.exact)
        put("startedAtMs", t.startedAtMs); put("endedAtMs", t.endedAtMs?.let { JsonPrimitive(it) } ?: JsonNull); put("createdAt", t.createdAt)
    }
}
