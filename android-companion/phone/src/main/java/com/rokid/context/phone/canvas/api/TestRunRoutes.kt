package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.live.LiveTapBounds
import com.rokid.context.canvas.window.E2eJoin
import com.rokid.context.canvas.window.PickMode
import com.rokid.context.canvas.window.PickResult
import com.rokid.context.canvas.window.PickerFrame
import com.rokid.context.canvas.window.TestRunPolicy
import com.rokid.context.canvas.window.TestRunSource
import com.rokid.context.canvas.window.WindowFramePicker
import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the Test run routes need from the phone, as data. Every call is read-only except [start] (the existing dry-run start of the runner). Production: `CanvasBackendImpl`. */
interface TestRunPort {
    data class Facts(val pipelineHash: String, val hasOnlineVision: Boolean, val hasPyFn: Boolean, /** The endpoint label a photo would go to (a fixed label, never a URL); null when none. */ val endpointLabel: String?)

    /** Null = unknown pipeline (404). */
    suspend fun facts(pipelineId: String, version: Int?): Facts?
    /** The frame rows of the window as picker input. A failure to read must throw (the route answers 500), never return a partial list. */
    suspend fun frames(fromMs: Long, toMs: Long): List<PickerFrame>
    /** The `[startedAtMs, endedAtMs)` of a stored trace, or null (unknown or tombstoned). */
    suspend fun traceWindow(traceId: String): Pair<Long, Long>?
    /** The recorded KEEP times of a trace (counts-only join); null = the glasses lane cannot be read here. */
    suspend fun keeps(traceId: String): List<Long>?
    fun busy(): Boolean
    fun offlineOnly(): Boolean
    /** True when the zone state cannot be read or a pause zone is held now (fail closed). */
    suspend fun zoneHeldOrUnknown(): Boolean = true
    suspend fun estimate(pipelineId: String, version: Int?, frameIds: List<String>): Estimate
    /** What a run asks for beyond live: serve model nodes from [fromRunId]'s rows (REPLAYED), and/or send [seed] (SEEDED). [onMissLive] false = a miss is the item error `not_recorded`. */
    data class ReplayAsk(val fromRunId: String?, val onMissLive: Boolean, val seed: Int?)

    /** True while the source run of a replay still exists (pruned or Forgotten = false: the replay is refused, never run live). */
    suspend fun replaySourceAvailable(runId: String): Boolean = false
    /** The existing dry-run photo run, optionally replaying and/or seeded. Throws [CanvasRunner.Busy]. */
    suspend fun start(pipelineId: String, version: Int?, frameIds: List<String>, token: String, replay: ReplayAsk? = null): String
    /** `{items, identical, drifted:{node:n}, line}` of [replayRunId] against [sourceRunId] (read-only, from the two runs' own rows); null = either run is gone. */
    suspend fun determinism(replayRunId: String, sourceRunId: String): JsonObject? = null
    /** Starts a bounded live tap and returns its run id; null = the tap driver is not built on this phone (the route answers 501). */
    suspend fun startTap(pipelineId: String, version: Int?, maxMin: Int, maxFrames: Int, maxPhotosSent: Int, token: String): String? = null
    /** Ends a tap cleanly; false = no such tap. */
    suspend fun stopTap(runId: String): Boolean = false
}

/**
 * Canvas v2.1 Test run routes (design section 1.9), all behind Developer mode AND the default-OFF flag `canvasTestRun` (403 `developer_mode_off` otherwise), all dry-run, all through the router's bearer token and Host policy
 * (this is an [ApiExtra] of the canvas API). No send, no delivery, no capture-plan publish, no write to user data: a run goes only through the existing runner and its `confirmToken`.
 *
 *  - `POST /api/runs/estimate` and `POST /api/runs` with a `source` field (the run-start source switch): `{kind:"window", from, to, mode, n, seed?}` or `{kind:"trace", traceId, mode, n, seed?}`. A body without `source` is NOT ours (null).
 *  - `POST /api/e2e`: the window or trace source plus the counts-only KEEP join; with a `confirmToken` it also starts the phone run. It stores nothing.
 *  - `POST /api/runs/tap` (bounded live tap) and `POST /api/runs/:id/stop`.
 *
 * Refusals are the fixed tokens of [TestRunPolicy]. Logs: an event word and counts only (never an id, a text or a coordinate).
 */
class TestRunRoutes(
    private val devMode: () -> Boolean,
    private val flag: () -> Boolean,
    private val port: TestRunPort,
) : ApiExtra {
    override suspend fun handle(req: HttpRequest): HttpResponse? {
        val path = req.path
        val kind = when {
            path == "/api/runs/estimate" -> Kind.ESTIMATE
            path == "/api/runs" -> Kind.START
            path == "/api/e2e" -> Kind.E2E
            path == "/api/runs/tap" -> Kind.TAP
            STOP.matches(path) -> Kind.STOP
            DETERMINISM.matches(path) -> Kind.DETERMINISM
            else -> return null
        }
        if (kind == Kind.DETERMINISM) return determinism(req)
        if (!req.method.equals("POST", true)) return if (kind == Kind.E2E || kind == Kind.TAP || kind == Kind.STOP) HttpResponse.error(405, "bad_request", "Method not allowed.") else null
        val body: JsonObject? = if (req.bodyTooLarge || req.body.size > MAX_BYTES) null
        else runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
        // the run-start routes are ours only when the body carries a source
        if ((kind == Kind.ESTIMATE || kind == Kind.START) && body?.get("source") == null) return null
        if (!devMode() || !flag()) return HttpResponse.error(403, "developer_mode_off", if (devMode()) "Test run is switched off." else "Developer mode is off.")
        if (kind == Kind.STOP) return if (port.stopTap(STOP.matchEntire(path)!!.groupValues[1])) HttpResponse.json(200, OK) else HttpResponse.error(404, "not_found", "Not found.")
        if (req.bodyTooLarge || req.body.size > MAX_BYTES) return HttpResponse.error(400, "too_large", "The request is too large.")
        if (body == null) return bad()
        return try {
            when (kind) {
                Kind.ESTIMATE -> estimate(body)
                Kind.START -> start(body)
                Kind.E2E -> e2e(body)
                Kind.TAP -> tap(body)
                Kind.STOP, Kind.DETERMINISM -> bad()
            }
        } catch (e: BadRequest) { bad()
        } catch (e: NoSuchElementException) { HttpResponse.error(404, "not_found", "Not found.")
        } catch (e: CanvasRunner.Busy) { HttpResponse.error(409, "busy", "A run is already active.")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { PcLog.i("test_run", "failed (${e.javaClass.simpleName})"); HttpResponse.error(500, "internal", "Something went wrong.") }
    }

    private enum class Kind { ESTIMATE, START, E2E, TAP, STOP, DETERMINISM }
    private class BadRequest : Exception()
    private fun bad() = HttpResponse.error(400, "bad_request", "Bad request.")
    private fun refused(token: String) = HttpResponse.error(422, "refused", "This test run is not allowed.", listOf(token))

    private class Picked(val src: TestRunSource, val pick: PickResult, val facts: TestRunPort.Facts, val frames: List<PickerFrame>, val traceId: String?)

    private suspend fun admitWindow(b: JsonObject): Pair<Picked?, HttpResponse?> {
        val pid = b.idStr("pipelineId"); val ver = b.optVersion()
        val s = b["source"] as? JsonObject ?: throw BadRequest()
        val facts = port.facts(pid, ver) ?: throw NoSuchElementException()
        val mode = PickMode.parse(s.optStr("mode")) ?: throw BadRequest()
        val n = s.intOf("n")
        val seedIn = s.optStr("seed")?.also { if (it.length > 64) throw BadRequest() }
        val traceId: String?
        val from: Long; val to: Long
        when (s.optStr("kind")) {
            "window" -> { traceId = null; from = s.longOf("from"); to = s.longOf("to") }
            "trace" -> {
                traceId = s.optStr("traceId")?.also { if (!ID.matches(it)) throw BadRequest() } ?: throw BadRequest()
                val w = port.traceWindow(traceId) ?: throw NoSuchElementException()
                from = w.first; to = w.second
            }
            else -> throw BadRequest()
        }
        val src: TestRunSource = if (traceId == null) TestRunSource.Window(from, to, mode, n, seedIn) else TestRunSource.Trace(traceId, from, to, mode, n, seedIn)
        val ctx = TestRunPolicy.Ctx(flagOn = flag(), developerMode = devMode(), busy = port.busy(), offlineOnly = port.offlineOnly(), hasOnlineVision = facts.hasOnlineVision, hasPyFn = facts.hasPyFn, zoneHeldOrUnknown = port.zoneHeldOrUnknown())
        TestRunPolicy.refusal(src, ctx)?.let { return null to (if (it == "busy") HttpResponse.error(409, "busy", "A run is already active.") else refused(it)) }
        val frames = port.frames(from, to)
        val seed = seedIn ?: WindowFramePicker.defaultSeed(from, to, facts.pipelineHash)
        return Picked(src, WindowFramePicker.pick(frames, from, to, mode, n, seed), facts, frames, traceId) to null
    }

    /** `replay: {fromRunId, onMiss: "error"|"live"}` and/or `modelSeed: 1..2147483647`; null when the run asks for neither. A vanished source is refused here, before the token is spent. */
    private suspend fun replayAsk(b: JsonObject): Pair<TestRunPort.ReplayAsk?, HttpResponse?> {
        val r = b["replay"]?.takeIf { it !is JsonNull }?.let { it as? JsonObject ?: throw BadRequest() }
        val seed = b["modelSeed"]?.takeIf { it !is JsonNull }?.let { b.intOf("modelSeed").also { v -> if (v < 1) throw BadRequest() } }
        if (r == null && seed == null) return null to null
        val from = r?.idStr("fromRunId")
        val miss = r?.optStr("onMiss") ?: "error"
        if (miss != "error" && miss != "live") throw BadRequest()
        if (from != null && !port.replaySourceAvailable(from)) return null to refused("replay_source_gone")
        return TestRunPort.ReplayAsk(from, miss == "live", seed) to null
    }

    private suspend fun determinism(req: HttpRequest): HttpResponse {
        if (!req.method.equals("GET", true)) return HttpResponse.error(405, "bad_request", "Method not allowed.")
        if (!devMode() || !flag()) return HttpResponse.error(403, "developer_mode_off", if (devMode()) "Test run is switched off." else "Developer mode is off.")
        val id = DETERMINISM.matchEntire(req.path)!!.groupValues[1]
        val against = req.query["against"]?.takeIf { ID.matches(it) } ?: return bad()
        return try { HttpResponse.json(200, port.determinism(id, against) ?: return HttpResponse.error(404, "not_found", "Not found.")) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { PcLog.i("test_run", "determinism failed (${e.javaClass.simpleName})"); HttpResponse.error(500, "internal", "Something went wrong.") }
    }

    private fun pickJson(p: Picked): JsonObject = buildJsonObject {
        put("picked", p.pick.ids.size); put("of", p.pick.of); put("note", p.pick.note); put("seed", p.pick.seed)
        put("excluded", buildJsonObject { put("tombstoned", p.pick.excludedTombstoned); put("noPhoto", p.pick.excludedNoPhoto); put("retention", p.pick.excludedRetention); put("nearDup", p.pick.excludedNearDup) })
        // one plain line: a count and a fixed endpoint label, never a url
        put("photosLeave", buildJsonObject { put("count", if (p.facts.hasOnlineVision) p.pick.ids.size else 0); put("endpoint", p.facts.endpointLabel?.let { JsonPrimitive(it) } ?: JsonNull) })
    }

    private suspend fun estimate(b: JsonObject): HttpResponse {
        val (p, resp) = admitWindow(b); if (resp != null) return resp
        p!!
        if (p.pick.ids.isEmpty()) return refused("no_frames")
        val e = port.estimate(b.idStr("pipelineId"), b.optVersion(), p.pick.ids)
        PcLog.i("test_run", "estimate picked=${p.pick.ids.size}")
        return when (e) {
            is Estimate.Ok -> HttpResponse.json(200, JsonObject(estimateJson(e) + pickJson(p)))
            is Estimate.Refused -> HttpResponse.error(422, "refused", "This run is over the limits.", e.reasons)
        }
    }

    private suspend fun start(b: JsonObject): HttpResponse {
        val (p, resp) = admitWindow(b); if (resp != null) return resp
        p!!
        if (p.pick.ids.isEmpty()) return refused("no_frames")
        // the same deterministic pick as the estimate: the token binds exactly these ids, so a different pick is refused by the runner
        val (ask, askRefusal) = replayAsk(b); if (askRefusal != null) return askRefusal
        val runId = port.start(b.idStr("pipelineId"), b.optVersion(), p.pick.ids, b.tokenStr(), ask)
        PcLog.i("test_run", "start picked=${p.pick.ids.size}")
        return HttpResponse.json(200, JsonObject(mapOf("runId" to JsonPrimitive(runId)) + pickJson(p)))
    }

    /** The window or trace source plus the counts-only join of the trace's KEEPs with the stored frames. Nothing is stored; no frame id is returned. */
    private suspend fun e2e(b: JsonObject): HttpResponse {
        val (p, resp) = admitWindow(b); if (resp != null) return resp
        p!!
        val keeps = p.traceId?.let { port.keeps(it) }
        val out = HashMap<String, JsonElement>(pickJson(p))
        if (keeps == null) out["glassesLane"] = JsonPrimitive(if (p.traceId == null) "no_trace" else "not_available")
        else {
            val j = E2eJoin.join(keeps.map { E2eJoin.Keep(it) }, p.frames.filter { !it.tombstoned && it.photoPresent }.map { E2eJoin.Stored(it.frameId, it.capturedAtMs) })
            out["glassesLane"] = JsonPrimitive("counts")
            out["join"] = buildJsonObject { put("matched", j.matched); put("notStored", j.notStored); put("unusedFrames", j.unusedFrames); put("neverCaptured", j.neverCaptured); put("toleranceMs", E2eJoin.TOLERANCE_MS) }
        }
        val token = b["confirmToken"]?.let { b.tokenStr() }
        val (ask, askRefusal) = replayAsk(b); if (askRefusal != null) return askRefusal
        if (token != null && p.pick.ids.isNotEmpty()) out["runId"] = JsonPrimitive(port.start(b.idStr("pipelineId"), b.optVersion(), p.pick.ids, token, ask))
        PcLog.i("test_run", "e2e picked=${p.pick.ids.size}")
        return HttpResponse.json(200, JsonObject(out))
    }

    private suspend fun tap(b: JsonObject): HttpResponse {
        val pid = b.idStr("pipelineId"); val ver = b.optVersion()
        val maxMin = b["maxMin"]?.let { b.intOf("maxMin") } ?: LiveTapBounds.DEFAULT_MIN
        val maxFrames = b["maxFrames"]?.let { b.intOf("maxFrames") } ?: LiveTapBounds.MAX_FRAMES
        val maxPhotos = b["maxPhotosSent"]?.let { b.intOf("maxPhotosSent") } ?: 0
        val facts = port.facts(pid, ver) ?: throw NoSuchElementException()
        val src = TestRunSource.Tap(maxMin, maxFrames, maxPhotos)
        val ctx = TestRunPolicy.Ctx(flagOn = flag(), developerMode = devMode(), busy = port.busy(), offlineOnly = port.offlineOnly(), hasOnlineVision = facts.hasOnlineVision, hasPyFn = facts.hasPyFn, zoneHeldOrUnknown = port.zoneHeldOrUnknown())
        TestRunPolicy.refusal(src, ctx)?.let { return if (it == "busy") HttpResponse.error(409, "busy", "A run is already active.") else refused(it) }
        val runId = port.startTap(pid, ver, maxMin, maxFrames, maxPhotos, b.tokenStr())
            ?: return HttpResponse.error(501, "not_available", "The live tap is not available on this phone yet.")
        PcLog.i("test_run", "tap start")
        return HttpResponse.json(200, buildJsonObject { put("runId", runId); put("maxMin", maxMin); put("maxFrames", maxFrames); put("maxPhotosSent", maxPhotos) })
    }

    private fun estimateJson(e: Estimate.Ok): Map<String, JsonElement> = mapOf(
        "nominal" to JsonPrimitive(e.nominal), "max" to JsonPrimitive(e.max), "photos" to JsonPrimitive(e.photos), "kind" to JsonPrimitive(e.kind), "photoCount" to JsonPrimitive(e.photoCount),
        "models" to JsonArray(e.models.map { m -> buildJsonObject { put("nodeId", m.nodeId); put("endpoint", m.endpoint); put("model", m.model) } }),
        "confirmToken" to JsonPrimitive(e.confirmToken),
        "tokens" to (e.tokens?.let { t -> buildJsonObject { put("promptTokens", t.promptTokens); put("completionTokens", t.completionTokens); put("basedOn", t.basedOn) } } ?: JsonNull),
    )

    private fun JsonObject.optStr(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.idStr(k: String): String = optStr(k)?.takeIf { ID.matches(it) } ?: throw BadRequest()
    private fun JsonObject.tokenStr(): String = optStr("confirmToken")?.takeIf { it.length in 1..128 } ?: throw BadRequest()
    private fun JsonObject.optVersion(): Int? = this["version"]?.takeIf { it !is JsonNull }?.let { ((it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: throw BadRequest()).also { v -> if (v < 1) throw BadRequest() } }
    private fun JsonObject.intOf(k: String): Int = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw BadRequest()
    private fun JsonObject.longOf(k: String): Long = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw BadRequest()

    companion object {
        const val MAX_BYTES = 4_096
        private val ID = Regex("^[A-Za-z0-9:_-]{1,64}$")
        private val DETERMINISM = Regex("^/api/runs/([A-Za-z0-9:_-]{1,64})/determinism$")
        private val STOP = Regex("^/api/runs/([A-Za-z0-9:_-]{1,64})/stop$")
        private val OK = buildJsonObject { put("ok", true) }
    }
}
