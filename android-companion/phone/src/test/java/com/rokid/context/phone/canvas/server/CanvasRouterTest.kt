package com.rokid.context.phone.canvas.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasRouterTest {
    private var now = 1_000_000L
    private val auth = CanvasAuth(clock = { now })
    private val hosts = HostPolicy(port = { 8765 }, lanIp = { null })
    private val apiCalls = mutableListOf<HttpRequest>()
    private val api = ApiHandler { req -> apiCalls += req; HttpResponse.json(200, buildJsonObject { put("ok", true) }) }
    private val files = mapOf("index.html" to "<html></html>", "app.js" to "x()", "style.css" to "a{}", "logo.svg" to "<svg/>")
    private val assets = AssetSource { files[it]?.toByteArray() }
    private val logs = mutableListOf<String>()
    private val router = CanvasRouter(auth, hosts, api, assets) { logs += it }

    private fun req(
        method: String = "GET", path: String = "/", host: String? = "127.0.0.1:8765",
        headers: Map<String, String> = emptyMap(), body: String = "", query: Map<String, String> = emptyMap(),
    ): HttpRequest {
        val h = buildMap { if (host != null) put("host", host); putAll(headers) }
        return HttpRequest(method, path, query, h, body.toByteArray())
    }

    private fun bearer(t: String) = mapOf("authorization" to "Bearer $t")
    private fun HttpResponse.text() = String(body, Charsets.UTF_8)
    private fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(text()).jsonObject

    @Test fun apiWithoutTokenIs401() {
        val paths = listOf("/api/session", "/api/pipelines", "/api/frames/abc/thumb", "/api/runs/1", "/api/logout", "/api", "/api/pair/")
        for (m in listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) {
            for (p in paths) {
                val r = router.handle(req(m, p))
                assertEquals("$m $p", 401, r.status)
                assertEquals("unauthorized", r.obj()["error"]!!.jsonPrimitive.content)
            }
        }
        // GET on the pairing route is not a pairing attempt and needs a token like any other.
        assertEquals(401, router.handle(req("GET", "/api/pair")).status)
        // Bad tokens and wrong schemes
        auth.newWebViewToken()
        for (h in listOf("Bearer nope", "Basic abc", "bearer", "", "Bearer ")) {
            assertEquals(h, 401, router.handle(req("GET", "/api/session", headers = mapOf("authorization" to h))).status)
        }
        assertTrue(apiCalls.isEmpty())
    }

    @Test fun validTokenReachesTheApi() {
        val t = auth.newWebViewToken()
        val r = router.handle(req("POST", "/api/pipelines", headers = bearer(t), body = "{}"))
        assertEquals(200, r.status)
        assertEquals(1, apiCalls.size)
        assertEquals("/api/pipelines", apiCalls[0].path)
        // scheme is case-insensitive
        assertEquals(200, router.handle(req("GET", "/api/x", headers = mapOf("authorization" to "bearer $t"))).status)
    }

    @Test fun apiPathsWithDotSegmentsAreRefusedAfterAuth() {
        val t = auth.newWebViewToken()
        for (p in listOf("/api/../x", "/api//x", "/api/a/./b")) {
            assertEquals(p, 400, router.handle(req("GET", p, headers = bearer(t))).status)
        }
        assertTrue(apiCalls.isEmpty())
    }

    @Test fun pairFlow() {
        val code = auth.newCode().code
        val wrong = if (code == "000000") "000001" else "000000"
        val bad = router.handle(req("POST", "/api/pair", body = """{"code":"$wrong"}"""))
        assertEquals(401, bad.status)
        assertEquals(4, bad.obj()["triesLeft"]!!.jsonPrimitive.content.toInt())
        val ok = router.handle(req("POST", "/api/pair", body = """{"code":"$code"}"""))
        assertEquals(200, ok.status)
        val token = ok.obj()["token"]!!.jsonPrimitive.content
        assertEquals(200, router.handle(req("GET", "/api/session", headers = bearer(token))).status)
        // single use
        assertEquals(401, router.handle(req("POST", "/api/pair", body = """{"code":"$code"}""")).status)
        // malformed body
        assertEquals(400, router.handle(req("POST", "/api/pair", body = "not json")).status)
        assertEquals(400, router.handle(req("POST", "/api/pair", body = """{"nope":1}""")).status)
        assertEquals(400, router.handle(req("POST", "/api/pair", body = """{"code":123456}""")).status)
        // pairing does not need a token, but the api handler never saw it
        assertTrue(apiCalls.size == 1)
    }

    @Test fun pairLockedIs429() {
        val code = auth.newCode().code
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(4) { assertEquals(401, router.handle(req("POST", "/api/pair", body = """{"code":"$wrong"}""")).status) }
        val locked = router.handle(req("POST", "/api/pair", body = """{"code":"$wrong"}"""))
        assertEquals(429, locked.status)
        assertEquals("30", locked.headers["Retry-After"])
        now += 10_000L
        assertEquals("20", router.handle(req("POST", "/api/pair", body = """{"code":"$code"}""")).headers["Retry-After"])
        assertEquals(429, router.handle(req("POST", "/api/pair", body = """{"code":"$code"}""")).status)
    }

    @Test fun pairNeedsHostAndOriginChecksToo() {
        val code = auth.newCode().code
        val body = """{"code":"$code"}"""
        assertEquals(421, router.handle(req("POST", "/api/pair", host = "evil.example:8765", body = body)).status)
        assertEquals(403, router.handle(req("POST", "/api/pair", headers = mapOf("origin" to "http://evil.example"), body = body)).status)
        // the code was never tried
        assertNotNull(auth.currentCode())
        assertEquals(200, router.handle(req("POST", "/api/pair", headers = mapOf("origin" to "http://127.0.0.1:8765"), body = body)).status)
    }

    @Test fun hostCheckComesFirst() {
        assertEquals(421, router.handle(req("GET", "/", host = "evil.example")).status)
        assertEquals(421, router.handle(req("GET", "/api/session", host = null)).status)
        val r = router.handle(req("GET", "/api/session", host = "evil.example:8765"))
        assertEquals("forbidden_host", r.obj()["error"]!!.jsonPrimitive.content)
        // host check beats the body limit
        val big = HttpRequest("POST", "/api/x", emptyMap(), mapOf("host" to "evil"), ByteArray(CanvasServer.MAX_BODY + 1))
        assertEquals(421, router.handle(big).status)
    }

    @Test fun originChecked() {
        val t = auth.newWebViewToken()
        val r = router.handle(req("POST", "/api/pipelines", headers = bearer(t) + ("origin" to "http://evil.example")))
        assertEquals(403, r.status)
        assertEquals("forbidden", r.obj()["error"]!!.jsonPrimitive.content)
        assertEquals(200, router.handle(req("POST", "/api/pipelines", headers = bearer(t) + ("origin" to "http://127.0.0.1:8765"))).status)
        assertEquals(200, router.handle(req("GET", "/api/pipelines", headers = bearer(t) + ("origin" to "http://evil.example"))).status)
        assertTrue(apiCalls.size == 2)
    }

    @Test fun staticNeedsNoToken() {
        val index = router.handle(req("GET", "/"))
        assertEquals(200, index.status)
        assertEquals("<html></html>", index.text())
        assertTrue(index.contentType.startsWith("text/html"))
        assertTrue(router.handle(req("GET", "/app.js")).contentType.startsWith("text/javascript"))
        assertTrue(router.handle(req("GET", "/style.css")).contentType.startsWith("text/css"))
        assertTrue(router.handle(req("GET", "/logo.svg")).contentType.startsWith("image/svg+xml"))
        assertEquals(200, router.handle(req("HEAD", "/app.js")).status)
        assertEquals(404, router.handle(req("GET", "/missing.js")).status)
        assertEquals(404, router.handle(req("GET", "/index.txt")).status)
    }

    @Test fun staticOnlyForReads() {
        assertEquals(405, router.handle(req("POST", "/app.js")).status)
        assertEquals(405, router.handle(req("DELETE", "/")).status)
    }

    @Test fun pathTraversalRejected() {
        val opened = mutableListOf<String>()
        val spy = CanvasRouter(auth, hosts, api, { opened += it; null })
        for (p in listOf("/../x", "/%2e%2e/x", "/a/b.js", "/..%2fx.js", "/.hidden.js", "/App.JS", "/a.js/", "//app.js", "/app.js%00.css", "/x.html?y", "/", "/index.html/..")) {
            val r = spy.handle(req("GET", p))
            if (p != "/") assertEquals(p, 404, r.status)
        }
        // "/" is the only path that reaches the source other than names matching the pattern; nothing with a slash inside does.
        assertTrue(opened.toString(), opened.all { it == "index.html" })
        assertFalse(opened.any { it.contains("/") || it.contains("..") })
    }

    @Test fun securityHeadersOnEveryResponse() {
        val t = auth.newWebViewToken()
        val responses = listOf(
            router.handle(req("GET", "/")),
            router.handle(req("GET", "/nope.js")),
            router.handle(req("GET", "/api/session")),
            router.handle(req("GET", "/api/session", headers = bearer(t))),
            router.handle(req("GET", "/", host = "evil.example")),
            router.handle(req("POST", "/api/x", headers = bearer(t) + ("origin" to "http://evil.example"))),
            router.handle(req("POST", "/api/pair", body = "x")),
            router.handle(HttpRequest("POST", "/api/x", emptyMap(), mapOf("host" to "127.0.0.1:8765"), ByteArray(CanvasServer.MAX_BODY + 1))),
        )
        for (r in responses) {
            assertEquals(
                "default-src 'self'; img-src 'self' blob:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'",
                r.headers["Content-Security-Policy"],
            )
            assertEquals("nosniff", r.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", r.headers["Referrer-Policy"])
            assertEquals("no-store", r.headers["Cache-Control"])
            assertTrue(r.headers.keys.none { it.startsWith("Access-Control-", ignoreCase = true) })
        }
    }

    @Test fun apiHandlerCannotWeakenTheHeaders() {
        val sneaky = CanvasRouter(auth, hosts, { HttpResponse(200, "text/plain", ByteArray(0), mapOf("Cache-Control" to "public", "Access-Control-Allow-Origin" to "*")) }, assets)
        val t = auth.newWebViewToken()
        val r = sneaky.handle(req("GET", "/api/x", headers = bearer(t)))
        assertEquals("no-store", r.headers["Cache-Control"])
        assertTrue(r.headers.keys.none { it.startsWith("Access-Control-", ignoreCase = true) })
    }

    @Test fun oversizeBodyIs413() {
        val t = auth.newWebViewToken()
        val just = ByteArray(CanvasServer.MAX_BODY)
        val over = ByteArray(CanvasServer.MAX_BODY + 1)
        fun post(b: ByteArray, flagged: Boolean = false) =
            router.handle(HttpRequest("POST", "/api/x", emptyMap(), mapOf("host" to "127.0.0.1:8765", "authorization" to "Bearer $t"), b, flagged))
        assertEquals(200, post(just).status)
        val r = post(over)
        assertEquals(413, r.status)
        assertEquals("too_large", r.obj()["error"]!!.jsonPrimitive.content)
        assertEquals(413, post(ByteArray(0), flagged = true).status)
        // 413 even without a token: the limit is checked before auth
        assertEquals(413, router.handle(HttpRequest("POST", "/api/pair", emptyMap(), mapOf("host" to "127.0.0.1:8765"), over)).status)
    }

    @Test fun logLineHasNoIdsOrQuery() {
        val t = auth.newWebViewToken()
        router.handle(req("GET", "/api/frames/1234567/thumb", headers = bearer(t), query = mapOf("before" to "99", "secret" to "hunter2")))
        router.handle(req("GET", "/api/pipelines/p-secret-id/versions/3/publish", headers = bearer(t)))
        router.handle(req("GET", "/api/session"))
        router.handle(req("POST", "/api/pair", body = """{"code":"654321"}"""))
        router.handle(req("GET", "/index.html"))
        router.handle(req("GET", "/some-user-controlled-name.js"))
        router.handle(req("GET", "/api/zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
        assertEquals(7, logs.size)
        val pattern = Regex("[A-Z]+ /[a-z/:.\\-]* [0-9]{3} [0-9]+ms")
        for (l in logs) assertTrue(l, pattern.matches(l))
        assertTrue(logs[0], logs[0].startsWith("GET /api/frames/:id/:id 200 "))
        assertTrue(logs[1], logs[1].startsWith("GET /api/pipelines/:id/:id/:id/:id 200 "))
        assertTrue(logs[2], logs[2].startsWith("GET /api/session 401 "))
        assertTrue(logs[3], logs[3].startsWith("POST /api/pair 401 "))
        val all = logs.joinToString("\n")
        for (leak in listOf("1234567", "secret", "hunter2", "p-secret-id", "654321", t, "before", "some-user-controlled", "zzzz")) {
            assertFalse("log leaks $leak", all.contains(leak))
        }
    }

    @Test fun unknownMethodIsNotLoggedVerbatim() {
        router.handle(req("GE\nT", "/"))
        assertFalse(logs.single().contains("\n"))
    }

    @Test fun apiExceptionBecomes500WithoutDetail() {
        val boom = CanvasRouter(auth, hosts, { throw IllegalStateException("prompt text: top secret") }, assets) { logs += it }
        val t = auth.newWebViewToken()
        val r = boom.handle(req("GET", "/api/x", headers = bearer(t)))
        assertEquals(500, r.status)
        assertFalse(r.text().contains("top secret"))
        assertFalse(logs.joinToString().contains("top secret"))
    }

    @Test fun errorBodyShape() {
        val r = HttpResponse.error(400, "bad_request", "No.", listOf("a", "b"))
        assertEquals(400, r.status)
        assertTrue(r.contentType.startsWith("application/json"))
        val o = r.obj()
        assertEquals("bad_request", o["error"]!!.jsonPrimitive.content)
        assertEquals("No.", o["message"]!!.jsonPrimitive.content)
        assertEquals(2, (o["problems"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(JsonPrimitive("x"), HttpResponse.json(200, JsonPrimitive("x")).let { Json.parseToJsonElement(it.text()) })
    }
}
