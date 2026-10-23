package com.rokid.context.phone.canvas.server

import com.rokid.context.canvas.helper.HelperProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

fun interface AssetSource { fun open(path: String): ByteArray? }
fun interface ApiHandler { fun handle(req: HttpRequest): HttpResponse }

/** Who a request path belongs to. A token is only ever accepted for its own principal's routes. */
enum class Principal {
    /** Not an API path: static assets, no token. */
    NONE,
    /** every `/api/` route except the helper's own: the browser's bearer session. */
    BROWSER,
    /** `/api/pair`: the browser's pairing code (a token for every other method). */
    BROWSER_PAIR,
    /** `/api/helper` and everything under `/api/helper/`: the laptop helper's token. */
    HELPER,
    /** `/api/helper/pair`: the helper's pairing code, opened by the native Accept tap (a token for every other method). */
    HELPER_PAIR,
}

/**
 * The single principal predicate of the request policy (3b WP2; always-on LGC11 puts `canTransfer()` in this file, whoever lands second rebases). The helper owns exactly
 * the path segment `helper`: `/api/helper-status` and `/api/helper-jobs` (WP6) are browser routes, and `/api/helperx` is nothing special.
 */
fun principalFor(path: String): Principal {
    if (path != "/api" && !path.startsWith("/api/")) return Principal.NONE
    val segs = path.split('/').drop(2) // after the empty segment and "api"; empty segments stay, so only the exact pairing paths are pairing paths
    return when {
        segs == listOf("pair") -> Principal.BROWSER_PAIR
        segs == listOf("helper", "pair") -> Principal.HELPER_PAIR
        segs.firstOrNull() == "helper" -> Principal.HELPER
        else -> Principal.BROWSER
    }
}

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
    /** The laptop helper's identity; null = no helper support (every helper route refuses). */
    private val helperAuth: HelperAuth? = null,
    /** The authenticated routes under `/api/helper/` (WP6's HelperRoutes); null = a valid helper token meets 404. */
    private val helperApi: ApiHandler? = null,
    private val log: (String) -> Unit = {},
) {
    companion object {
        private val SECURITY_HEADERS = mapOf(
            "Content-Security-Policy" to "default-src 'self'; img-src 'self' blob:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Cache-Control" to "no-store",
        )
        internal val STATIC_PATH = Regex("^/[a-z0-9_-]+\\.(html|js|css|svg)$")
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
            val principal = principalFor(req.path)
            val post = req.method.equals("POST", true)
            if (principal == Principal.BROWSER_PAIR && post) return pair(req)
            if (principal == Principal.HELPER_PAIR && post) return helperPair(req)
            val helperRoute = principal == Principal.HELPER || principal == Principal.HELPER_PAIR
            val authed = if (helperRoute) helperAuth?.check(bearer(req)) == true else auth.check(bearer(req))
            if (!authed) return HttpResponse.error(401, "unauthorized", "Authentication required.")
            val segs = req.path.split('/').drop(1)
            if (segs.any { it.isEmpty() || it == "." || it == ".." }) return HttpResponse.error(400, "bad_request", "Bad path.")
            return if (helperRoute) helperApi?.handle(req) ?: notFound() else api.handle(req)
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
        return when (val r = auth.pair(code, req.remoteIp)) {
            is CanvasAuth.Pair.Ok -> HttpResponse.json(200, buildJsonObject { put("token", r.token) })
            is CanvasAuth.Pair.Wrong -> HttpResponse.json(401, buildJsonObject {
                put("error", "unauthorized"); put("message", "Wrong code."); put("triesLeft", r.triesLeft)
            })
            is CanvasAuth.Pair.Locked -> HttpResponse.json(
                429,
                buildJsonObject { put("error", "refused"); put("message", "Pairing is locked. Tap New code in the app.") },
                mapOf("Retry-After" to auth.retryAfterSeconds(req.remoteIp).coerceAtLeast(1).toString()),
            )
            CanvasAuth.Pair.NoCode -> HttpResponse.error(401, "unauthorized", "No pairing code is active. Show a new one in the app.")
        }
    }

    /**
     * The answer's `constants` object is the SUBSET of constants.json the helper needs to start (ten numbers: helperProtocol, pollIntervalIdleMs, heartbeatIntervalMs,
     * heartbeatRequestTimeoutMs, leaseMs, helperSelfKillMs, pyExecDeadlineMs, confirmTtlMs, maxPayloadBytes, maxResultBytes); `caps` maps each known grant to whether it is on now.
     * `POST /api/helper/pair` `{helperProtocol, code, name}` (section 6; nothing else is read, no fingerprint: the helper pins the certificate before it sends anything).
     * The code exists only while the user's native "Accept pairing" tap has a window open.
     */
    private fun helperPair(req: HttpRequest): HttpResponse {
        val h = helperAuth ?: return notFound()
        val bad = HttpResponse.error(400, "bad_request", "Expected {\"helperProtocol\":1,\"code\":\"123456\",\"name\":\"...\"}.")
        val o = try { Json.parseToJsonElement(String(req.body, Charsets.UTF_8)) as? JsonObject } catch (e: Exception) { null } ?: return bad
        if (o.keys != setOf("helperProtocol", "code", "name")) return bad
        val proto = (o["helperProtocol"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return bad
        val code = (o["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return bad
        val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return bad
        if (name.isEmpty() || name.length > 64 || name.any { it.isISOControl() }) return bad
        if (proto != HelperProtocol.HELPER_PROTOCOL) return HttpResponse.error(409, "protocol_mismatch", "Unsupported helper protocol.")
        return when (val r = h.pair(code, name, req.remoteIp)) {
            is HelperAuth.PairResult.Ok -> HttpResponse.json(200, buildJsonObject {
                put("token", r.token)
                put("helperId", r.session.helperId)
                put("sessionGen", r.session.sessionGen)
                put("caps", buildJsonObject { for ((k, v) in h.caps()) put(k, v) })
                put("constants", buildJsonObject {
                    put("helperProtocol", HelperProtocol.HELPER_PROTOCOL)
                    put("pollIntervalIdleMs", HelperProtocol.POLL_INTERVAL_IDLE_MS)
                    put("heartbeatIntervalMs", HelperProtocol.HEARTBEAT_INTERVAL_MS)
                    put("heartbeatRequestTimeoutMs", HelperProtocol.HEARTBEAT_REQUEST_TIMEOUT_MS)
                    put("leaseMs", HelperProtocol.LEASE_MS)
                    put("helperSelfKillMs", HelperProtocol.HELPER_SELF_KILL_MS)
                    put("pyExecDeadlineMs", HelperProtocol.PY_EXEC_DEADLINE_MS)
                    put("confirmTtlMs", HelperProtocol.CONFIRM_TTL_MS)
                    put("maxPayloadBytes", HelperProtocol.MAX_PAYLOAD_BYTES)
                    put("maxResultBytes", HelperProtocol.MAX_RESULT_BYTES)
                })
            })
            is HelperAuth.PairResult.Wrong -> HttpResponse.json(401, buildJsonObject {
                put("error", "unauthorized"); put("message", "Wrong code."); put("triesLeft", r.triesLeft)
            })
            is HelperAuth.PairResult.Locked -> HttpResponse.json(
                429,
                buildJsonObject { put("error", "refused"); put("message", "Pairing is locked. Tap Accept pairing in the app.") },
                mapOf("Retry-After" to h.retryAfterSeconds(req.remoteIp).coerceAtLeast(1).toString()),
            )
            HelperAuth.PairResult.NoCode -> HttpResponse.error(401, "unauthorized", "No pairing is open. Tap Accept pairing in the app.")
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
