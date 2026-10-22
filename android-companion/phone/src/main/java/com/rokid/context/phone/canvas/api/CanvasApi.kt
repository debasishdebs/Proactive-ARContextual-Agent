package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.canvas.AskDebug
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.LiveMode
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The canvas JSON API (spec section 8). Auth, Host/Origin policy, body size and logging are the router's; this class only maps
 * routes to [CanvasBackend] calls and results to statuses. Messages are fixed texts: no input and no exception text is echoed.
 * A read is called under a timeout ([callTimeoutMs]) and a slow one answers 503 `busy`. A mutation runs to completion once started (a cancel
 * between two writes could leave half a publish); only the Ask replay, which writes nothing, has a deadline ([MUTATION_DEADLINE_MS]).
 */
class CanvasApi(
    private val backend: CanvasBackend,
    private val callTimeoutMs: Long = 30_000,
    /** The phone's zone: the day filter of `GET /api/frames?day=` is that day's local midnight to the next. */
    private val zone: () -> java.time.ZoneId = java.time.ZoneId::systemDefault,
    private val mutationDeadlineMs: Long = MUTATION_DEADLINE_MS,
) : ApiHandler {
    private class ApiError(val status: Int, val code: String, val msg: String, val problems: List<String> = emptyList()) : Exception(code)

    companion object {
        private val ID = Regex("^[A-Za-z0-9:_-]{1,64}$")
        private val FROM = Regex("^[A-Za-z0-9:_-]{1,64}(@[0-9]{1,6})?$")
        private val VERSION = Regex("^[0-9]{1,6}$")
        private val DAY = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        const val DEFAULT_LIMIT = 60
        const val MAX_LIMIT = 120
        const val MAX_FRAME_IDS = 50
        const val MAX_HISTORY = 50
        const val DEFAULT_LIVE = 50
        const val MAX_LIVE = 100
        const val MAX_NOTE = 280
        /** The deadline of the one mutation that calls a model and writes nothing: the Ask replay. */
        const val MUTATION_DEADLINE_MS = 60_000L
        const val MAX_FREEZE_NOTE = 80
        const val MAX_NAME = 80
        const val MAX_TEXT = 64 * 1024
        private val OK = buildJsonObject { put("ok", true) }
    }

    override fun handle(req: HttpRequest): HttpResponse = try {
        // Reads are bounded by the deadline. A mutation (publish, rollback, freeze, save, start, cancel, score, delete) runs to
        // completion once started: cancelling it between two writes could leave live prompts changed with no publish record.
        if (req.method.equals("GET", true)) runBlocking { withTimeout(callTimeoutMs) { dispatch(req) } }
        else runBlocking { withContext(NonCancellable) { dispatch(req) } }
    } catch (e: TimeoutCancellationException) {
        HttpResponse.error(503, "busy", "The phone is busy. Try again.")
    } catch (e: ApiError) {
        HttpResponse.error(e.status, e.code, e.msg, e.problems)
    } catch (e: CanvasRunner.Busy) {
        HttpResponse.error(409, "busy", "A run is already active.")
    } catch (e: NoSuchElementException) {
        HttpResponse.error(404, "not_found", "Not found.")
    } catch (e: IllegalArgumentException) {
        if (e.message == "invalid_pipeline") HttpResponse.error(422, "invalid_pipeline", "The pipeline has problems.")
        else HttpResponse.error(400, "bad_request", "Bad request.")
    } catch (e: Throwable) {
        // Includes StackOverflowError and OOM: a canvas request must never take the process (it hosts the glasses link) down.
        HttpResponse.error(500, "internal", "Something went wrong.")
    }

    private fun bad(msg: String = "Bad request.") = ApiError(400, "bad_request", msg)
    private fun json(body: JsonElement) = HttpResponse.json(200, body)

    private suspend fun dispatch(req: HttpRequest): HttpResponse {
        val s = req.path.removePrefix("/api").split('/').filter { it.isNotEmpty() }
        val m = req.method.uppercase()
        val key = templateOf(s) ?: throw ApiError(404, "not_found", "Not found.")
        return when ("$m $key") {
            "GET session" -> json(backend.session(token(req)))
            "POST logout" -> { backend.logout(token(req)); json(OK) }
            "GET node-types" -> json(backend.nodeTypes())
            "GET pipelines" -> json(backend.pipelines())
            "POST pipelines" -> duplicate(req)
            "GET pipelines/:id" -> json(backend.pipeline(id(s[1])) ?: throw NoSuchElementException())
            "PUT pipelines/:id" -> saveDraft(id(s[1]), req)
            "DELETE pipelines/:id" -> {
                if (backend.deletePipeline(id(s[1]))) json(OK)
                else throw ApiError(409, "conflict", "Built-in and published pipelines cannot be deleted.")
            }
            "POST pipelines/:id/freeze" -> freeze(id(s[1]), req)
            "GET pipelines/:id/diff" -> diff(id(s[1]), req)
            "POST validate" -> validate(req)
            "POST pipelines/:id/versions/:n/publish" -> publish(id(s[1]), s[3], req)
            "GET publish" -> json(backend.activePublish())
            "GET publish/history" -> json(backend.publishHistory(limit(req, MAX_HISTORY, MAX_HISTORY)))
            "GET live" -> json(backend.live(limit(req, DEFAULT_LIVE, MAX_LIVE)))
            "POST publish/rollback" -> rollback(req)
            "POST check-prompt" -> checkPrompt(req)
            "GET frames" -> frames(req)
            "GET frames/:id" -> json(backend.frame(id(s[1])) ?: throw NoSuchElementException())
            "GET frames/:id/thumb" -> { sameOriginOnly(req); FrameImagesHttp.response(backend.frameImage(id(s[1]), thumb = true)) }
            "GET frames/:id/image" -> { sameOriginOnly(req); FrameImagesHttp.response(backend.frameImage(id(s[1]), thumb = false)) }
            "POST runs/estimate" -> estimate(req)
            "POST runs/estimate-ab" -> estimateAb(req)
            "POST runs/ab" -> startAb(req)
            "GET asks" -> json(backend.asks())
            "GET asks/:id" -> askView(id(s[1]))
            "POST asks/:id/replay" -> withTimeout(mutationDeadlineMs) { replay(id(s[1]), req) }
            "POST runs" -> startRun(req)
            "GET runs" -> json(backend.runs())
            "GET runs/:id" -> json(backend.run(id(s[1])) ?: throw NoSuchElementException())
            "POST runs/:id/cancel" -> if (backend.cancelRun(id(s[1]))) json(OK) else throw NoSuchElementException()
            "DELETE runs/:id" -> if (backend.deleteRun(id(s[1]))) json(OK) else throw NoSuchElementException()
            "PUT runs/:id/scores" -> score(id(s[1]), req)
            "GET compare" -> compare(req)
            else -> throw ApiError(405, "bad_request", "Method not allowed.")
        }
    }

    /** The route shape with ids as `:id`, or null for a path that is no route at all (404). */
    private fun templateOf(s: List<String>): String? = when {
        s.size == 1 && s[0] in setOf("session", "logout", "node-types", "pipelines", "publish", "check-prompt", "frames", "runs", "compare", "validate", "live") -> s[0]
        s.size == 2 && s[0] == "pipelines" -> "pipelines/:id"
        s.size == 3 && s[0] == "pipelines" && s[2] in setOf("freeze", "diff") -> "pipelines/:id/${s[2]}"
        s.size == 5 && s[0] == "pipelines" && s[2] == "versions" && s[4] == "publish" -> "pipelines/:id/versions/:n/publish"
        s.size == 2 && s[0] == "publish" && s[1] in setOf("rollback", "history") -> "publish/${s[1]}"
        s.size == 2 && s[0] == "frames" -> "frames/:id"
        s.size == 3 && s[0] == "frames" && s[2] in setOf("thumb", "image") -> "frames/:id/${s[2]}"
        s.size == 2 && s[0] == "runs" && s[1] in setOf("estimate", "estimate-ab", "ab") -> "runs/${s[1]}"
        s.size == 1 && s[0] == "asks" -> "asks"
        s.size == 2 && s[0] == "asks" -> "asks/:id"
        s.size == 3 && s[0] == "asks" && s[2] == "replay" -> "asks/:id/replay"
        s.size == 2 && s[0] == "runs" -> "runs/:id"
        s.size == 3 && s[0] == "runs" && s[2] in setOf("cancel", "scores") -> "runs/:id/${s[2]}"
        else -> null
    }

    /**
     * Photos are for the canvas page itself. The router already refuses a foreign Host and needs the bearer token (a header no other site can add),
     * and no response carries CORS headers; on top of that a request that names another origin, or that the browser marks cross-site or same-site, is refused.
     */
    private fun sameOriginOnly(req: HttpRequest) {
        val host = req.headers["host"].orEmpty()
        val origin = req.headers["origin"]
        if (origin != null && origin != "http://$host" && origin != "https://$host") throw ApiError(403, "forbidden", "Not allowed.")
        val site = req.headers["sec-fetch-site"]
        if (site != null && site != "same-origin" && site != "none") throw ApiError(403, "forbidden", "Not allowed.")
    }

    private fun id(v: String): String = if (ID.matches(v)) v else throw bad("Bad id.")

    private fun token(req: HttpRequest): String {
        val h = req.headers["authorization"] ?: throw bad()
        return h.removePrefix("Bearer ").removePrefix("bearer ").trim()
    }

    private fun body(req: HttpRequest): JsonObject =
        (try { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject } catch (e: Throwable) { null } ?: throw bad("Expected a JSON object."))
            .also { if (PipelineJson.tooDeep(it)) throw bad("JSON is nested too deeply.") }

    /** An empty body is allowed (null); anything else must be a JSON object. */
    private fun optBody(req: HttpRequest): JsonObject? = if (req.body.isEmpty()) null else body(req)

    private fun JsonObject.str(key: String, max: Int): String {
        val p = this[key] as? JsonPrimitive
        val v = p?.takeIf { it.isString }?.content ?: throw bad("Missing or bad \"$key\".")
        if (v.length > max) throw bad("\"$key\" is too long.")
        return v
    }

    private fun JsonObject.optString(key: String, max: Int): String? {
        val e = this[key]
        if (e == null || e is JsonNull) return null
        return str(key, max)
    }

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw bad("Missing or bad \"$key\".")

    private fun JsonObject.idField(key: String): String = id(str(key, 64))

    private fun JsonObject.frameIds(): List<String> {
        val arr = this["frameIds"] as? JsonArray ?: throw bad("Missing \"frameIds\".")
        if (arr.size > MAX_FRAME_IDS) throw bad("At most $MAX_FRAME_IDS photos")
        return arr.map { e ->
            val v = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw bad("Bad frame id.")
            id(v)
        }
    }

    private fun JsonObject.version(): Int? {
        val e = this["version"]
        if (e == null || e is JsonNull) return null
        return int("version").also { if (it < 1) throw bad() }
    }

    private suspend fun duplicate(req: HttpRequest): HttpResponse {
        val b = body(req)
        val from = b.str("from", 80).also { if (!FROM.matches(it)) throw bad("Bad \"from\".") }
        val name = b.str("name", MAX_NAME).trim().also { if (it.isEmpty()) throw bad("Name is empty.") }
        return json(buildJsonObject { put("id", backend.duplicate(from, name)) })
    }

    /** The pipeline JSON of a request: an object, or a string holding one. */
    private fun JsonObject.pipelineJson(): String = when (val e = this["json"]) {
        is JsonObject -> e.toString()
        is JsonPrimitive -> e.takeIf { it.isString }?.content
        else -> null
    } ?: throw bad("Missing \"json\".")

    private suspend fun freeze(id: String, req: HttpRequest): HttpResponse {
        val b = optBody(req)
        val note = b?.optString("note", MAX_FREEZE_NOTE) ?: ""
        val rev = if (b == null || b["draftRev"].let { it == null || it is JsonNull }) null else b.int("draftRev").also { if (it < 0) throw bad() }
        return try {
            json(backend.freeze(id, note, rev))
        } catch (e: CanvasRepository.DraftChanged) {
            throw ApiError(409, "conflict", "The draft changed. Save again.")
        }
    }

    /** Validates a draft without saving it. The request's `rev` (S38) comes back unchanged so the editor can drop stale replies. */
    private suspend fun validate(req: HttpRequest): HttpResponse {
        val b = body(req)
        val rev = if (b["rev"].let { it == null || it is JsonNull }) null else b.int("rev").also { if (it < 0) throw bad() }
        val r = backend.validate(b.pipelineJson())
        return json(if (rev == null) r else JsonObject(r + ("rev" to JsonPrimitive(rev))))
    }

    private suspend fun diff(id: String, req: HttpRequest): HttpResponse {
        fun side(k: String): String = (req.query[k] ?: throw bad()).also { if (it != "draft" && !VERSION.matches(it)) throw bad() }
        return json(backend.diff(id, side("a"), side("b")) ?: throw NoSuchElementException())
    }

    private suspend fun saveDraft(id: String, req: HttpRequest): HttpResponse {
        val b = body(req)
        val j = b.pipelineJson()
        val rev = b.int("draftRev").also { if (it < 0) throw bad() }
        return when (val r = backend.saveDraft(id, j, rev)) {
            is CanvasRepository.SaveResult.Saved -> json(buildJsonObject {
                put("draftRev", r.rev)
                put("problems", JsonArray(r.problems.map { JsonPrimitive(it) }))
            })
            CanvasRepository.SaveResult.Conflict -> throw ApiError(409, "conflict", "The draft changed. Reload it.")
            CanvasRepository.SaveResult.ReadOnly -> throw ApiError(403, "forbidden", "Built-in pipelines are read-only.")
            is CanvasRepository.SaveResult.Unreadable -> throw ApiError(400, "bad_request", "Pipeline JSON is not valid.")
        }
    }

    /** `limit` clamped to 1..[max]; absent means [default]. */
    private fun limit(req: HttpRequest, default: Int, max: Int): Int =
        (req.query["limit"]?.let { it.toIntOrNull() ?: throw bad() } ?: default).coerceIn(1, max)

    /** A publish in the mode of the optional body: absent, null or `prompts` is the MVP prompt publish; `shadow` and `live` publish the interpreter. */
    private suspend fun publish(id: String, version: String, req: HttpRequest): HttpResponse {
        if (!VERSION.matches(version)) throw bad()
        val m = optBody(req)?.get("mode")
        val mode = if (m == null || m is JsonNull) "prompts" else (m as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw bad("Bad \"mode\".")
        return when (mode) {
            "prompts" -> outcome(backend.publish(id, version.toInt()), "publish_blocked")
            "shadow" -> outcome(backend.publishInterpreter(id, version.toInt(), LiveMode.SHADOW), "publish_blocked", LiveMode.SHADOW)
            "live" -> outcome(backend.publishInterpreter(id, version.toInt(), LiveMode.LIVE), "publish_blocked", LiveMode.LIVE)
            else -> throw bad("Bad \"mode\".")
        }
    }

    private fun outcome(o: CanvasPublisher.Outcome, blockedCode: String, mode: LiveMode? = null): HttpResponse = when (o) {
        is CanvasPublisher.Outcome.Published -> json(buildJsonObject {
            put("publishId", o.publishId)
            put("keys", JsonArray(o.keys.map { JsonPrimitive(it.name) }))
            put("kind", if (mode == null) CanvasPublisher.KIND_PROMPTS else CanvasPublisher.KIND_INTERPRETER)
            mode?.let { put("mode", it.name) }
        })
        is CanvasPublisher.Outcome.Blocked -> throw ApiError(409, blockedCode, "Blocked.", o.reasons)
    }

    /** The optional `expectedId` is the publish the user saw as the newest: an auto-rollback that won the race refuses the click instead of undoing the wrong publish. */
    private suspend fun rollback(req: HttpRequest): HttpResponse {
        val b = optBody(req)
        val expected = if (b == null || b["expectedId"].let { it == null || it is JsonNull }) null
            else ((b["expectedId"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: throw bad("Bad \"expectedId\".")).also { if (it < 0) throw bad() }
        return outcome(backend.rollback(expected), "rollback_blocked")
    }

    private suspend fun checkPrompt(req: HttpRequest): HttpResponse {
        val b = body(req)
        val problems = backend.checkPrompt(b.str("nodeType", 64), b.str("template", MAX_TEXT), b.optString("schema", MAX_TEXT))
        return json(buildJsonObject { put("problems", JsonArray(problems.map { JsonPrimitive(it) })) })
    }

    private suspend fun frames(req: HttpRequest): HttpResponse {
        val before = req.query["before"]?.let { it.toLongOrNull() ?: throw bad() } ?: Long.MAX_VALUE
        val limit = (req.query["limit"]?.let { it.toIntOrNull() ?: throw bad() } ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val day = req.query["day"]
        if (day == null) return json(backend.frames(null, before, limit, req.query["dwell"] == "true"))
        // the day filter, in the phone's zone: local midnight up to the next midnight, or to `before` when that is earlier
        if (!DAY.matches(day)) throw bad("Bad day.")
        val date = try { java.time.LocalDate.parse(day) } catch (e: java.time.DateTimeException) { throw bad("Bad day.") }
        val z = zone()
        val after = date.atStartOfDay(z).toInstant().toEpochMilli()
        val nextMidnight = date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        return json(backend.frames(after, minOf(before, nextMidnight), limit, req.query["dwell"] == "true"))
    }

    private fun estimateJson(e: Estimate): HttpResponse = when (e) {
        is Estimate.Ok -> json(buildJsonObject {
            put("nominal", e.nominal)
            put("max", e.max)
            put("photos", e.photos)
            put("models", JsonArray(e.models.map { m -> buildJsonObject { put("nodeId", m.nodeId); put("endpoint", m.endpoint); put("model", m.model) } }))
            put("confirmToken", e.confirmToken)
            put("tokens", e.tokens?.let { t -> buildJsonObject { put("promptTokens", t.promptTokens); put("completionTokens", t.completionTokens); put("basedOn", t.basedOn) } } ?: JsonNull)
        })
        is Estimate.Refused -> throw ApiError(422, "refused", "This run is over the limits.", e.reasons)
    }

    private suspend fun estimate(req: HttpRequest): HttpResponse {
        val b = body(req)
        return estimateJson(backend.estimate(b.idField("pipelineId"), b.version(), b.frameIds()))
    }

    private fun JsonObject.pipelineRef(key: String): PipelineRef {
        val o = this[key] as? JsonObject ?: throw bad("Missing \"$key\".")
        return PipelineRef(o.idField("pipelineId"), o.version())
    }

    private suspend fun estimateAb(req: HttpRequest): HttpResponse {
        val b = body(req)
        return estimateJson(backend.estimateAb(b.pipelineRef("a"), b.pipelineRef("b"), b.frameIds()))
    }

    private suspend fun startAb(req: HttpRequest): HttpResponse {
        val b = body(req)
        val started = backend.startAb(b.pipelineRef("a"), b.pipelineRef("b"), b.frameIds(), b.str("confirmToken", 128))
        return json(buildJsonObject { put("group", started.group); put("runA", started.runA); put("runB", started.runB) })
    }

    // ---- Ask debug: authenticated like every /api route (the router checks the token first); bodies and prompts are never logged ----

    private suspend fun askView(id: String): HttpResponse = try {
        json(backend.ask(id) ?: throw NoSuchElementException())
    } catch (e: IllegalStateException) {
        if (e is CanvasRunner.Busy || e.message != "redacted") throw e
        throw ApiError(409, "refused", "This ask was redacted")
    }

    private suspend fun replay(id: String, req: HttpRequest): HttpResponse {
        val template = body(req).str("template", MAX_TEXT)
        return when (val r = backend.replayAsk(id, template)) {
            is AskDebug.Replay.Ok -> json(buildJsonObject {
                put("answer", r.answer)
                put("sources", JsonArray(r.sources.map { JsonPrimitive(it) }))
                put("latencyMs", r.latencyMs); put("promptTokens", r.promptTokens); put("completionTokens", r.completionTokens); put("model", r.model)
            })
            is AskDebug.Replay.Failed -> when (r.error) {
                "redacted" -> throw ApiError(409, "refused", "This ask was redacted")
                "not_found" -> throw NoSuchElementException()
                "incompatible" -> throw ApiError(422, "refused", "That template doesn't fit the Answer prompt. Check it first.")
                "forgotten" -> throw ApiError(409, "refused", "A memory used by this ask was forgotten while it ran. No answer is shown.")
                "AUTH" -> throw ApiError(502, "model_failed", "The model rejected the key.")
                "REJECTED" -> throw ApiError(502, "model_failed", "The model refused the request.")
                "INVALID_OUTPUT" -> throw ApiError(502, "model_failed", "The model's answer didn't fit the Answer format.")
                "TRANSIENT" -> throw ApiError(502, "model_failed", "The model call failed. Try again.")
                "NOT_SET" -> throw ApiError(502, "model_failed", "No model is set for Answer.")
                else -> throw ApiError(502, "model_failed", "The replay failed.") // an exception class name or an unknown word: never echoed
            }
        }
    }

    private suspend fun startRun(req: HttpRequest): HttpResponse {
        val b = body(req)
        val runId = backend.startRun(b.idField("pipelineId"), b.version(), b.frameIds(), b.str("confirmToken", 128))
        return json(buildJsonObject { put("runId", runId) })
    }

    private suspend fun score(runId: String, req: HttpRequest): HttpResponse {
        val b = body(req)
        val s = b.int("score").also { if (it !in -1..1) throw bad("Score must be -1, 0 or 1.") }
        backend.score(runId, b.idField("frameId"), b.idField("nodeId"), s, b.optString("note", MAX_NOTE) ?: "")
        return json(OK)
    }

    private suspend fun compare(req: HttpRequest): HttpResponse {
        val a = id(req.query["a"] ?: throw bad())
        val b = id(req.query["b"] ?: throw bad())
        return json(backend.compare(a, b) ?: throw NoSuchElementException())
    }
}
