package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.trace.CollectSession
import com.rokid.context.canvas.trace.TraceImport
import com.rokid.context.canvas.trace.TraceImportResult
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.TraceBounds
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Trace and "Collect for tuning" routes (Developer mode; the router has done auth). [handle] returns null for a path that is not one of them. Messages are fixed texts.
 *
 * - `GET /api/traces` -> the stored traces (no manifest, no events); `GET /api/traces/{id}`
 * - `GET /api/collect` -> `{active, untilMs}`; `POST /api/collect/start` `{minutes?}` (1 to 720; default 20); `POST /api/collect/stop`
 *
 * - `POST /api/traces/import` `{name, allowPartial?}` -> imports the trace pulled to `<staging>/<name>/` (`tools/m1-run.sh pull`, via adb push) with [TraceImport]: `{traceId, status, events, chunks, unchanged}`.
 *   `name` is only a trace id (no path, no `..`; the directory is resolved strictly under the staging root and a symlink is not followed). 409 for a PARTIAL trace unless
 *   `allowPartial`, an old epoch or a tombstone, 413 over the phone cap, 422 for a bad or tampered file set. The staging directory is deleted after a successful import only.
 *
 * The [CollectSession] is the hub's: a start or stop moves its flow and the hub sends the control; this class never talks to the glasses.
 */
class TraceRoutes(
    private val dao: CanvasSlice3Dao,
    private val collect: () -> CollectSession?,
    private val developerMode: () -> Boolean,
    private val importer: () -> TraceImport? = { null },
    /** The app's own external files dir `trace-staging` (adb push can write it without root); null when there is none. */
    private val stagingRoot: () -> File? = { null },
) {
    private val ID = Regex("^[A-Za-z0-9:_-]{1,64}$")

    suspend fun handle(req: HttpRequest): HttpResponse? {
        val seg = req.path.removePrefix("/api").split('/').filter { it.isNotEmpty() }
        val head = seg.firstOrNull()
        if (head != "traces" && head != "collect") return null
        val m = req.method.uppercase()
        if (!developerMode()) return HttpResponse.error(403, "developer_mode_off", "Developer mode is off.")
        return try {
            when {
                head == "traces" && seg.size == 1 && m == "GET" -> HttpResponse.json(200, JsonArray(dao.liveTraces().map(::row)))
                head == "traces" && seg.size == 2 && m == "GET" ->
                    HttpResponse.json(200, row(seg[1].takeIf { ID.matches(it) }?.let { dao.trace(it) }?.takeIf { !it.tombstoned } ?: throw NoSuchElementException()))
                head == "traces" && seg.size == 2 && seg[1] == "import" && m == "POST" -> import(req)
                head == "collect" && seg.size == 1 && m == "GET" -> status()
                head == "collect" && seg.size == 2 && seg[1] == "start" && m == "POST" -> start(req)
                head == "collect" && seg.size == 2 && seg[1] == "stop" && m == "POST" -> { (collect() ?: return unavailable()).stop(); status() }
                else -> HttpResponse.error(404, "not_found", "Not found.")
            }
        } catch (e: NoSuchElementException) { HttpResponse.error(404, "not_found", "Not found.")
        } catch (e: IllegalArgumentException) { HttpResponse.error(400, "bad_request", "Bad request.")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { HttpResponse.error(500, "internal", "Something went wrong.") }
    }

    private suspend fun import(req: HttpRequest): HttpResponse {
        val o = runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: throw IllegalArgumentException("body")
        val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("name")
        val allowPartial = when (val v = o["allowPartial"]) { null, is JsonNull -> false; else -> (v as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("allowPartial") }
        if (!TraceBounds.isTraceId(name)) throw IllegalArgumentException("name")
        val imp = importer() ?: return unavailable()
        val root = stagingRoot()?.takeIf { it.isDirectory } ?: return HttpResponse.error(404, "not_found", "No staging directory.")
        val dir = File(root, name)
        // Strictly under the root: a real directory (a symlink is not followed) whose canonical parent is the canonical root.
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) || dir.canonicalFile.parentFile != root.canonicalFile) return HttpResponse.error(404, "not_found", "No such staged trace.")
        return when (val r = imp.import(dir, allowPartial)) {
            is TraceImportResult.Imported -> imported(dir, r.traceId, false)
            is TraceImportResult.Unchanged -> imported(dir, r.traceId, true)
            is TraceImportResult.Rejected -> HttpResponse.error(
                when (r.code) { "partial_trace", "tombstoned", "old_epoch", "other_install", "fence" -> 409; "phone_cap" -> 413; "not_a_directory" -> 404; else -> 422 },
                r.code, "The trace was not imported.",
            )
        }
    }

    private suspend fun imported(dir: File, traceId: String, unchanged: Boolean): HttpResponse {
        val row = dao.trace(traceId) ?: throw IllegalStateException("row")
        val mf = Manifest.decodeStrict(row.manifestJson).getOrThrow()
        dir.deleteRecursively()
        return HttpResponse.json(200, buildJsonObject {
            put("traceId", traceId); put("status", row.status); put("events", mf.eventCount); put("chunks", mf.chunkCount); put("unchanged", unchanged)
        })
    }

    private fun unavailable() = HttpResponse.error(503, "unavailable", "The glasses link is not available.")

    private fun status(): HttpResponse {
        val c = collect() ?: return unavailable()
        val ctl = c.control()
        return HttpResponse.json(200, buildJsonObject { put("active", ctl.collect); put("untilMs", ctl.collectUntilMs?.let { JsonPrimitive(it) } ?: JsonNull) })
    }

    private fun start(req: HttpRequest): HttpResponse {
        val c = collect() ?: return unavailable()
        val minutes = if (req.body.isEmpty()) 20L else {
            val o = runCatching { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: throw IllegalArgumentException("body")
            val v = o["minutes"]
            if (v == null || v is JsonNull) 20L else (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("minutes")
        }
        require(minutes in 1..720) { "minutes" }
        c.start(minutes * 60_000L)
        return status()
    }

    private fun row(t: CanvasTraceEntity): JsonElement = buildJsonObject {
        put("traceId", t.traceId); put("source", t.source); put("status", t.status); put("revision", t.revision); put("exact", t.exact)
        put("startedAtMs", t.startedAtMs); put("endedAtMs", t.endedAtMs?.let { JsonPrimitive(it) } ?: JsonNull); put("createdAt", t.createdAt)
    }
}
