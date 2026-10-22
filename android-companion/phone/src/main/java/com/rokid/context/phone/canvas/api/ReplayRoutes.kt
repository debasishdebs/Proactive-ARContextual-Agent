package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.plan.ReplayBusy
import com.rokid.context.canvas.plan.ReplayCancel
import com.rokid.context.canvas.plan.ReplayDeveloperModeOff
import com.rokid.context.canvas.plan.ReplayRequest
import com.rokid.context.canvas.plan.ReplayService
import com.rokid.context.canvas.plan.ReplayUnknownTrace
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.memory.db.CanvasReplayEntity
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
import kotlinx.serialization.json.put

/**
 * `/api/replays` routes (S77). Written in Task 21 and NOT registered: Task 22 hands it to `CanvasApi`/`CanvasRouter` (auth, Host/Origin policy and body size are the
 * router's). [handle] returns null for a path that is not a replay route. Messages are fixed texts; no input is echoed.
 *
 * - `POST /api/replays` `{traceIds, candidate?: {configHash}, fusion?: {key: number}}` -> `202 {replayId}`
 * - `GET /api/replays` -> the newest jobs; `GET /api/replays/{id}` -> the job and, once DONE, its estimate (always labelled an estimate, with its warnings)
 * - `POST /api/replays/{id}/cancel`
 */
class ReplayRoutes(
    private val service: ReplayService,
    /** The candidate of the STORED plan with this hash, or null when none has it (S77: the label must be the hash of what is simulated; nothing in the body overrides it). */
    private val candidateFor: suspend (String) -> ReplayCandidate? = { null },
) {
    companion object {
        private val ID = Regex("^[A-Za-z0-9:_-]{1,64}$")
        private val HASH = Regex("^[0-9a-f]{1,64}$")
        const val MAX_LIST = 50
    }

    suspend fun handle(req: HttpRequest): HttpResponse? {
        val seg = req.path.removePrefix("/api").split('/').filter { it.isNotEmpty() }
        if (seg.firstOrNull() != "replays") return null
        val m = req.method.uppercase()
        return try {
            when {
                seg.size == 1 && m == "POST" -> start(req)
                seg.size == 1 && m == "GET" -> HttpResponse.json(200, JsonArray(service.list(MAX_LIST).map { row(it, withResult = false) }))
                seg.size == 2 && m == "GET" -> HttpResponse.json(200, row(service.get(id(seg[1])) ?: throw NoSuchElementException(), withResult = true))
                seg.size == 3 && seg[2] == "cancel" && m == "POST" -> cancel(id(seg[1]))
                else -> HttpResponse.error(404, "not_found", "Not found.")
            }
        } catch (e: ReplayBusy) { HttpResponse.error(409, "busy", "A replay is already running.")
        } catch (e: ReplayDeveloperModeOff) { HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        } catch (e: ReplayUnknownTrace) { HttpResponse.error(404, "not_found", "Trace not found.")
        } catch (e: NoSuchElementException) { HttpResponse.error(404, "not_found", "Not found.")
        } catch (e: IllegalArgumentException) { HttpResponse.error(400, "bad_request", "Bad request.")
        } catch (e: Throwable) { HttpResponse.error(500, "internal", "Something went wrong.") }
    }

    private fun id(v: String): String = if (ID.matches(v)) v else throw IllegalArgumentException("id")

    private suspend fun cancel(id: String): HttpResponse {
        val row = service.get(id) ?: throw NoSuchElementException()
        if (row.jobState != "RUNNING") return HttpResponse.error(409, "not_running", "The replay is not running.")
        if (service.runningId() == id) service.cancelRunning(ReplayCancel.USER)
        return HttpResponse.json(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun start(req: HttpRequest): HttpResponse {
        val b = (runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) }.getOrNull() as? JsonObject) ?: throw IllegalArgumentException("body")
        val ids = (b["traceIds"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("traceIds") }
            ?: throw IllegalArgumentException("traceIds")
        require(ids.isNotEmpty() && ids.size <= ReplayService.MAX_TRACES && ids.all { ID.matches(it) }) { "traceIds" }
        val cand = (b["candidate"] as? JsonObject)?.let { c ->
            val hash = (c["configHash"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { HASH.matches(it) } ?: throw IllegalArgumentException("configHash")
            candidateFor(hash) ?: throw NoSuchElementException("configHash")
        }
        val fusion = (b["fusion"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.doubleOrNull?.takeIf { d -> d.isFinite() } ?: throw IllegalArgumentException("fusion") }
        val replayId = service.start(ReplayRequest(ids, cand, fusion, paramsJson = JsonObject(b.filterKeys { it != "traceIds" }).toString()))
        return HttpResponse.json(202, buildJsonObject { put("replayId", replayId) })
    }

    private fun row(r: CanvasReplayEntity, withResult: Boolean): JsonElement = buildJsonObject {
        put("replayId", r.replayId); put("class", r.replayClass); put("jobState", r.jobState); put("invalidated", r.invalidated)
        put("createdAt", r.createdAt); put("finishedAt", r.finishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("configHash", r.configHash?.let { JsonPrimitive(it) } ?: JsonNull); put("labelRev", r.labelRev)
        put("sources", JsonArray((TraceSources.decode(r.sourcesJson) ?: emptyList()).map { s ->
            buildJsonObject { put("traceId", s.traceId); put("revision", s.revision); put("fromMs", s.fromMs); put("toMs", s.toMs) }
        }))
        if (withResult) put("result", r.resultJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
    }
}
