package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.plan.CompileResult
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.canvas.plan.CapturePlanPublisher
import com.rokid.context.canvas.plan.GateReport
import com.rokid.context.canvas.plan.M1Evidence
import com.rokid.context.canvas.plan.M1EvidenceStore
import com.rokid.context.canvas.plan.PlanOutcome
import com.rokid.context.core.model.PlanMode
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
 * Task 27 and 28 (the gated publish, `plans` and `m1` are null until the composition task binds them: the routes then answer 503):
 * - `POST /api/plans/check` `{pipelineId, version, mode}` -> `200 {ok, configHash, gates:[{n, ok, text}], reasons}`: the five gates, nothing sent or written.
 * - `POST /api/plans/publish` the same body -> `200 {publishId, groupId, mode, configHash, gen}`, or `409 publish_blocked` with the reasons. `mode` is SHADOW, TRIAL or LIVE; TRIAL and LIVE are
 *   refused until M1 is recorded, and LIVE is refused in core.
 * - `POST /api/plans/restore-previous` and `POST /api/plans/use-settings` (the S26 actions; Undo latest is `POST /api/publish/rollback`, which is group-aware).
 * - `GET /api/plans/status` -> `{active: {publishId, groupId, name, version, mode, configHash, state, settingsRow, frozenLabel}|null, rows:[...]}`: the Settings row and the Diagnostics card.
 * - `POST /api/plans/m1-evidence` `{note}` (Developer mode): records that the worn M1 run passed. Never inferred.
 *
 * Plan reconciliation (the desired gen, the re-send on a new session, the acks) is the hub's: this class only hands a compiled plan to the port.
 */
class PlanRoutes(
    private val developerMode: () -> Boolean,
    private val sender: () -> PlanPublishPort?,
    private val compile: (Pipeline) -> CompileResult = { PlanCompiler.compile(it) },
    private val plans: () -> CapturePlanPublisher? = { null },
    private val m1: () -> M1EvidenceStore? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object { const val DEBUG_LABEL = "debug SHADOW (no publish gates)" }

    suspend fun handle(req: HttpRequest): HttpResponse? {
        val seg = req.path.removePrefix("/api").split('/').filter { it.isNotEmpty() }
        if (seg.firstOrNull() != "plans") return null
        if (seg.size == 2 && seg[1] == "status" && req.method.equals("GET", true)) return status()
        if (seg.size != 2 || !req.method.equals("POST", true)) return HttpResponse.error(404, "not_found", "Not found.")
        return try {
            when (seg[1]) {
                "compile" -> compileRoute(req)
                "debug-shadow" -> debugShadow(req)
                "check" -> check(req)
                "publish" -> publish(req)
                "restore-previous" -> action(req) { it.restorePrevious() }
                "use-settings" -> action(req) { it.useSettings() }
                "m1-evidence" -> m1Evidence(req)
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

    private fun target(b: JsonObject): Triple<String, Int, PlanMode> {
        val id = (b["pipelineId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { Regex("^[A-Za-z0-9:_-]{1,64}$").matches(it) } ?: throw IllegalArgumentException("pipelineId")
        val v = (b["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()?.takeIf { it in 1..999_999 } ?: throw IllegalArgumentException("version")
        val m = (b["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val mode = PlanMode.entries.firstOrNull { it.name == m && it != PlanMode.SETTINGS } ?: throw IllegalArgumentException("mode")
        return Triple(id, v, mode)
    }

    private fun gatesJson(r: GateReport) = buildJsonObject {
        put("ok", r.ok); put("configHash", r.configHash?.let { JsonPrimitive(it) } ?: JsonNull)
        put("gates", JsonArray(r.gates.map { g -> buildJsonObject { put("n", g.n); put("ok", g.ok); put("text", if (g.ok) "" else g.text) } }))
        put("reasons", JsonArray(r.reasons.map { JsonPrimitive(it) }))
        put("evidence", r.evidence?.let { e ->
            buildJsonObject {
                put("replayId", e.replayId); put("minutes", e.minutes); put("statesCovered", e.statesCovered)
                put("candidatePctPerMin", e.candidatePctPerMin); put("baselinePctPerMin", e.baselinePctPerMin)
                put("windowsCompared", e.windowsCompared); put("inexactWindows", e.inexactWindows); put("fusionWarning", e.fusionWarning)
            }
        } ?: JsonNull)
    }

    private suspend fun check(req: HttpRequest): HttpResponse {
        val p = plans() ?: return unavailable()
        val (id, v, mode) = target(body(req))
        val r = p.check(id, v, mode) ?: return HttpResponse.error(404, "not_found", "Not found.")
        val body = gatesJson(r)
        // the M1 flag is shown as its own line (never inferred); the record itself is the Developer-mode route's
        return HttpResponse.json(200, JsonObject(body + ("m1Passed" to JsonPrimitive(m1()?.get() != null))))
    }

    private fun blocked(o: PlanOutcome.Blocked) = HttpResponse.error(409, "publish_blocked", "Blocked.", o.reasons)
    private fun unavailable() = HttpResponse.error(503, "unavailable", "Capture plans are not available.")

    private fun done(o: PlanOutcome.Done) = HttpResponse.json(200, buildJsonObject {
        put("publishId", o.publishId); put("groupId", o.groupId?.let { JsonPrimitive(it) } ?: JsonNull); put("mode", o.mode.name)
        put("configHash", o.configHash?.let { JsonPrimitive(it) } ?: JsonNull); put("gen", o.gen?.let { JsonPrimitive(it) } ?: JsonNull)
    })

    private suspend fun publish(req: HttpRequest): HttpResponse {
        val p = plans() ?: return unavailable()
        val (id, v, mode) = target(body(req))
        return when (val o = p.publish(id, v, mode)) { is PlanOutcome.Done -> done(o); is PlanOutcome.Blocked -> blocked(o) }
    }

    private suspend fun action(req: HttpRequest, run: suspend (CapturePlanPublisher) -> PlanOutcome): HttpResponse {
        body(req)
        val p = plans() ?: return unavailable()
        return when (val o = run(p)) { is PlanOutcome.Done -> done(o); is PlanOutcome.Blocked -> blocked(o) }
    }

    private suspend fun status(): HttpResponse {
        val p = plans() ?: return unavailable()
        val s = p.status()
        return HttpResponse.json(200, buildJsonObject {
            put("active", s.active?.let { a ->
                buildJsonObject {
                    put("publishId", a.publishId); put("groupId", a.groupId); put("name", a.name); put("version", a.version); put("mode", a.mode)
                    put("configHash", a.configHash); put("state", a.state); put("sentAtMs", a.sentAtMs?.let { JsonPrimitive(it) } ?: JsonNull); put("settingsRow", a.settingsRow); put("frozenLabel", a.frozenLabel)
                }
            } ?: JsonNull)
            put("rows", JsonArray(s.rows.map { JsonPrimitive(it) }))
        })
    }

    private suspend fun m1Evidence(req: HttpRequest): HttpResponse {
        if (!developerMode()) return HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        val b = body(req)
        val note = (b["note"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        if (note.length > M1Evidence.MAX_NOTE) throw IllegalArgumentException("note")
        val store = m1() ?: return unavailable()
        store.record(M1Evidence(clock(), note))
        return HttpResponse.json(200, buildJsonObject { put("recorded", true) })
    }
}
