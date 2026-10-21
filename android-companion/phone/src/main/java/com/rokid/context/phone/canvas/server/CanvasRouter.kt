package com.rokid.context.phone.canvas.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

fun interface AssetSource { fun open(path: String): ByteArray? }
fun interface ApiHandler { fun handle(req: HttpRequest): HttpResponse }

/**
 * The whole request policy, in order: Host, body size, Origin, pairing, token, API, static assets.
 * Every response, errors included, carries the security headers. The log is count-only: method, route
 * template, status and duration; never ids, query, bodies, tokens or codes.
 */
class CanvasRouter(
    private val auth: CanvasAuth,
    private val hosts: HostPolicy,
    private val api: ApiHandler,
    private val assets: AssetSource,
    private val log: (String) -> Unit = {},
) {
    companion object {
        private val SECURITY_HEADERS = mapOf(
            "Content-Security-Policy" to "default-src 'self'; img-src 'self' blob:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Cache-Control" to "no-store",
        )
        private val STATIC_PATH = Regex("^/[a-z0-9_-]+\\.(html|js|css|svg)$")
        private val ROUTE_NAME = Regex("[a-z-]{1,24}")
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        private val CONTENT_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "svg" to "image/svg+xml",
        )

        /** `/api/frames/123/thumb` becomes `/api/frames/:id/:id`; anything that is not an API path is `/static`. */
        internal fun template(path: String): String {
            if (path != "/api" && !path.startsWith("/api/")) return "/static"
            val segs = path.removePrefix("/api").split('/').filter { it.isNotEmpty() }
            if (segs.isEmpty()) return "/api"
            val name = segs[0].takeIf { ROUTE_NAME.matches(it) } ?: ":id"
            return "/api/" + name + segs.drop(1).joinToString("") { "/:id" }
        }
    }

    fun handle(req: HttpRequest): HttpResponse {
        val start = System.nanoTime()
        val res = try { route(req) } catch (e: Throwable) {
            HttpResponse.error(500, "internal", "Something went wrong.") // never the exception text
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val method = req.method.uppercase().takeIf { it in METHODS } ?: "OTHER"
        log("$method ${template(req.path)} ${res.status} ${ms}ms")
        return withSecurityHeaders(res)
    }

    /** The one place security headers are applied; the server's catch path uses it too. */
    fun withSecurityHeaders(res: HttpResponse): HttpResponse =
        res.copy(headers = res.headers.filterKeys { k -> SECURITY_HEADERS.keys.none { it.equals(k, true) } && !k.startsWith("Access-Control-", true) } + SECURITY_HEADERS)

    private fun HttpResponse.copy(headers: Map<String, String>) = HttpResponse(status, contentType, body, headers)

    private fun route(req: HttpRequest): HttpResponse {
        val host = req.headers["host"]
        if (!hosts.hostAllowed(host)) return HttpResponse.error(421, "forbidden_host", "Host not allowed.")
        if (req.bodyTooLarge || req.body.size > CanvasServer.MAX_BODY) return HttpResponse.error(413, "too_large", "Request body too large.")
        if (!hosts.originAllowed(req.method, req.headers["origin"], host)) return HttpResponse.error(403, "forbidden", "Origin not allowed.")

        val isApi = req.path == "/api" || req.path.startsWith("/api/")
        if (isApi) {
            if (req.path == "/api/pair" && req.method.equals("POST", true)) return pair(req)
            if (!auth.check(bearer(req))) return HttpResponse.error(401, "unauthorized", "Authentication required.")
            val segs = req.path.split('/').drop(1)
            if (segs.any { it.isEmpty() || it == "." || it == ".." }) return HttpResponse.error(400, "bad_request", "Bad path.")
            return api.handle(req)
        }
        return static(req)
    }

    private fun bearer(req: HttpRequest): String? {
        val h = req.headers["authorization"] ?: return null
        if (h.length <= 7 || !h.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        return h.substring(7).trim().ifEmpty { null }
    }

    private fun pair(req: HttpRequest): HttpResponse {
        val code = try {
            val p = (Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject)?.get("code") as? JsonPrimitive
            p?.takeIf { it.isString }?.content
        } catch (e: Exception) { null } ?: return HttpResponse.error(400, "bad_request", "Expected {\"code\":\"123456\"}.")
        return when (val r = auth.pair(code)) {
            is CanvasAuth.Pair.Ok -> HttpResponse.json(200, buildJsonObject { put("token", r.token) })
            is CanvasAuth.Pair.Wrong -> HttpResponse.json(401, buildJsonObject {
                put("error", "unauthorized"); put("message", "Wrong code."); put("triesLeft", r.triesLeft)
            })
            is CanvasAuth.Pair.Locked -> HttpResponse.json(
                429,
                buildJsonObject { put("error", "refused"); put("message", "Pairing is locked. Try again shortly.") },
                mapOf("Retry-After" to "30"),
            )
            CanvasAuth.Pair.NoCode -> HttpResponse.error(401, "unauthorized", "No pairing code is active. Show a new one in the app.")
        }
    }

    private fun static(req: HttpRequest): HttpResponse {
        val m = req.method.uppercase()
        if (m != "GET" && m != "HEAD") return HttpResponse.error(405, "bad_request", "Method not allowed.")
        val name = if (req.path == "/") "index.html" else {
            if (!STATIC_PATH.matches(req.path)) return notFound()
            req.path.substring(1)
        }
        val bytes = assets.open(name) ?: return notFound()
        return HttpResponse(200, CONTENT_TYPES.getValue(name.substringAfterLast('.')), bytes)
    }

    private fun notFound() = HttpResponse.error(404, "not_found", "Not found.")
}
