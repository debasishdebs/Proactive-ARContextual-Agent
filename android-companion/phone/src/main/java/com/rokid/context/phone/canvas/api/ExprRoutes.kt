package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.plan.ExprProgram
import com.rokid.context.ai.canvas.plan.ExprTry
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `POST /api/expr/try` (3c X8): the phone-evaluated try-it. Body `{graph, role?, row?, field?, signals: {name: number | [number|null...]}, ctx?, ctxAgeS?}` (null = a missing second, 3d) ->
 * `200 {ok, steps:[{name, result:{state, value?}}], output, verdict|applied, seconds, windowStateBytes}` (state NUM/BOOL/ENUM/ERROR/DIV0/NOT_READY/UNAVAILABLE), or `200 {ok:false, problems}`
 * for a program that does not compile. The evaluator is the glasses' `ExprRunner` ([ExprTry]); the route reads and writes nothing. Auth, Host/Origin policy and the body
 * limit are the router's; input over [ExprTry.MAX_BYTES], too many signals or values, a non-number and ragged lists are a typed `400` with a fixed message.
 */
class ExprRoutes(private val windowMinValidPct: () -> Int = { ExprProgram.DEFAULT_MIN_VALID_PCT }) {
    suspend fun handle(req: HttpRequest): HttpResponse? {
        if (req.path != "/api/expr/try") return null
        if (!req.method.equals("POST", true)) return HttpResponse.error(405, "bad_request", "Method not allowed.")
        if (req.body.size > ExprTry.MAX_BYTES) return HttpResponse.error(400, "too_large", "The request is too large.")
        val body = runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return HttpResponse.error(400, "bad_request", "Bad request.")
        return try {
            when (val r = ExprTry.run(body, windowMinValidPct())) {
                is ExprTry.Outcome.Ok -> HttpResponse.json(200, r.body)
                is ExprTry.Outcome.Bad -> HttpResponse.error(400, r.code, r.msg, r.problems)
            }
        } catch (e: Throwable) { HttpResponse.error(500, "internal", "Something went wrong.") }
    }
}
