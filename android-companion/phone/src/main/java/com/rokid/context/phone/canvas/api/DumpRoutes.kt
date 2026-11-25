package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.dump.DumpItem
import com.rokid.context.canvas.dump.DumpSource
import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Developer-only READ-ONLY dump API (design 2026-11-26): `GET /api/dump/manifest`, `/moments`, `/scenes`, `/marks`, `/pins`, `/frames/<id>/photo` and `/vectors`. The router owns the pairing token (a request without a paired session never reaches
 * this class: 401), the Host policy (loopback, or the paired LAN host while "Use from a laptop" is on: this route does not widen it) and the Origin policy. This class owns the Developer gate: Developer mode off answers 403 `developer_mode_off`
 * on every call. Other methods are 405. At most [MAX_PER_SECOND] calls a second (429 `rate_limited`, `Retry-After: 1`). A Delete all that lands while a request reads answers 409 `dump_stopped` and no data; Developer mode going off mid-read
 * answers 403. Pages hold at most [DumpSource.MAX_PAGE_ITEMS] items and [DumpSource.MAX_PAGE_BYTES] bytes; a photo is capped at [DumpSource.MAX_PHOTO_BYTES]. Logs: a literal and a count, never an id, text or position. This route carries the
 * saved Moment location, the pin coordinates and the place names (the owner's explicit exception to the v18 rule); no other route does.
 */
class DumpRoutes(
    private val developerMode: () -> Boolean,
    private val source: DumpSource,
    private val generation: () -> Long = { com.rokid.context.orchestration.MemoryWipe.generation.get() },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPerSecond: Int = MAX_PER_SECOND,
) {
    companion object {
        const val PREFIX = "/api/dump/"
        const val MAX_PER_SECOND = 60
        private val CURSOR = Regex("^[A-Za-z0-9:_.-]{1,200}$")
        private val FRAME_ID = Regex("^[A-Za-z0-9_.:-]{1,100}$")
        private val DAY = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        private val MS = Regex("^[0-9]{1,15}$")
    }

    private class Bad : Exception()

    private val windowLock = Any()
    private var windowStart = 0L
    private var windowCount = 0

    private fun rateLimited(): Boolean = synchronized(windowLock) {
        val now = clock()
        if (now - windowStart >= 1000L || now < windowStart) { windowStart = now; windowCount = 0 }
        windowCount++
        windowCount > maxPerSecond
    }

    private fun stopped() = HttpResponse.error(409, "dump_stopped", "The dump was stopped.")

    suspend fun handle(req: HttpRequest): HttpResponse? {
        if (!req.path.startsWith(PREFIX)) return null
        val seg = req.path.removePrefix(PREFIX).split('/')
        if (!req.method.equals("GET", true)) return HttpResponse.error(405, "bad_request", "Method not allowed.")
        if (!developerMode()) return HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        if (rateLimited()) return HttpResponse.error(429, "rate_limited", "Try again in a second.").let { HttpResponse(it.status, it.contentType, it.body, mapOf("Retry-After" to "1")) }
        val gen = generation()
        return try {
            val r = when {
                seg == listOf("manifest") -> HttpResponse.json(200, source.manifest()).also { PcLog.i("PcDump", "dump manifest") }
                seg == listOf("moments") -> page(req, "moments") { f, t, a, n -> source.moments(f, t, a, n) }
                seg == listOf("scenes") -> page(req, "scenes") { f, t, a, n -> source.scenes(f, t, a, n) }
                seg == listOf("marks") -> page(req, "marks") { f, t, a, n -> source.marks(f, t, a, n) }
                seg == listOf("pins") -> pins()
                seg.size == 3 && seg[0] == "frames" && seg[2] == "photo" -> photo(seg[1])
                seg == listOf("vectors") -> vectors(req)
                else -> HttpResponse.error(404, "not_found", "Not found.")
            }
            // a Delete all that landed while the read ran, or Developer mode going off: nothing is answered
            if (generation() != gen) stopped() else if (!developerMode()) HttpResponse.error(403, "developer_mode_off", "Developer mode is off.") else r
        } catch (e: Bad) {
            HttpResponse.error(400, "bad_request", "Bad request.")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            PcLog.i("PcDump", "dump failed (${e.javaClass.simpleName})")
            HttpResponse.error(500, "internal", "Something went wrong.")
        }
    }

    private suspend fun page(req: HttpRequest, name: String, read: suspend (Long, Long, String?, Int) -> List<DumpItem>): HttpResponse {
        val from = bound(req.query["from"], false) ?: 0L
        val to = bound(req.query["to"], true) ?: Long.MAX_VALUE
        val after = cursor(req.query["after"])
        val limit = limit(req.query["limit"])
        val items = read(from, to, after, limit)
        val n = items.size
        PcLog.i("PcDump", "dump page n=$n")
        return pageJson(items, limit)
    }

    private fun pageJson(items: List<DumpItem>, limit: Int): HttpResponse {
        var bytes = 0; val kept = ArrayList<DumpItem>()
        for (x in items) {
            val n = x.json.toString().toByteArray(Charsets.UTF_8).size + 1
            if (kept.isNotEmpty() && bytes + n > DumpSource.MAX_PAGE_BYTES - 1024) break
            kept += x; bytes += n
        }
        val more = kept.size < items.size || items.size >= limit
        return HttpResponse.json(200, buildJsonObject {
            put("items", JsonArray(kept.map { it.json })); put("count", kept.size)
            if (more && kept.isNotEmpty()) put("next", kept.last().cursor) else put("next", JsonNull)
        })
    }

    private suspend fun pins(): HttpResponse {
        val p = source.pins()
        val n = p.size
        PcLog.i("PcDump", "dump pins n=$n")
        return HttpResponse.json(200, buildJsonObject { put("items", JsonArray(p)); put("count", p.size); put("next", JsonNull) })
    }

    private suspend fun photo(id: String): HttpResponse {
        if (!FRAME_ID.matches(id)) throw Bad()
        val ph = source.photo(id) ?: return HttpResponse.error(404, "not_found", "Not found.")
        PcLog.i("PcDump", "dump photo")
        return HttpResponse(200, ph.contentType, ph.bytes, mapOf("Cache-Control" to "no-store", "Cross-Origin-Resource-Policy" to "same-origin"))
    }

    private suspend fun vectors(req: HttpRequest): HttpResponse {
        val space = req.query["space"] ?: throw Bad()
        val dtype = req.query["dtype"] ?: "float32"
        if (dtype != "float32" && dtype != "float16") throw Bad()
        val limit = limit(req.query["limit"])
        val items = source.vectors(space, cursor(req.query["after"]), limit, dtype == "float16") ?: throw Bad()
        // vectors are 3 KB each: the byte cap, not the 200 items, is what usually ends a page
        val n = items.size
        PcLog.i("PcDump", "dump vectors n=$n")
        return pageJson(items, limit)
    }

    private fun cursor(v: String?): String? { if (v.isNullOrEmpty()) return null; if (!CURSOR.matches(v)) throw Bad(); return v }
    private fun limit(v: String?): Int = if (v.isNullOrEmpty()) DumpSource.MAX_PAGE_ITEMS else (v.toIntOrNull()?.takeIf { it > 0 } ?: throw Bad()).coerceAtMost(DumpSource.MAX_PAGE_ITEMS)

    /** `from` and `to`: epoch milliseconds or a local day `YYYY-MM-DD` (`to` = the end of that day). Null = not given. */
    private fun bound(v: String?, end: Boolean): Long? {
        if (v.isNullOrEmpty()) return null
        if (MS.matches(v)) return v.toLong()
        if (!DAY.matches(v)) throw Bad()
        val d = try { LocalDate.parse(v) } catch (e: DateTimeException) { throw Bad() }
        val z = zone()
        return if (end) d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1 else d.atStartOfDay(z).toInstant().toEpochMilli()
    }
}
