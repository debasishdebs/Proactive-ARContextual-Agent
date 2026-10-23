package com.rokid.context.phone.canvas.helper

import com.rokid.context.canvas.helper.HelperJobService
import com.rokid.context.phone.canvas.api.ApiExtra
import com.rokid.context.phone.canvas.server.HelperAuth
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The two BROWSER routes about the helper (browser bearer token; the helper's token never reaches them).
 *
 * `GET /api/helper-status` reports what the page may show: whether a helper is paired, each grant, the helper's advertised readiness (and whether it is stale), and the
 * per-run confirmation state as a COUNT of runs waiting for the user's tap plus the earliest tap-window end and the phone's clock. It names no job, hash, tag or key and it is never a control:
 * the tap happens on the phone (the card), and no HTTP route confirms, declines or lists jobs for confirmation.
 *
 * `/api/helper-jobs` exists only to refuse: a browser cannot submit a `py_exec` job. A run starts through `POST /api/runs/estimate` and `POST /api/runs`.
 */
class HelperBrowserRoutes(
    private val auth: HelperAuth,
    private val service: HelperJobService,
    private val grantOn: (String) -> Boolean,
    /** The phone clock, reported with the status so the page counts the tap window down on the phone's time, not its own. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ApiExtra {
    override suspend fun handle(req: HttpRequest): HttpResponse? {
        val m = req.method.uppercase()
        return when {
            req.path == "/api/helper-status" && m == "GET" -> status()
            req.path == "/api/helper-jobs" && m == "POST" -> HttpResponse.error(403, "forbidden", "Python runs start from a run, not from this route.")
            else -> null
        }
    }

    private suspend fun status(): HttpResponse {
        val awaiting = service.pendingConfirmations()
        val r = service.readiness()
        return HttpResponse.json(200, buildJsonObject {
            put("paired", auth.session() != null)
            put("grants", buildJsonObject { for (k in HelperGrants.KNOWN) put(k, grantOn(k)) })
            put("ready", r.ready)
            put("readyStale", r.stale)
            put("awaitingConfirm", awaiting.size)
            put("confirmExpiresAtMs", awaiting.minOfOrNull { it.expiresAtMs }?.let { JsonPrimitive(it) } ?: JsonNull)
            put("serverTimeMs", clock())
        })
    }
}
