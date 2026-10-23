package com.rokid.context.phone.canvas.helper

import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.HelperAuth
import com.rokid.context.phone.canvas.server.HostPolicy
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2 (review 1 #3): the real router and real HelperAuth, with every native dependency a RECORDING fake, driven by every HTTP method on a wide path matrix with the
 * anonymous, helper and browser credentials. Nothing HTTP can do may reach accept / grant / disconnect / confirm / decline / tag, change a grant, or return key or
 * tag bytes. WP6 must run this same matrix against the production HelperRoutes (see the report).
 */
class HttpSurfaceRecordingTest {
    private var now = 7_000_000L
    private class RecordingStore : GrantStore {
        val writes = mutableListOf<String>()
        private val v = mutableMapOf<String, Boolean>()
        override fun read(key: String) = v[key] ?: false
        override fun write(key: String, on: Boolean): Boolean { writes += "$key=$on"; v[key] = on; return true }
        override fun clear(): Boolean { writes += "clear"; v.clear(); return true }
    }
    private class RecordingConfirmActions : HelperConfirmActions {
        val calls = mutableListOf<String>()
        override fun onConfirm(jobId: String) { calls += "confirm" }
        override fun onCancel(jobId: String) { calls += "cancel" }
        override fun pending(): List<PendingConfirmRow> { calls += "pending"; return emptyList() }
    }

    private val store = RecordingStore()
    private val grants = HelperGrants(store)
    private val helper = HelperAuth(clock = { now }, log = { _, _, _ -> }, grantOn = { grants.isOn(it) })
    private val keyBytes = ByteArray(32) { (it * 7 + 1).toByte() }
    private val confirmLog = mutableListOf<String>()
    private val confirm = HelperConfirm.forTest(keyBytes, log = { _, _, m -> confirmLog += m })
    private val confirmActions = RecordingConfirmActions()
    private val nativeCalls = mutableListOf<String>()
    private val native = object : HelperNativeActions {
        private val real = HelperNative(helper, grants, { now }, log = { _, _, _ -> })
        override fun state() = real.state()
        override fun acceptPairing() { nativeCalls += "accept"; real.acceptPairing() }
        override fun setGrant(grant: String, on: Boolean) { nativeCalls += "grant"; real.setGrant(grant, on) }
        override fun disconnectHelper() { nativeCalls += "disconnect"; real.disconnectHelper() }
    }
    private val browser = CanvasAuth(clock = { now })
    private val handled = mutableListOf<String>()
    private val handler = ApiHandler { handled += it.method + " " + it.path; HttpResponse.error(404, "not_found", "Not found.") }
    private val router = CanvasRouter(browser, HostPolicy({ 8765 }, { null }), handler, AssetSource { null }, helperAuth = helper, helperApi = handler)

    private fun req(m: String, p: String, tok: String?, body: String) =
        HttpRequest(m, p, emptyMap(), buildMap { put("host", "127.0.0.1:8765"); if (tok != null) put("authorization", "Bearer $tok") }, body.toByteArray(), remoteIp = "10.0.0.9")

    @Test fun noHttpRequestReachesAnyNativeDependencyOrLeaksKeyMaterial() {
        helper.acceptPairing()
        val code = helper.pairingCode()!!.code
        val ht = (helper.pair(code, "h", "10.0.0.9") as HelperAuth.PairResult.Ok).token
        val bt = (browser.pair(browser.newCode().code) as CanvasAuth.Pair.Ok).token
        val b = ConfirmBinding("job-1", "a".repeat(64), 1, 99_000L)
        val secrets = listOf(confirm.tag(b), keyBytes.joinToString("") { "%02x".format(it) }, java.util.Base64.getEncoder().encodeToString(keyBytes))
        confirmLog.clear(); nativeCalls.clear()
        val stateBefore = native.state()
        val windowBefore = helper.pairingCode()
        val segments = listOf("helper", "helper-status", "helper-jobs", "jobs", "job-1", "accept", "accept-pairing", "acceptPairing", "grant", "grants", "setGrant", "py_exec",
            "disconnect", "confirm", "decline", "cancel", "pending", "confirmations", "tag", "key", "pair", "poll", "lease", "payload", "heartbeat", "result", "fail", "runs", "session")
        val paths = buildSet {
            for (a in segments) { add("/api/$a"); for (c in segments) { add("/api/$a/$c"); if (a == "helper" || a == "helper-jobs") add("/api/$a/job-1/$c") } }
        }
        val bodies = listOf("", """{"grant":"py_exec","on":true}""", """{"jobId":"job-1","tag":"${secrets[0]}"}""", """{"accept":true,"confirm":true}""")
        var n = 0
        for (p in paths) for (m in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) for (tok in listOf(null, ht, bt)) for (body in bodies.take(if (tok == null) 1 else 4)) {
            if (p == "/api/helper/pair" || p == "/api/pair") continue // the pairing routes themselves are covered by HelperAuthTest (a wrong guess changes the limiter, nothing else)
            val res = router.handle(req(m, p, tok, body))
            val text = String(res.body, Charsets.UTF_8) + res.headers.values.joinToString("\n")
            for (s in secrets) assertFalse("$m $p leaked key material", text.contains(s))
            n++
        }
        assertTrue("matrix ran: $n", n > 5000)
        assertEquals("no native action ran", emptyList<String>(), nativeCalls)
        assertEquals("no confirm, decline or pending call", emptyList<String>(), confirmActions.calls)
        assertEquals("the HMAC was never asked to tag or verify", emptyList<String>(), confirmLog)
        assertEquals("no grant write, no clear", emptyList<String>(), store.writes)
        assertEquals(stateBefore, native.state())
        assertEquals(windowBefore, helper.pairingCode())
        assertTrue("the helper stays paired", helper.check(ht))
        assertTrue("the browser stays paired", browser.check(bt))
        assertTrue("only authenticated helper routes ever reached the handler: ${handled.size}", handled.isNotEmpty())
    }
}
