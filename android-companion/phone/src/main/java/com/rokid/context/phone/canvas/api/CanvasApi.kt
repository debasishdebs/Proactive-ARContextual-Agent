package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.Estimate
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
 * The backend is called under a timeout ([callTimeoutMs]); a slow call answers 503 `busy`.
 */
class CanvasApi(private val backend: CanvasBackend, private val callTimeoutMs: Long = 30_000) : ApiHandler {
    private class ApiError(val status: Int, val code: String, val msg: String, val problems: List<String> = emptyList()) : Exception(code)

    companion object {
        private val ID = Regex("^[A-Za-z0-9:_-]{1,64}$")
        private val FROM = Regex("^[A-Za-z0-9:_-]{1,64}(@[0-9]{1,6})?$")
        private val VERSION = Regex("^[0-9]{1,6}$")
        const val DEFAULT_LIMIT = 60
        const val MAX_LIMIT = 120
        const val MAX_FRAME_IDS = 50
        const val MAX_NOTE = 280
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
            "POST pipelines/:id/freeze" -> {
                val note = optBody(req)?.optString("note", MAX_FREEZE_NOTE) ?: ""
                json(backend.freeze(id(s[1]), note))
            }
            "POST pipelines/:id/versions/:n/publish" -> {
                if (!VERSION.matches(s[3])) throw bad()
                outcome(backend.publish(id(s[1]), s[3].toInt()), "publish_blocked")
            }
            "GET publish" -> json(backend.activePublish())
            "POST publish/rollback" -> outcome(backend.rollback(), "rollback_blocked")
            "POST check-prompt" -> checkPrompt(req)
            "GET frames" -> frames(req)
            "GET frames/:id" -> json(backend.frame(id(s[1])) ?: throw NoSuchElementException())
            "GET frames/:id/thumb" -> FrameImagesHttp.response(backend.frameImage(id(s[1]), thumb = true))
            "GET frames/:id/image" -> FrameImagesHttp.response(backend.frameImage(id(s[1]), thumb = false))
            "POST runs/estimate" -> estimate(req)
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
        s.size == 1 && s[0] in setOf("session", "logout", "node-types", "pipelines", "publish", "check-prompt", "frames", "runs", "compare") -> s[0]
        s.size == 2 && s[0] == "pipelines" -> "pipelines/:id"
        s.size == 3 && s[0] == "pipelines" && s[2] == "freeze" -> "pipelines/:id/freeze"
        s.size == 5 && s[0] == "pipelines" && s[2] == "versions" && s[4] == "publish" -> "pipelines/:id/versions/:n/publish"
        s.size == 2 && s[0] == "publish" && s[1] == "rollback" -> "publish/rollback"
        s.size == 2 && s[0] == "frames" -> "frames/:id"
        s.size == 3 && s[0] == "frames" && s[2] in setOf("thumb", "image") -> "frames/:id/${s[2]}"
        s.size == 2 && s[0] == "runs" && s[1] == "estimate" -> "runs/estimate"
        s.size == 2 && s[0] == "runs" -> "runs/:id"
        s.size == 3 && s[0] == "runs" && s[2] in setOf("cancel", "scores") -> "runs/:id/${s[2]}"
        else -> null
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
        return arr.take(MAX_FRAME_IDS).map { e ->
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

    private suspend fun saveDraft(id: String, req: HttpRequest): HttpResponse {
        val b = body(req)
        val j = when (val e = b["json"]) {
            is JsonObject -> e.toString()
            is JsonPrimitive -> e.takeIf { it.isString }?.content
            else -> null
        } ?: throw bad("Missing \"json\".")
        val rev = b.int("draftRev").also { if (it < 0) throw bad() }
        return when (val r = backend.saveDraft(id, j, rev)) {
            is CanvasRepository.SaveResult.Saved -> json(buildJsonObject {
                put("draftRev", r.rev)
                put("problems", JsonArray(r.problems.map { JsonPrimitive(it) }))
            })
            CanvasRepository.SaveResult.Conflict -> throw ApiError(409, "conflict", "The draft changed. Reload it.")
            CanvasRepository.SaveResult.ReadOnly -> throw ApiError(403, "forbidden", "Built-in pipelines are read-only.")
            is CanvasRepository.SaveResult.Unreadable -> throw ApiError(400, "bad_request", "The pipeline JSON is not readable.", listOf(r.reason.take(200)))
        }
    }

    private fun outcome(o: CanvasPublisher.Outcome, blockedCode: String): HttpResponse = when (o) {
        is CanvasPublisher.Outcome.Published -> json(buildJsonObject {
            put("publishId", o.publishId)
            put("keys", JsonArray(o.keys.map { JsonPrimitive(it.name) }))
        })
        is CanvasPublisher.Outcome.Blocked -> throw ApiError(409, blockedCode, "Blocked.", o.reasons)
    }

    private suspend fun checkPrompt(req: HttpRequest): HttpResponse {
        val b = body(req)
        val problems = backend.checkPrompt(b.str("nodeType", 64), b.str("template", MAX_TEXT), b.optString("schema", MAX_TEXT))
        return json(buildJsonObject { put("problems", JsonArray(problems.map { JsonPrimitive(it) })) })
    }

    private suspend fun frames(req: HttpRequest): HttpResponse {
        val before = req.query["before"]?.let { it.toLongOrNull() ?: throw bad() } ?: Long.MAX_VALUE
        val limit = (req.query["limit"]?.let { it.toIntOrNull() ?: throw bad() } ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return json(backend.frames(before, limit, req.query["dwell"] == "true"))
    }

    private suspend fun estimate(req: HttpRequest): HttpResponse {
        val b = body(req)
        return when (val e = backend.estimate(b.idField("pipelineId"), b.version(), b.frameIds())) {
            is Estimate.Ok -> json(buildJsonObject {
                put("nominal", e.nominal)
                put("max", e.max)
                put("photos", e.photos)
                put("models", JsonArray(e.models.map { m -> buildJsonObject { put("nodeId", m.nodeId); put("endpoint", m.endpoint); put("model", m.model) } }))
                put("confirmToken", e.confirmToken)
            })
            is Estimate.Refused -> throw ApiError(422, "refused", "This run is over the limits.", e.reasons)
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
