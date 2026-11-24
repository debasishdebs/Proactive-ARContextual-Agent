package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.moment.MomentLocationImport
import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v18 WP5: `POST /api/moments/locations/import`, the Developer-only GPS backfill import (auth, Host/Origin policy and the 256 KiB body cap are the router's). The body is a TSV whose first line is exactly
 * [MomentLocationImport.HEADER]. Refusals: 405 not POST, 403 `developer_mode_off`, 409 `busy`, 429 `rate_limited`, 409 `setting_off`, 409 `zone_active`, 400 `too_large` (body over the cap or over [MAX_ROWS] rows),
 * 400 `bad_header`. A bad row counts `bad_row` and is not fatal. The answer is counts only, `{received, written, skipped:{...}}`; the body is never logged.
 */
class MomentLocationRoutes(
    private val developerMode: () -> Boolean,
    private val settingOn: () -> Boolean,
    private val zoneBlocked: suspend () -> Boolean,
    private val import: MomentLocationImport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    companion object {
        const val MIN_INTERVAL_MS = 10_000L
        const val MAX_ROWS = 2_000
        const val PATH = "/api/moments/locations/import"
    }

    private val gate = Mutex()
    private var lastAt = Long.MIN_VALUE

    suspend fun handle(req: HttpRequest): HttpResponse? {
        if (req.path != PATH) return null
        if (!req.method.equals("POST", true)) return HttpResponse.error(405, "bad_request", "Method not allowed.")
        if (!developerMode()) return HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        if (!gate.tryLock()) return HttpResponse.error(409, "busy", "An import is already running.")
        try {
            val now = clock()
            if (lastAt != Long.MIN_VALUE && now - lastAt < minIntervalMs) return HttpResponse.error(429, "rate_limited", "Try again in a few seconds.")
            lastAt = now
            if (!settingOn()) return HttpResponse.error(409, "setting_off", "Save location with Moments is off.")
            if (zoneBlocked()) return HttpResponse.error(409, "zone_active", "A pause zone is active.")
            if (req.bodyTooLarge) return HttpResponse.error(400, "too_large", "Too large.")
            val lines = String(req.body, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }.let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
            if (lines.isEmpty() || lines[0] != MomentLocationImport.HEADER) return HttpResponse.error(400, "bad_header", "Bad header.")
            if (lines.size - 1 > MAX_ROWS) return HttpResponse.error(400, "too_large", "Too many rows.")
            val rows = ArrayList<MomentLocationImport.Row>(); var bad = 0
            for (l in lines.drop(1)) { val r = MomentLocationImport.parseRow(l); if (r == null) bad++ else rows += r }
            val rep = try { import.run(rows, bad) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                PcLog.i("PcMoments", "location import failed (${e.javaClass.simpleName})"); return HttpResponse.error(500, "internal", "Something went wrong.")
            }
            val written = rep.written
            PcLog.i("PcMoments", "location import written=$written")
            return HttpResponse.json(200, buildJsonObject {
                put("received", rep.received); put("written", rep.written)
                put("skipped", buildJsonObject { rep.skipped.forEach { (k, v) -> put(k, v) } })
            })
        } finally { gate.unlock() }
    }
}
