package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.plan.ReplayBusy
import com.rokid.context.canvas.plan.ReplayDeveloperModeOff
import com.rokid.context.canvas.plan.ReplayService
import com.rokid.context.canvas.plan.ReplayUnknownTrace
import com.rokid.context.canvas.plan.SweepRequest
import com.rokid.context.canvas.plan.SweepService
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.sweep.SweepMode
import com.rokid.context.capture.sweep.SweepParam
import com.rokid.context.capture.sweep.SweepPlanner
import com.rokid.context.capture.sweep.SweepSpec
import com.rokid.context.memory.db.CanvasSweepEntity
import com.rokid.context.memory.db.TraceSources
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `/api/sweeps` routes (S16, S77). Written in Task 24 and NOT registered: Task 33 hands it to `CanvasApi`/`CanvasRouter` (auth, Host/Origin policy and body size are the router's).
 * [handle] returns null for a path that is not a sweep route.
 *
 * - `POST /api/sweeps` `{traceIds, mode: "grid"|"random", params: [{path, values: [...]} | {path, min, max, step}], samples?, seed?, base?: {glance|gate|policy partial config}}` -> `202 {sweepId}`
 * - `GET /api/sweeps` -> the newest jobs; `GET /api/sweeps/{id}?sort=hours|captures|pct|opens|unobserved|index&dir=asc|desc` -> the job and, once DONE, its estimate table (sortable)
 * - `POST /api/sweeps/{id}/cancel`
 *
 * A refused spec answers 400 with the fixed refusal text of [SweepPlanner] (it names at most a param path made of letters, digits and dots).
 */
class SweepRoutes(private val service: SweepService) {
    companion object {
        private val ID = Regex("^[A-Za-z0-9:_-]{1,64}$")
        private val SAFE = Regex("^[A-Za-z0-9_.:()<>=, \\u2013/|-]{1,200}$")
        const val MAX_LIST = 50
        /** The sortable columns of the result table: the `sort` query value -> the point's field. */
        val SORT_KEYS = mapOf(
            "index" to "index", "captures" to "capturesPerMin", "opens" to "opensPerMin", "pct" to "estimatedPctPerMin", "hours" to "estimatedHoursFromFull", "unobserved" to "unobservedOpensPct",
        )
    }

    suspend fun handle(req: HttpRequest): HttpResponse? {
        val seg = req.path.removePrefix("/api").split('/').filter { it.isNotEmpty() }
        if (seg.firstOrNull() != "sweeps") return null
        val m = req.method.uppercase()
        return try {
            when {
                seg.size == 1 && m == "POST" -> start(req)
                seg.size == 1 && m == "GET" -> HttpResponse.json(200, JsonArray(service.list(MAX_LIST).map { row(it, withResult = false, sort = "index", desc = false) }))
                seg.size == 2 && m == "GET" -> {
                    val sort = req.query["sort"] ?: "index"
                    require(sort in SORT_KEYS) { "sort" }
                    val dir = req.query["dir"] ?: "asc"
                    require(dir == "asc" || dir == "desc") { "dir" }
                    HttpResponse.json(200, row(service.get(id(seg[1])) ?: throw NoSuchElementException(), withResult = true, sort = sort, desc = dir == "desc"))
                }
                seg.size == 3 && seg[2] == "cancel" && m == "POST" -> cancel(id(seg[1]))
                else -> HttpResponse.error(404, "not_found", "Not found.")
            }
        } catch (e: ReplayBusy) { HttpResponse.error(409, "busy", "A replay or sweep is already running.")
        } catch (e: ReplayDeveloperModeOff) { HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        } catch (e: ReplayUnknownTrace) { HttpResponse.error(404, "not_found", "Trace not found.")
        } catch (e: NoSuchElementException) { HttpResponse.error(404, "not_found", "Not found.")
        } catch (e: IllegalArgumentException) { HttpResponse.error(400, "bad_request", e.message?.takeIf { SAFE.matches(it) } ?: "Bad request.")
        } catch (e: Throwable) { HttpResponse.error(500, "internal", "Something went wrong.") }
    }

    private fun id(v: String): String = if (ID.matches(v)) v else throw IllegalArgumentException("id")

    private suspend fun cancel(id: String): HttpResponse {
        val row = service.get(id) ?: throw NoSuchElementException()
        if (row.jobState != "RUNNING") return HttpResponse.error(409, "not_running", "The sweep is not running.")
        if (service.runningId() == id) service.cancelRunning()
        return HttpResponse.json(200, buildJsonObject { put("ok", true) })
    }

    private fun prim(e: JsonElement?): JsonPrimitive = (e as? JsonPrimitive) ?: throw IllegalArgumentException("param")

    private suspend fun start(req: HttpRequest): HttpResponse {
        val b = (runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) }.getOrNull() as? JsonObject) ?: throw IllegalArgumentException("body")
        val ids = (b["traceIds"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("traceIds") } ?: throw IllegalArgumentException("traceIds")
        require(ids.isNotEmpty() && ids.size <= ReplayService.MAX_TRACES && ids.all { ID.matches(it) }) { "traceIds" }
        val mode = when ((b["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content) { "grid" -> SweepMode.GRID; "random" -> SweepMode.RANDOM; else -> throw IllegalArgumentException("mode") }
        val params = (b["params"] as? JsonArray)?.map { p ->
            val o = p as? JsonObject ?: throw IllegalArgumentException("params")
            val path = (o["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("params")
            val values: List<JsonElement> = when {
                o["values"] is JsonArray -> (o["values"] as JsonArray).map { v -> if (v is JsonNull) v else prim(v).also { x -> require(x.isString || x.doubleOrNull?.isFinite() == true) { "param value" } } }
                o["min"] != null -> SweepPlanner.range(num(o["min"]), num(o["max"]), num(o["step"]))
                else -> throw IllegalArgumentException("params")
            }
            SweepParam(path, values)
        } ?: throw IllegalArgumentException("params")
        val samples = b["samples"]?.let { (it as? JsonPrimitive)?.longOrNull?.toInt() ?: throw IllegalArgumentException("samples") } ?: 0
        val seed = b["seed"]?.let { (it as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("seed") } ?: 0L
        val base = (b["base"] as? JsonObject)?.let { PlanResolver.resolve(it, PlanResolver.defaults()).getOrElse { throw IllegalArgumentException("base") } } ?: PlanResolver.defaults()
        val sweepId = service.start(SweepRequest(ids, SweepSpec(mode, params, samples, seed), base, paramsJson = JsonObject(b.filterKeys { it != "traceIds" }).toString()))
        return HttpResponse.json(202, buildJsonObject { put("sweepId", sweepId) })
    }

    private fun num(e: JsonElement?): Double = (e as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("range")

    private fun row(r: CanvasSweepEntity, withResult: Boolean, sort: String, desc: Boolean): JsonElement = buildJsonObject {
        put("sweepId", r.sweepId); put("jobState", r.jobState); put("invalidated", r.invalidated)
        put("createdAt", r.createdAt); put("finishedAt", r.finishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("configHash", r.configHash?.let { JsonPrimitive(it) } ?: JsonNull); put("labelRev", r.labelRev)
        put("sources", JsonArray((TraceSources.decode(r.sourcesJson) ?: emptyList()).map { s ->
            buildJsonObject { put("traceId", s.traceId); put("revision", s.revision); put("fromMs", s.fromMs); put("toMs", s.toMs) }
        }))
        if (withResult) put("result", r.resultJson?.let { sorted(it, sort, desc) } ?: JsonNull)
    }

    /** The stored result with its points ordered by [sort] (ties keep the sweep order). */
    private fun sorted(resultJson: String, sort: String, desc: Boolean): JsonElement {
        val o = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return JsonNull
        val key = SORT_KEYS.getValue(sort)
        val pts = (o["points"] as? JsonArray)?.toList() ?: return o
        fun v(e: JsonElement) = ((e as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val ordered = if (desc) pts.sortedByDescending { v(it) } else pts.sortedBy { v(it) }
        return JsonObject(o + ("points" to JsonArray(ordered)))
    }
}
