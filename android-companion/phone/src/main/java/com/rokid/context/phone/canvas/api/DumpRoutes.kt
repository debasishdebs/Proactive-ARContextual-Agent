package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.dump.DumpItem
import com.rokid.context.canvas.dump.DumpSource
import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Developer-only READ-ONLY dump API (design 2026-11-26): `GET /api/dump/manifest`, `/moments`, `/scenes`, `/marks`, `/pins`, `/items` (several object types in one stream: `object_type`, `order`) and `/frames/<id>/photo` (the vectors endpoint was REMOVED, owner ruling 26 Nov: he generates vectors himself). The router owns the pairing token (a request without a paired session never reaches
 * this class: 401), the Host policy (loopback, or the paired LAN host while "Use from a laptop" is on: this route does not widen it) and the Origin policy. This class owns the Developer gate: Developer mode off answers 403 `developer_mode_off`
 * on every call. Other methods are 405. At most [MAX_PER_SECOND] calls a second (429 `rate_limited`, `Retry-After: 1`). A Delete all that lands while a request reads answers 409 `dump_stopped` and no data; any other delete (Forget, Delete saved locations) cannot commit during a read (the read holds the export side of the data-operation gate) and one that runs or waits makes the request answer 409 too; Developer mode going off mid-read
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
    /** review2 #4: the dump read is an EXPORT of the data-operation gate: a Forget, Delete saved locations or any gated delete waits for it (never commits mid-read), and a delete that runs or waits refuses it (`dump_stopped`). */
    private val gate: () -> com.rokid.context.orchestration.DataOpGate = { com.rokid.context.orchestration.DataOpGate.shared },
) {
    companion object {
        const val PREFIX = "/api/dump/"
        const val MAX_PER_SECOND = 60
        private val CURSOR = Regex("^[A-Za-z0-9:_.-]{1,200}$")
        private val FRAME_ID = Regex("^[A-Za-z0-9_.:-]{1,100}$")
        private val DAY = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        private val MS = Regex("^[0-9]{1,15}$")
        private val FIELD = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")
        private const val MAX_FIELDS = 40
        private val TYPES = listOf("moment", "scene", "mark", "pin", "place_attempt")
        /** `place_attempt` (by-exception content: place ids, scores, labels) is opt-in: named in `object_type` only, never part of the default stream. */
        private val DEFAULT_TYPES = setOf("moment", "scene", "mark", "pin")
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
            val r = gate().export { when {
                seg == listOf("manifest") -> HttpResponse.json(200, source.manifest()).also { PcLog.i("PcDump", "dump manifest") }
                seg == listOf("moments") -> page(req, "moments") { f, t, a, n, o -> source.moments(f, t, a, n, o) }
                seg == listOf("scenes") -> page(req, "scenes") { f, t, a, n, o -> source.scenes(f, t, a, n, o) }
                seg == listOf("marks") -> page(req, "marks") { f, t, a, n, o -> source.marks(f, t, a, n, o) }
                seg == listOf("place_attempts") -> page(req, "place_attempts") { f, t, a, n, o -> source.placeAttempts(f, t, a, n, o) }
                seg == listOf("pins") -> pins(req)
                seg == listOf("items") -> items(req)
                seg.size == 3 && seg[0] == "frames" && seg[2] == "photo" -> photo(seg[1])
                else -> HttpResponse.error(404, "not_found", "Not found.")
            } }
            // a Delete all that landed while the read ran, or Developer mode going off: nothing is answered
            if (generation() != gen) stopped() else if (!developerMode()) HttpResponse.error(403, "developer_mode_off", "Developer mode is off.") else r
        } catch (e: com.rokid.context.orchestration.DataOpGate.DeleteRunning) {
            stopped() // a delete runs or waits (a Forget, Delete saved locations, ...): nothing was read
        } catch (e: Bad) {
            HttpResponse.error(400, "bad_request", "Bad request.")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            PcLog.i("PcDump", "dump failed (${e.javaClass.simpleName})")
            HttpResponse.error(500, "internal", "Something went wrong.")
        }
    }

    private suspend fun page(req: HttpRequest, name: String, read: suspend (Long, Long, String?, Int, Boolean) -> List<DumpItem>): HttpResponse {
        val from = bound(one(req, "from", "since"), false) ?: 0L
        val to = bound(one(req, "to", "until"), true) ?: Long.MAX_VALUE
        if (from > to) throw Bad()
        val after = cursor(one(req, "after", "cursor"))
        val limit = limit(req)
        val fields = fields(req.query["fields"])
        val items = read(from, to, after, limit, ascending(req))
        val n = items.size
        PcLog.i("PcDump", "dump page n=$n")
        return pageJson(items, limit, fields)
    }

    private fun pageJson(items: List<DumpItem>, limit: Int, fields: Set<String>? = null): HttpResponse {
        var bytes = 0; val kept = ArrayList<DumpItem>()
        for (x0 in items) {
            val x = if (fields == null) x0 else DumpItem(x0.cursor, project(x0.json, fields))
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

    private suspend fun pins(req: HttpRequest): HttpResponse {
        val fields = fields(req.query["fields"])
        val asc = ascending(req)
        val p = source.pins().let { l -> if (asc) l else l.reversed() }.let { l -> if (fields == null) l else l.map { project(it, fields) } }
        val n = p.size
        PcLog.i("PcDump", "dump pins n=$n")
        return HttpResponse.json(200, buildJsonObject { put("items", JsonArray(p)); put("count", p.size); put("next", JsonNull) })
    }

    /**
     * `GET /api/dump/items?object_type=moment,scene,mark,pin`: one stream of several object types, each item carrying `object_type`, ordered by its time (`startMs` of a Moment or Scene, `atMs` of a Mark, `pinnedAt` of a pin, 0 when a pin has none) then type then id,
     * `order` asc or desc, paged by `limit`/`batch_size` with the composite cursor `<ms>:<type>:<id>` (the `next` of the previous page). `object_type` (or `object_type[]`) is a comma-separated list; absent = all four; an unknown name, an empty list or a repeated name is 400.
     * Each type is read with the same range, then merged: the read of one type holds at most `limit` items, so a page is exact unless more than `limit` items of ONE type share the same millisecond (not possible for Moments, one per second).
     */
    private suspend fun items(req: HttpRequest): HttpResponse {
        val types = objectTypes(req)
        val from = bound(one(req, "from", "since"), false) ?: 0L
        val to = bound(one(req, "to", "until"), true) ?: Long.MAX_VALUE
        if (from > to) throw Bad()
        val cur = composite(one(req, "after", "cursor"))
        val limit = limit(req)
        val fields = fields(req.query["fields"])
        val asc = ascending(req)
        // the cursor's time narrows each read; the exact tuple below drops what the previous page already gave
        val f = if (asc && cur != null) maxOf(from, cur.ms) else from
        val t = if (!asc && cur != null) minOf(to, cur.ms) else to
        if (f > t) return pageJson(emptyList(), limit)
        var readLimit = limit
        var all = ArrayList<Pair<Triple<Long, Int, String>, DumpItem>>()
        var full = false
        var sorted = emptyList<Pair<Triple<Long, Int, String>, DumpItem>>()
        // A type read stops at readLimit rows: when rows tied on the cursor millisecond were all dropped as already served, read again with a larger limit (bounded) instead of ending the stream early.
        while (true) {
        all = ArrayList<Pair<Triple<Long, Int, String>, DumpItem>>()
        full = false
        fun add(type: String, list: List<DumpItem>, key: (JsonObject) -> Long) {
            if (list.size >= readLimit) full = true
            list.forEach { x ->
                val id = (x.json["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                val k = Triple(key(x.json), TYPES.indexOf(type), id)
                val body = JsonObject(mapOf("object_type" to kotlinx.serialization.json.JsonPrimitive(type)) + x.json)
                all += k to DumpItem("${k.first}:$type:$id", if (fields == null) body else project(body, fields + "object_type"))
            }
        }
        if ("moment" in types) add("moment", source.moments(f, t, null, readLimit, asc)) { num(it, "startMs") }
        if ("scene" in types) add("scene", source.scenes(f, t, null, readLimit, asc)) { num(it, "startMs") }
        if ("mark" in types) add("mark", source.marks(f, t, null, readLimit, asc)) { num(it, "atMs") }
        if ("place_attempt" in types) add("place_attempt", source.placeAttempts(f, t, null, readLimit, asc)) { num(it, "atMs") }
        if ("pin" in types) {
            val pins = source.pins().filter { num(it, "pinnedAt") in f..t }
            // a pin carries no cursor of its own: its composite cursor is built the same way
            pins.forEach { p -> val id = (p["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach; val k = Triple(num(p, "pinnedAt"), TYPES.indexOf("pin"), id)
                all += k to DumpItem("${k.first}:pin:$id", JsonObject(mapOf("object_type" to kotlinx.serialization.json.JsonPrimitive("pin")) + p).let { b -> if (fields == null) b else project(b, fields + "object_type") }) }
        }
        val cmp = Comparator<Pair<Triple<Long, Int, String>, DumpItem>> { a, b ->
            compareValuesBy(a, b, { it.first.first }, { it.first.second }, { it.first.third })
        }
        sorted = all.sortedWith(if (asc) cmp else cmp.reversed())
        if (cur != null) sorted = sorted.filter { val c = compareValuesBy(it.first, Triple(cur.ms, TYPES.indexOf(cur.type), cur.id), { x -> x.first }, { x -> x.second }, { x -> x.third }); if (asc) c > 0 else c < 0 }
        if (!full || sorted.size > limit || readLimit >= ITEMS_READ_CAP) break
        readLimit = minOf(readLimit * 4, ITEMS_READ_CAP)
        }
        val more = full || sorted.size > limit
        val page = sorted.take(limit).map { it.second }
        PcLog.i("PcDump", "dump items n=${page.size}")
        // `more` forces a `next` even when the page is short of `limit` (a type was cut at its own limit)
        val r = pageJson(page, if (more && page.size < limit) page.size.coerceAtLeast(1) else limit)
        return r
    }

    private val ITEMS_READ_CAP = 5_000

    private class Composite(val ms: Long, val type: String, val id: String)

    private fun composite(v: String?): Composite? {
        if (v.isNullOrEmpty()) return null
        if (!CURSOR.matches(v)) throw Bad()
        val a = v.indexOf(':'); val b = if (a > 0) v.indexOf(':', a + 1) else -1
        if (b < 0) throw Bad()
        val ms = v.substring(0, a).toLongOrNull()?.takeIf { it >= 0 } ?: throw Bad()
        val type = v.substring(a + 1, b); val id = v.substring(b + 1)
        if (type !in TYPES || id.isEmpty()) throw Bad()
        return Composite(ms, type, id)
    }

    private fun num(o: JsonObject, k: String): Long = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun objectTypes(req: HttpRequest): Set<String> {
        val a = req.query["object_type"]; val b = req.query["object_type[]"]
        if (a != null && b != null) throw Bad()
        val v = a ?: b ?: return DEFAULT_TYPES
        val names = v.split(',').map { it.trim() }
        if (names.any { it !in TYPES } || names.toSet().size != names.size) throw Bad()
        return names.toSet()
    }

    /** `order=asc` (default) or `desc`; anything else is 400. */
    private fun ascending(req: HttpRequest): Boolean = when (req.query["order"]?.lowercase()) { null, "", "asc" -> true; "desc" -> false; else -> throw Bad() }

    private suspend fun photo(id: String): HttpResponse {
        if (!FRAME_ID.matches(id)) throw Bad()
        val ph = source.photo(id) ?: return HttpResponse.error(404, "not_found", "Not found.")
        PcLog.i("PcDump", "dump photo")
        return HttpResponse(200, ph.contentType, ph.bytes, mapOf("Cache-Control" to "no-store", "Cross-Origin-Resource-Policy" to "same-origin"))
    }

    /** The value of [name] or of its alias [alias]; both given is a bad request. */
    private fun one(req: HttpRequest, name: String, alias: String): String? {
        val a = req.query[name]; val b = req.query[alias]
        if (!a.isNullOrEmpty() && !b.isNullOrEmpty()) throw Bad()
        return if (!a.isNullOrEmpty()) a else b
    }

    /** `fields=a,b`: the top-level keys to keep per item (`id` is always kept). Null = all. Names are plain identifiers; an unknown name just matches nothing. */
    private fun fields(v: String?): Set<String>? {
        if (v.isNullOrEmpty()) return null
        val names = v.split(',')
        if (names.size > MAX_FIELDS || names.any { !FIELD.matches(it) }) throw Bad()
        return names.toSet() + "id"
    }

    private fun project(o: JsonObject, keep: Set<String>) = JsonObject(o.filterKeys { it in keep })

    private fun cursor(v: String?): String? { if (v.isNullOrEmpty()) return null; if (!CURSOR.matches(v)) throw Bad(); return v }
    /** `limit` or its alias `batch_size` (the page size); both given and different is a bad request. */
    private fun limit(req: HttpRequest): Int {
        val a = req.query["limit"]; val b = req.query["batch_size"]
        if (!a.isNullOrEmpty() && !b.isNullOrEmpty() && a != b) throw Bad()
        val v = if (!a.isNullOrEmpty()) a else b
        return if (v.isNullOrEmpty()) DumpSource.MAX_PAGE_ITEMS else (v.toIntOrNull()?.takeIf { it > 0 } ?: throw Bad()).coerceAtMost(DumpSource.MAX_PAGE_ITEMS)
    }

    /** An ISO-8601 instant or offset date-time (`2026-11-25T10:00:00Z`, `...+05:30`) or a zone-less local date-time, as epoch ms; null when it is none of those. */
    private fun iso(v: String): Long? {
        if (v.length > 40) return null
        try { return java.time.Instant.parse(v).toEpochMilli() } catch (e: DateTimeException) { /* next */ }
        try { return java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() } catch (e: DateTimeException) { /* next */ }
        return try { java.time.LocalDateTime.parse(v).atZone(zone()).toInstant().toEpochMilli() } catch (e: DateTimeException) { null }
    }

    /** `from`/`since` and `to`/`until`: epoch milliseconds, a local day `YYYY-MM-DD` (`to` = the end of that day) or an ISO-8601 date-time. Null = not given. */
    private fun bound(v: String?, end: Boolean): Long? {
        if (v.isNullOrEmpty()) return null
        if (MS.matches(v)) return v.toLong()
        if (!DAY.matches(v)) return iso(v) ?: throw Bad()
        val d = try { LocalDate.parse(v) } catch (e: DateTimeException) { throw Bad() }
        val z = zone()
        return if (end) d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1 else d.atStartOfDay(z).toInstant().toEpochMilli()
    }
}
