package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.plan.CompileResult
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.hub.plan.PlanPublishPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `/api/plans` routes. Auth, Host/Origin policy and body size are the router's.
 *
 * - `POST /api/plans/compile` `{json}` (the pipeline, an object or a string holding one) -> `200 {ok, planJson, configHash, problems, warnings, staticCost}`; nothing is sent or stored.
 * - `POST /api/plans/debug-shadow` `{json}` (M1): compiles and sends the plan as SHADOW through the hub's [PlanPublishPort]. Developer mode only; the label says it has no publish
 *   gates; `mode` may be absent or `SHADOW`, anything else (LIVE, TRIAL, SETTINGS) is refused before anything is compiled or sent. The port has no LIVE or TRIAL method at all.
 *
 * Plan reconciliation (the desired gen, the re-send on a new session, the acks) is the hub's: this class only hands a compiled plan to the port.
 */
class PlanRoutes(
    private val developerMode: () -> Boolean,
    private val sender: () -> PlanPublishPort?,
    private val compile: (Pipeline) -> CompileResult = { PlanCompiler.compile(it) },
) {
    companion object { const val DEBUG_LABEL = "debug SHADOW (no publish gates)" }

    suspend fun handle(req: HttpRequest): HttpResponse? {
        val seg = req.path.removePrefix("/api").split('/').filter { it.isNotEmpty() }
        if (seg.firstOrNull() != "plans") return null
        if (seg.size != 2 || !req.method.equals("POST", true)) return HttpResponse.error(404, "not_found", "Not found.")
        return try {
            when (seg[1]) {
                "compile" -> compileRoute(req)
                "debug-shadow" -> debugShadow(req)
                else -> HttpResponse.error(404, "not_found", "Not found.")
            }
        } catch (e: IllegalArgumentException) { HttpResponse.error(400, "bad_request", "Bad request.") }
    }

    private fun body(req: HttpRequest): JsonObject {
        val o = runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: throw IllegalArgumentException("body")
        if (PipelineJson.tooDeep(o)) throw IllegalArgumentException("deep")
        return o
    }

    private fun pipeline(b: JsonObject): Pipeline {
        val text = when (val e = b["json"]) {
            is JsonObject -> e.toString()
            is JsonPrimitive -> e.takeIf { it.isString }?.content
            else -> null
        } ?: throw IllegalArgumentException("json")
        return PipelineJson.decode(text).getOrNull() ?: throw IllegalArgumentException("json")
    }

    private fun compileRoute(req: HttpRequest): HttpResponse {
        val r = compile(pipeline(body(req)))
        return HttpResponse.json(200, buildJsonObject {
            put("ok", r.problems.isEmpty())
            put("planJson", r.planJson?.let { JsonPrimitive(it) } ?: JsonNull)
            put("configHash", r.plan?.configHash?.let { JsonPrimitive(it) } ?: JsonNull)
            put("problems", JsonArray(r.problems.map { JsonPrimitive(it) }))
            put("warnings", JsonArray(r.warnings.map { JsonPrimitive(it) }))
            put("staticCost", buildJsonObject { put("modelCalls", r.staticCost.modelCalls); put("rtOps", r.staticCost.rtOps); put("cpuClass", r.staticCost.cpuClass) })
        })
    }

    private suspend fun debugShadow(req: HttpRequest): HttpResponse {
        if (!developerMode()) return HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        val b = body(req)
        val mode = b["mode"]
        if (mode != null && mode !is JsonNull && (mode as? JsonPrimitive)?.takeIf { it.isString }?.content != "SHADOW") {
            return HttpResponse.error(400, "shadow_only", "Only SHADOW can be sent from here.")
        }
        val r = compile(pipeline(b))
        val plan = r.plan
        val planJson = r.planJson
        if (r.problems.isNotEmpty() || plan == null || planJson == null) return HttpResponse.error(422, "invalid_pipeline", "The pipeline has problems.", r.problems)
        val port = sender() ?: return HttpResponse.error(503, "unavailable", "The glasses link is not available.")
        val sent = port.sendShadow(planJson, plan.configHash)
        return HttpResponse.json(200, buildJsonObject {
            put("label", DEBUG_LABEL); put("mode", "SHADOW"); put("gen", sent.gen); put("sent", sent.sent); put("configHash", plan.configHash)
            put("warnings", JsonArray(r.warnings.map { JsonPrimitive(it) }))
        })
    }
}
