package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.dumpimport.DumpImportParser
import com.rokid.context.canvas.dumpimport.ImportReport
import com.rokid.context.core.log.PcLog
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Developer IMPORT (owner round 7, 26 Nov): `POST /api/import[?dedup=true][&dry_run=true]`. The body is `{"items":[...]}`, the dump's own item objects plus a `photos` map on a Moment (see [DumpImportParser]).
 * Gates, in this order: the router's pairing token (401), Host and Origin policy (421/403) and the 20 MiB body cap (413) come first; then 405 not POST, 403 `developer_mode_off`, 403 `import_off` (the
 * `devImport` Developer flag, default OFF), 429 `rate_limited` (at most [MAX_PER_SECOND] calls a second, as the dump routes), 409 `busy` (one import at a time), 400 on a bad query (only `dedup` and `dry_run`,
 * `true` or `false`) or a bad body (the item INDEX and a fixed token, never content). Nothing is written for a 400. A Moment whose id (or frame, memory or mark id) already exists is `skipped` with `dedup=true`,
 * else it is a per-item `conflict` and the answer is 409 (the other items are still imported). A forgotten second is always `skipped: forgotten`. Delete all landing mid-import answers 409 `import_stopped`.
 * The answer and the log carry counts and fixed tokens only: `PcImport import n=<count>`.
 */
class ImportRoutes(
    private val developerMode: () -> Boolean,
    private val flagOn: () -> Boolean,
    private val import: suspend (List<DumpImportParser.Entry>, Boolean, Boolean) -> ImportReport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPerSecond: Int = MAX_PER_SECOND,
) {
    companion object {
        const val PATH = "/api/import"
        const val MAX_PER_SECOND = 60
        const val MAX_BODY = 20 * 1024 * 1024
        private val PARAMS = setOf("dedup", "dry_run")
    }

    private val busy = Mutex()
    private val windowLock = Any()
    private var windowStart = 0L
    private var windowCount = 0

    private fun rateLimited(): Boolean = synchronized(windowLock) {
        val now = clock()
        if (now - windowStart >= 1000L || now < windowStart) { windowStart = now; windowCount = 0 }
        windowCount++
        windowCount > maxPerSecond
    }

    private fun flag(req: HttpRequest, k: String): Boolean? = when (req.query[k]) { null -> false; "true" -> true; "false" -> false; else -> null }

    suspend fun handle(req: HttpRequest): HttpResponse? {
        if (req.path != PATH) return null
        if (!req.method.equals("POST", true)) return HttpResponse.error(405, "bad_request", "Method not allowed.")
        if (!developerMode()) return HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        if (!flagOn()) return HttpResponse.error(403, "import_off", "Import is off (Developer flag).")
        if (rateLimited()) return HttpResponse.error(429, "rate_limited", "Try again in a second.").let { HttpResponse(it.status, it.contentType, it.body, mapOf("Retry-After" to "1")) }
        if (req.bodyTooLarge || req.body.size > MAX_BODY) return HttpResponse.error(413, "too_large", "Request body too large.")
        if (req.query.keys.any { it !in PARAMS }) return bad()
        val dedup = flag(req, "dedup") ?: return bad()
        val dry = flag(req, "dry_run") ?: return bad()
        val entries = try { DumpImportParser.parse(req.body) } catch (e: DumpImportParser.Bad) {
            PcLog.i("PcImport", "import bad request")
            return HttpResponse.json(400, buildJsonObject { put("error", "bad_request"); put("message", "Bad import."); put("item", e.index); put("reason", e.reason) })
        }
        if (!busy.tryLock()) return HttpResponse.error(409, "busy", "An import is already running.")
        try {
            val r = try { import(entries, dedup, dry) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                PcLog.i("PcImport", "import failed (${e.javaClass.simpleName})")
                return HttpResponse.error(500, "internal", "Something went wrong.")
            }
            val created = r.count("created"); val would = r.count("would_create"); val conflicts = r.count("conflict"); val failed = r.count("failed")
            val n = entries.size
            PcLog.i("PcImport", "import n=$n created=$created would=$would conflicts=$conflicts failed=$failed dry=$dry")
            if (r.stopped) return HttpResponse.error(409, "import_stopped", "The import was stopped.")
            val skipped = r.items.filter { it.first == "skipped" }.groupingBy { it.second }.eachCount()
            val body = buildJsonObject {
                put("dry_run", dry); put("received", n); put("created", created); put("would_create", would); put("conflicts", conflicts); put("failed", failed)
                put("skipped", buildJsonObject { skipped.toSortedMap().forEach { (k, v) -> put(k, v) } })
                put("counts", buildJsonObject {
                    put("frames", r.frames); put("memories", r.memories); put("marks", r.marks); put("locations", r.locations)
                    put("place_cards", r.cards); put("place_cards_skipped", buildJsonObject { r.cardsSkipped.toSortedMap().forEach { (k, v) -> put(k, v) } }); put("pins", r.pins); put("pin_links_dropped", r.pinLinksDropped)
                })
                put("results", JsonArray(r.items.mapIndexed { i, p -> buildJsonObject { put("i", i); put("status", p.first); put("reason", p.second) } }))
            }
            return HttpResponse.json(if (conflicts > 0) 409 else 200, body)
        } finally { busy.unlock() }
    }

    private fun bad() = HttpResponse.error(400, "bad_request", "Bad request.")
}
