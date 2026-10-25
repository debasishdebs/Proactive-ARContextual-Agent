package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/**
 * `POST /api/moments/rebuild?day=YYYY-MM-DD`, a developer tool to see why a Moment is missing (catch-up diagnosis 2026-10-25). Auth, Host/Origin policy and body size are the router's.
 * It runs what the 15-minute cadence runs, for one local day: the one-time mark catch-up (a no-op once its marker is stored) and the window rebuild of that day, through the SAME builder
 * (store switch, tombstones, Forget fences and the unknown-capture-time rule apply, so it can never resurrect a forgotten second). It calls no model and costs nothing, so there is no confirm token;
 * it is idempotent. Developer mode only (403 `developer_mode_off`); a missing or malformed `day` is 400 `bad_request`; a second call within [MIN_INTERVAL_MS] is 429 `rate_limited`;
 * one at a time (409 `busy`). The answer is counts only: `{day, catchUp: {stored, deleted, rejected, done}, window: {stored, deleted, rejected}}`.
 */
class MomentRoutes(
    private val developerMode: () -> Boolean,
    private val rebuild: suspend (fromMs: Long, toMs: Long, nowMs: Long) -> MomentBuilder.DayReport,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    companion object {
        const val MIN_INTERVAL_MS = 10_000L
        private val DAY = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    }

    private val gate = Mutex()
    private var lastAt = Long.MIN_VALUE

    suspend fun handle(req: HttpRequest): HttpResponse? {
        val seg = req.path.removePrefix("/api").split('/').filter { it.isNotEmpty() }
        if (seg != listOf("moments", "rebuild")) return null
        if (!req.method.equals("POST", true)) return HttpResponse.error(405, "bad_request", "Method not allowed.")
        if (!developerMode()) return HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        val day = req.query["day"]
        val date = day?.takeIf { DAY.matches(it) }?.let { try { LocalDate.parse(it) } catch (e: DateTimeException) { null } }
            ?: return HttpResponse.error(400, "bad_request", "Bad day.")
        if (!gate.tryLock()) return HttpResponse.error(409, "busy", "A rebuild is already running.")
        try {
            val now = clock()
            if (lastAt != Long.MIN_VALUE && now - lastAt < minIntervalMs) return HttpResponse.error(429, "rate_limited", "Try again in a few seconds.")
            lastAt = now
            val z = zone()
            val from = date.atStartOfDay(z).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
            val r = try { rebuild(from, to, now) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                PcLog.i("PcMoments", "day rebuild failed (${e.javaClass.simpleName})"); return HttpResponse.error(500, "internal", "Something went wrong.")
            }
            val stored = r.window.stored; val deleted = r.window.removed; val rejected = r.window.refused
            PcLog.i("PcMoments", "day rebuild: stored=$stored deleted=$deleted rejected=$rejected")
            return HttpResponse.json(200, buildJsonObject {
                put("day", date.toString())
                put("catchUp", buildJsonObject { put("stored", r.catchUp.stored); put("deleted", r.catchUp.removed); put("rejected", r.catchUp.refused); put("done", r.catchUpDone) })
                put("window", buildJsonObject { put("stored", r.window.stored); put("deleted", r.window.removed); put("rejected", r.window.refused) })
            })
        } finally { gate.unlock() }
    }
}
