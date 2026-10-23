package com.rokid.context.phone.canvas.server

import com.rokid.context.phone.canvas.helper.HelperGrants
import com.rokid.context.phone.canvas.helper.HelperNative
import com.rokid.context.phone.canvas.helper.MemoryGrantStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP2 RED group: helper principal, routing by principal, native-only pairing and grant (no HTTP surface), section 6 pair shape. */
class HelperAuthTest {
    private var now = 1_000_000L
    private val browser = CanvasAuth(clock = { now })
    private val helper = HelperAuth(clock = { now }, log = { _, _, _ -> })
    private val grants = HelperGrants(MemoryGrantStore())
    private val nativeActions = HelperNative(helper, grants, clock = { now }, log = { _, _, _ -> })
    private val hosts = HostPolicy(port = { 8765 }, lanIp = { null })
    private val browserCalls = mutableListOf<HttpRequest>()
    private val helperCalls = mutableListOf<HttpRequest>()
    private val api = ApiHandler { req -> browserCalls += req; HttpResponse.json(200, buildJsonObject { put("ok", "browser") }) }
    private val helperApi = ApiHandler { req -> helperCalls += req; HttpResponse.json(200, buildJsonObject { put("ok", "helper") }) }
    private val assets = AssetSource { if (it == "index.html") "<html></html>".toByteArray() else null }
    private val router = CanvasRouter(browser, hosts, api, assets, helperAuth = helper, helperApi = helperApi)

    private fun req(method: String, path: String, token: String? = null, body: String = "", ip: String? = "10.0.0.7") = HttpRequest(
        method, path, emptyMap(), buildMap { put("host", "127.0.0.1:8765"); if (token != null) put("authorization", "Bearer $token") }, body.toByteArray(), remoteIp = ip,
    )

    private fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
    private fun pairBody(code: String, name: String = "my-laptop", protocol: Int = 1) = """{"helperProtocol":$protocol,"code":"$code","name":"$name"}"""

    private fun pairedHelperToken(): String {
        nativeActions.acceptPairing()
        val code = helper.pairingCode()!!.code
        val r = router.handle(req("POST", "/api/helper/pair", body = pairBody(code)))
        assertEquals(200, r.status)
        return r.obj()["token"]!!.jsonPrimitive.content
    }

    private fun browserToken(): String = (browser.pair(browser.newCode().code) as CanvasAuth.Pair.Ok).token

    // ---- principal-aware routing -------------------------------------------------------------------------------------------------------------------

    @Test fun principalForTable() {
        val expect = mapOf(
            "/api/helper/pair" to Principal.HELPER_PAIR,
            "/api/helper/poll" to Principal.HELPER,
            "/api/helper/jobs/j1/payload" to Principal.HELPER,
            "/api/helper" to Principal.HELPER,
            "/api/pair" to Principal.BROWSER_PAIR,
            "/api/pipelines" to Principal.BROWSER,
            "/api/helper-status" to Principal.BROWSER,
            "/api/helper-jobs" to Principal.BROWSER,
            "/api/helperx/poll" to Principal.BROWSER,
            "/api" to Principal.BROWSER,
            "/" to Principal.NONE,
            "/app.js" to Principal.NONE,
            "/apix/helper/poll" to Principal.NONE,
        )
        for ((p, e) in expect) assertEquals(p, e, principalFor(p))
    }

    @Test fun helperTokenRejectedOnBrowserRoutes() {
        val t = pairedHelperToken()
        // positive control: the helper token reaches helper routes
        assertEquals(200, router.handle(req("POST", "/api/helper/poll", t, "{}")).status)
        assertEquals(1, helperCalls.size)
        for (p in listOf("/api/session", "/api/pipelines", "/api/runs/1", "/api/helper-status", "/api/helper-jobs", "/api/frames/a/thumb")) {
            for (m in listOf("GET", "POST", "DELETE")) {
                val r = router.handle(req(m, p, t))
                assertEquals("$m $p", 401, r.status)
                assertEquals("unauthorized", r.obj()["error"]!!.jsonPrimitive.content)
            }
        }
        assertTrue("no browser handler ran for a helper token", browserCalls.isEmpty())
    }

    @Test fun browserTokenRejectedOnHelperRoutes() {
        val bt = browserToken()
        // positive control: the browser token reaches browser routes
        assertEquals(200, router.handle(req("GET", "/api/pipelines", bt)).status)
        assertEquals(1, browserCalls.size)
        for (p in listOf("/api/helper/poll", "/api/helper/lease", "/api/helper/jobs/j1/payload", "/api/helper/jobs/j1/heartbeat", "/api/helper/jobs/j1/result")) {
            for (m in listOf("GET", "POST")) {
                val r = router.handle(req(m, p, bt))
                assertEquals("$m $p", 401, r.status)
            }
        }
        assertTrue("no helper handler ran for a browser token", helperCalls.isEmpty())
        // a browser token is no pairing credential either
        assertEquals(401, router.handle(req("POST", "/api/helper/pair", bt, pairBody("123456"))).status)
    }

    @Test fun helperRoutesWithoutAHelperAuthRefuseEveryone() {
        val bare = CanvasRouter(browser, hosts, api, assets)
        val bt = browserToken()
        assertEquals(401, bare.handle(req("POST", "/api/helper/poll", bt, "{}")).status)
        assertEquals(404, bare.handle(req("POST", "/api/helper/pair", null, pairBody("123456"))).status)
    }

    @Test fun validHelperTokenWithNoHelperRoutesIs404() {
        val t = pairedHelperToken()
        val bare = CanvasRouter(browser, hosts, api, assets, helperAuth = helper)
        assertEquals(404, bare.handle(req("POST", "/api/helper/poll", t, "{}")).status)
    }

    @Test fun tokensAreMemoryOnlyARestartForgetsThem() {
        val t = pairedHelperToken()
        assertTrue(helper.check(t))
        val restarted = HelperAuth(clock = { now }, log = { _, _, _ -> })
        assertFalse(restarted.check(t))
    }

    // ---- pairing: native accept, section 6 shape, limiter -------------------------------------------------------------------------------------------

    @Test fun pairRequestKeepsSection6Shape() {
        nativeActions.acceptPairing()
        val code = helper.pairingCode()!!.code
        val r = router.handle(req("POST", "/api/helper/pair", body = pairBody(code, name = "mac-mini")))
        assertEquals(200, r.status)
        val o = r.obj()
        assertEquals(setOf("token", "helperId", "sessionGen", "caps", "constants"), o.keys)
        assertTrue(o["token"]!!.jsonPrimitive.content.length >= 40)
        assertTrue(o["helperId"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(1, o["sessionGen"]!!.jsonPrimitive.intOrNull)
        assertEquals(setOf("py_exec"), o["caps"]!!.jsonObject.keys)
        assertEquals(false, o["caps"]!!.jsonObject["py_exec"]!!.jsonPrimitive.booleanOrNull)
        val c = o["constants"]!!.jsonObject
        assertEquals(1, c["helperProtocol"]!!.jsonPrimitive.intOrNull)
        assertEquals(3000, c["pollIntervalIdleMs"]!!.jsonPrimitive.intOrNull)
        assertEquals(1000, c["heartbeatIntervalMs"]!!.jsonPrimitive.intOrNull)
        assertEquals("mac-mini", helper.session()!!.name)
    }

    @Test fun pairBodyIsExactlyHelperProtocolCodeName() {
        nativeActions.acceptPairing()
        val code = helper.pairingCode()!!.code
        assertEquals("extra key", 400, router.handle(req("POST", "/api/helper/pair", body = """{"helperProtocol":1,"code":"$code","name":"x","fingerprint":"ab"}""")).status)
        assertEquals("missing name", 400, router.handle(req("POST", "/api/helper/pair", body = """{"helperProtocol":1,"code":"$code"}""")).status)
        assertEquals("code not a string", 400, router.handle(req("POST", "/api/helper/pair", body = """{"helperProtocol":1,"code":123456,"name":"x"}""")).status)
        assertEquals("not json", 400, router.handle(req("POST", "/api/helper/pair", body = "nope")).status)
        assertEquals("long name", 400, router.handle(req("POST", "/api/helper/pair", body = pairBody(code, "x".repeat(65)))).status)
        assertEquals("control char in name", 400, router.handle(req("POST", "/api/helper/pair", body = pairBody(code, "a\\u0007b"))).status)
        val mismatch = router.handle(req("POST", "/api/helper/pair", body = pairBody(code, protocol = 2)))
        assertEquals(409, mismatch.status)
        assertEquals("protocol_mismatch", mismatch.obj()["error"]!!.jsonPrimitive.content)
        assertNull("none of those used the code", helper.session())
        assertEquals(200, router.handle(req("POST", "/api/helper/pair", body = pairBody(code))).status)
    }

    @Test fun pairingNeedsTheNativeAcceptFirst() {
        // nobody tapped Accept: no window, no code, even a guessed code has nothing to match
        assertNull(helper.pairingCode())
        assertNull(nativeActions.state().pairingCode)
        val r = router.handle(req("POST", "/api/helper/pair", body = pairBody("123456")))
        assertEquals(401, r.status)
        assertNull(helper.session())
        nativeActions.acceptPairing()
        val code = helper.pairingCode()!!
        assertEquals(now + 120_000L, code.expiresAt)
        now += 120_000L
        assertEquals("the window closes after pairCodeTtlMs", 401, router.handle(req("POST", "/api/helper/pair", body = pairBody(code.code))).status)
    }

    @Test fun lockoutUsesTheExtractedLimiter() {
        nativeActions.acceptPairing()
        val code = helper.pairingCode()!!.code
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(4) { assertEquals(401, router.handle(req("POST", "/api/helper/pair", body = pairBody(wrong))).status) }
        val locked = router.handle(req("POST", "/api/helper/pair", body = pairBody(wrong)))
        assertEquals(429, locked.status)
        assertEquals("refused", locked.obj()["error"]!!.jsonPrimitive.content)
        assertNotNull(locked.headers["Retry-After"])
        assertEquals("right code from a locked ip is still refused", 429, router.handle(req("POST", "/api/helper/pair", body = pairBody(code))).status)
        // another device is not locked out, and the browser limiter is a separate instance
        assertEquals(200, router.handle(req("POST", "/api/helper/pair", body = pairBody(code), ip = "10.0.0.8")).status)
        assertTrue("browser pairing untouched", browser.pair(browser.newCode().code) is CanvasAuth.Pair.Ok)
    }

    @Test fun aSecondPairingReplacesTheFirstHelper() {
        val first = pairedHelperToken()
        val second = pairedHelperToken()
        assertFalse(helper.check(first))
        assertTrue(helper.check(second))
        assertEquals(2, helper.session()!!.sessionGen)
    }

    // ---- no HTTP surface ---------------------------------------------------------------------------------------------------------------------------

    @Test fun pairingAndGrantActionsHaveNoHttpSurface() {
        val ht = pairedHelperToken()
        val bt = browserToken()
        helper.acceptPairing().also { /* a window is open: pairing is still only reachable by the code */ }
        val codeBefore = helper.pairingCode()
        val noSurface = ApiHandler { HttpResponse.error(404, "not_found", "Not found.") }
        val r = CanvasRouter(browser, hosts, noSurface, assets, helperAuth = helper, helperApi = noSurface)
        val paths = listOf(
            "/api/helper/accept", "/api/helper/accept-pairing", "/api/helper/acceptPairing", "/api/helper/grant", "/api/helper/grants", "/api/helper/grant/py_exec",
            "/api/helper/disconnect", "/api/helper-grant", "/api/helper-grants", "/api/helper-pair", "/api/helper-accept", "/api/grants", "/api/grant", "/api/pair/accept",
            "/api/helper/setGrant", "/api/helper/py_exec", "/api/helper-status",
        )
        val bodies = listOf("", """{"grant":"py_exec","on":true}""", """{"py_exec":true}""", """{"accept":true}""")
        for (p in paths) for (m in listOf("GET", "POST", "PUT", "PATCH", "DELETE")) for (tok in listOf(null, ht, bt)) for (b in bodies) {
            val res = r.handle(req(m, p, tok, b))
            assertTrue("$m $p ${if (tok == null) "anon" else "token"} answered ${res.status}", res.status in 400..499)
        }
        assertFalse("the grant never flipped", grants.isOn(HelperGrants.PY_EXEC))
        assertEquals("the pairing window is untouched", codeBefore, helper.pairingCode())
        assertTrue("the helper stays paired and nothing was disconnected", helper.check(ht))
    }

    @Test fun noRouteLiteralInTheServerSourcesAcceptsConfirmsOrTogglesGrants() {
        val dir = java.io.File("src/main/java/com/rokid/context/phone/canvas/server")
        val files = dir.listFiles { f -> f.extension == "kt" }!!.toList() + (java.io.File("src/main/java/com/rokid/context/phone/canvas/helper").listFiles()?.filter { it.name.startsWith("HelperRoutes") } ?: emptyList())
        assertTrue(files.size >= 5)
        val offending = files.filter { f -> Regex("\"/api/[^\"]*(confirm|decline|accept|grant|disconnect|pending)[^\"]*\"", RegexOption.IGNORE_CASE).containsMatchIn(f.readText()) }.map { it.name }
        assertTrue("route literals that accept, confirm or toggle: $offending", offending.isEmpty())
    }

    // ---- grant: native only ------------------------------------------------------------------------------------------------------------------------

    @Test fun grantOnRequiresNativeAction() {
        assertFalse(grants.isOn(HelperGrants.PY_EXEC))
        // pairing alone does not turn it on, and the pair response says so
        pairedHelperToken()
        assertFalse(grants.isOn(HelperGrants.PY_EXEC))
        nativeActions.setGrant(HelperGrants.PY_EXEC, true)
        assertTrue(grants.isOn(HelperGrants.PY_EXEC))
        nativeActions.setGrant(HelperGrants.PY_EXEC, false)
        assertFalse(grants.isOn(HelperGrants.PY_EXEC))
        nativeActions.setGrant("tool_build", true)
        assertFalse("an unknown grant never exists", grants.isOn("tool_build"))
    }

    @Test fun disconnectRevokesTheTokenAndTurnsTheGrantOff() {
        val t = pairedHelperToken()
        nativeActions.setGrant(HelperGrants.PY_EXEC, true)
        nativeActions.disconnectHelper()
        assertFalse(helper.check(t))
        assertNull(helper.session())
        assertFalse(grants.isOn(HelperGrants.PY_EXEC))
        assertEquals(401, router.handle(req("POST", "/api/helper/poll", t, "{}")).status)
        assertNull("the pairing window is closed too", helper.pairingCode())
    }

    @Test fun cardStateFollowsTheActions() {
        assertTrue(nativeActions.state().available)
        assertFalse(nativeActions.state().paired)
        nativeActions.acceptPairing()
        assertNotNull(nativeActions.state().pairingCode)
        pairedHelperToken()
        val s = nativeActions.state()
        assertTrue(s.paired); assertEquals("my-laptop", s.helperName); assertNull("the code is gone once used", s.pairingCode); assertFalse(s.grantPyExec)
        nativeActions.setGrant(HelperGrants.PY_EXEC, true)
        assertTrue(nativeActions.state().grantPyExec)
    }
}
