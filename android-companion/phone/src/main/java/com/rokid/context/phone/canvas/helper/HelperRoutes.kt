package com.rokid.context.phone.canvas.helper

import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.canvas.helper.HelperJobService
import com.rokid.context.canvas.helper.HelperJobStore
import com.rokid.context.canvas.helper.HelperProtocol
import com.rokid.context.canvas.helper.ResultCommit
import com.rokid.context.memory.canvas.HelperJobState
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.HelperAuth
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The authenticated routes under `/api/helper/` (core plan section 6; `/api/helper/pair` is the router's). The router has already checked the HELPER bearer token and the Host and
 * Origin policy; this class maps one request to one [HelperJobService] call and its answer to the fixed denial table. Error bodies are fixed texts: no id, hash, code or
 * exception text is echoed. It holds no confirmation, grant-writer or pairing dependency: a request cannot confirm, decline, toggle a grant or open pairing (the card does
 * that natively), and `lease` and `available` never show an unconfirmed job, so payload, heartbeat and result for one answer 409 `not_confirmed` from the DAO.
 *
 * Bodies are parsed with exact key sets; a repeated key in one object keeps its LAST value (kotlinx.serialization does not reject duplicates): the helper is the trusted principal of threat model A.
 *
 * `POST /poll` body `{helperProtocol, active:[{jobId,leaseGen}], cleanupAcks:["<cleanupId>"], ready:{py_exec}}`; the answer is `{sessionGen, caps, serverTimeMs, available:[type],
 * cancel:[jobId], cleanup:[{cleanupId:"<n>", jobId}]}`. `cleanupId` is the row's number written as a decimal STRING (the helper treats it as opaque text and echoes it back).
 */
class HelperRoutes(
    private val auth: HelperAuth,
    private val service: HelperJobService,
    private val store: HelperJobStore,
    private val grantOn: (String) -> Boolean,
    private val clock: () -> Long,
    private val log: CanvasLogSink = CanvasLogSink { _, _, _ -> },
    private val callTimeoutMs: Long = 20_000,
) : ApiHandler {
    companion object {
        /** The HTTP status of each protocol denial code (the table of `constants.json`). */
        fun statusOf(code: String): Int = when (code) {
            "unauthorized" -> 401
            "capability_revoked" -> 403
            "not_found" -> 404
            "lease_expired", "voided", "cancelled", "stale_generation", "deadline_exceeded", "protocol_mismatch", "not_confirmed" -> 409
            "too_large" -> 413
            "invalid_result" -> 422
            "refused" -> 429
            else -> 400
        }

        private const val CLEANUP_BATCH = 50
        private val CLEANUP_ID = Regex("^[0-9]{1,18}$")
        private val MESSAGES = mapOf(
            "unauthorized" to "Authentication required.", "capability_revoked" to "This capability is off.", "not_found" to "Not found.",
            "lease_expired" to "The lease ended.", "voided" to "The job was voided.", "cancelled" to "The job was cancelled.", "stale_generation" to "Stale lease generation.",
            "deadline_exceeded" to "The job deadline passed.", "protocol_mismatch" to "Unsupported helper protocol.", "not_confirmed" to "The job is not confirmed.",
            "too_large" to "Request body too large.", "invalid_result" to "The result is not valid.", "refused" to "Refused.", "bad_request" to "Bad request.",
        )
    }

    private class Denied(val code: String) : Exception(code)

    private fun denial(code: String) = HttpResponse.error(statusOf(code), code, MESSAGES[code] ?: "Bad request.")

    override fun handle(req: HttpRequest): HttpResponse = try {
        runBlocking { kotlinx.coroutines.withTimeout(callTimeoutMs) { dispatch(req) } }
    } catch (e: Denied) {
        denial(e.code)
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        HttpResponse.error(503, "busy", "The phone is busy. Try again.")
    } catch (e: Throwable) {
        HttpResponse.error(500, "internal", "Something went wrong.")
    }

    private suspend fun dispatch(req: HttpRequest): HttpResponse {
        val segs = req.path.split('/').filter { it.isNotEmpty() }.drop(2) // after "api" and "helper"
        val m = req.method.uppercase()
        val bearer = req.headers["authorization"]?.takeIf { it.length > 7 && it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) }?.substring(7)?.trim()
        val session = auth.sessionFor(bearer) ?: throw Denied("unauthorized")
        return when {
            m == "POST" && segs == listOf("poll") -> poll(req, session)
            m == "POST" && segs == listOf("lease") -> lease(req, session)
            segs.size == 3 && segs[0] == "jobs" && HelperProtocol.JOB_ID_REGEX.matches(segs[1]) -> when {
                m == "GET" && segs[2] == "payload" -> payload(req, session, segs[1])
                m == "POST" && segs[2] == "heartbeat" -> heartbeat(req, session, segs[1])
                m == "POST" && segs[2] == "result" -> result(req, session, segs[1])
                m == "POST" && segs[2] == "fail" -> fail(req, session, segs[1])
                else -> throw Denied("not_found")
            }
            else -> throw Denied("not_found")
        }
    }

    // ---- request parsing: strict, fixed-text errors ----

    private fun body(req: HttpRequest, keys: Set<String>): JsonObject {
        val o = try { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject } catch (_: Exception) { null } ?: throw Denied("bad_request")
        if (o.keys != keys) throw Denied("bad_request")
        return o
    }

    private fun protocol(o: JsonObject) {
        val p = (o["helperProtocol"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: throw Denied("bad_request")
        if (p != HelperProtocol.HELPER_PROTOCOL.toLong()) throw Denied("protocol_mismatch")
    }

    private fun long(e: kotlinx.serialization.json.JsonElement?): Long = (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it >= 0 } ?: throw Denied("bad_request")
    private fun text(e: kotlinx.serialization.json.JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Denied("bad_request")

    private fun requireGrant() { if (!grantOn(HelperGrants.PY_EXEC)) throw Denied("capability_revoked") }

    // ---- routes ----

    private suspend fun poll(req: HttpRequest, s: HelperAuth.Session): HttpResponse {
        val o = body(req, setOf("helperProtocol", "active", "cleanupAcks", "ready"))
        protocol(o)
        val active = (o["active"] as? JsonArray ?: throw Denied("bad_request")).map { e ->
            val a = e as? JsonObject ?: throw Denied("bad_request")
            if (a.keys != setOf("jobId", "leaseGen")) throw Denied("bad_request")
            val id = text(a["jobId"])
            if (!HelperProtocol.JOB_ID_REGEX.matches(id)) throw Denied("bad_request")
            id to long(a["leaseGen"])
        }
        if (active.size > 8) throw Denied("bad_request")
        val acks = (o["cleanupAcks"] as? JsonArray ?: throw Denied("bad_request")).map { e ->
            val v = text(e)
            if (!CLEANUP_ID.matches(v)) throw Denied("bad_request")
            v.toLong()
        }
        if (acks.size > 500) throw Denied("bad_request")
        val ready = o["ready"] as? JsonObject ?: throw Denied("bad_request")
        if (ready.keys != setOf(HelperProtocol.JOB_TYPE_PY_EXEC)) throw Denied("bad_request")
        val pyEl = ready[HelperProtocol.JOB_TYPE_PY_EXEC] as? JsonPrimitive
        val py = when { pyEl == null || pyEl.isString -> throw Denied("bad_request"); pyEl.content == "true" -> true; pyEl.content == "false" -> false; else -> throw Denied("bad_request") }

        if (acks.isNotEmpty()) service.ackCleanup(acks)
        service.advertiseReady(py)
        service.sweep()
        val available = if (grantOn(HelperGrants.PY_EXEC) && service.available(listOf(HelperProtocol.JOB_TYPE_PY_EXEC)).isNotEmpty()) listOf(HelperProtocol.JOB_TYPE_PY_EXEC) else emptyList()
        val cancel = active.filter { (id, gen) ->
            val row = store.job(id)
            !(row != null && row.state == HelperJobState.LEASED.name && row.leaseOwner == s.helperId && row.leaseGen == gen)
        }.map { it.first }
        val cleanup = service.pendingCleanup(CLEANUP_BATCH)
        log.helperLine("poll answered " + cleanup.size)
        return HttpResponse.json(200, buildJsonObject {
            put("sessionGen", s.sessionGen)
            put("caps", buildJsonObject { for ((k, v) in auth.caps()) put(k, v) })
            put("serverTimeMs", clock())
            put("available", JsonArray(available.map { JsonPrimitive(it) }))
            put("cancel", JsonArray(cancel.map { JsonPrimitive(it) }))
            put("cleanup", JsonArray(cleanup.map { c -> buildJsonObject { put("cleanupId", c.cleanupId.toString()); put("jobId", c.jobId) } }))
        })
    }

    private suspend fun lease(req: HttpRequest, s: HelperAuth.Session): HttpResponse {
        val o = body(req, setOf("helperProtocol", "types"))
        protocol(o)
        val types = (o["types"] as? JsonArray ?: throw Denied("bad_request")).map { text(it) }
        if (types.isEmpty() || types.any { it != HelperProtocol.JOB_TYPE_PY_EXEC }) throw Denied("bad_request")
        requireGrant()
        val j = service.lease(s.helperId, types) ?: return HttpResponse.json(200, buildJsonObject { put("job", JsonNull) })
        return HttpResponse.json(200, buildJsonObject {
            put("job", buildJsonObject {
                put("jobId", j.jobId); put("type", j.jobType); put("leaseGen", j.leaseGen); put("leaseUntilMs", j.leaseUntilMs)
                put("deadlineMs", j.deadlineMs); put("bundleHash", j.bundleHash); put("payloadBytes", j.payloadBytes)
            })
            put("serverTimeMs", clock())
        })
    }

    private suspend fun payload(req: HttpRequest, s: HelperAuth.Session, jobId: String): HttpResponse {
        val gen = req.query["leaseGen"]?.takeIf { Regex("^[0-9]{1,18}$").matches(it) }?.toLong() ?: throw Denied("bad_request")
        requireGrant()
        val r = service.releasePayload(jobId, s.helperId, gen)
        if (!r.ok) throw Denied(r.denial ?: "stale_generation")
        log.helperLine("payload released")
        return HttpResponse(200, HttpResponse.JSON, (r.payload ?: throw Denied("voided")).toByteArray(Charsets.UTF_8))
    }

    private suspend fun heartbeat(req: HttpRequest, s: HelperAuth.Session, jobId: String): HttpResponse {
        val gen = long(body(req, setOf("leaseGen"))["leaseGen"])
        requireGrant()
        val r = service.heartbeat(jobId, s.helperId, gen)
        if (!r.ok) throw Denied(r.denial ?: "stale_generation")
        return HttpResponse.json(200, buildJsonObject { put("leaseUntilMs", r.leaseUntilMs ?: 0L); put("serverTimeMs", clock()) })
    }

    private suspend fun result(req: HttpRequest, s: HelperAuth.Session, jobId: String): HttpResponse {
        val o = body(req, setOf("leaseGen", "resultHash", "result"))
        val gen = long(o["leaseGen"])
        val hash = text(o["resultHash"])
        // 422, not 400: the helper retries a 400 until its watchdog kills the job, but answers 422 with a `fail(bad_output)`
        if (!Regex("^[0-9a-f]{64}$").matches(hash)) throw Denied("invalid_result")
        val result = o["result"] as? JsonObject ?: throw Denied("invalid_result")
        requireGrant()
        return when (val c = service.commitResult(jobId, s.helperId, gen, result)) {
            is ResultCommit.Committed -> { log.helperLine(if (c.duplicate) "result duplicate" else "result committed"); HttpResponse.json(200, buildJsonObject { put("committed", !c.duplicate); put("duplicate", c.duplicate) }) }
            is ResultCommit.Denied -> throw Denied(c.code)
            is ResultCommit.Invalid -> throw Denied("invalid_result")
            ResultCommit.TooLarge -> throw Denied("too_large")
        }
    }

    private suspend fun fail(req: HttpRequest, s: HelperAuth.Session, jobId: String): HttpResponse {
        val o = body(req, setOf("leaseGen", "code"))
        val gen = long(o["leaseGen"])
        val code = text(o["code"])
        if (code !in HelperProtocol.FAIL_CODES) throw Denied("bad_request")
        requireGrant()
        if (!service.failJob(jobId, s.helperId, gen, code)) throw Denied("stale_generation")
        return HttpResponse.json(200, buildJsonObject { put("ok", true) })
    }
}
