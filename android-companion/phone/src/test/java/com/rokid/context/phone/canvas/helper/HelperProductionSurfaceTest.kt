package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.canvas.server.Principal
import com.rokid.context.phone.canvas.server.principalFor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * WP6 (Codex WP2 review 1 #3 and 2 #3): the WP2 HTTP-surface recording matrix, run against the REAL [HelperRoutes] and [HelperBrowserRoutes], the REAL adapter, native
 * actions, session, service and DAO, with RECORDING decorators on every confirm / decline / grant / pairing / key dependency. No HTTP path with any credential may confirm,
 * decline, toggle a grant, open pairing, end the session or return a key or tag; a not_confirmed 409 never carries a tag; each credential reaches only its own routes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HelperProductionSurfaceTest {
    private lateinit var h: HelperHarness
    private lateinit var helperToken: String
    private lateinit var browserToken: String

    @Before fun setUp() {
        h = HelperHarness(ApplicationProvider.getApplicationContext())
        helperToken = h.pairHelper()
        browserToken = (h.browserAuth.pair(h.browserAuth.newCode().code) as CanvasAuth.Pair.Ok).token
    }
    @After fun tearDown() { h.close() }

    @Test fun noHttpRequestWithAnyCredentialConfirmsDeclinesTogglesPairsEndsTheSessionOrLeaksKeyMaterial() {
        h.startRun()
        val id = h.awaitPendingJob()
        val info = runBlocking { h.backend.service.pendingConfirmations().first() }
        val binding = com.rokid.context.memory.canvas.ConfirmBinding(id, info.bundleHash, info.sessionGen, info.expiresAtMs)
        val tag = h.backend.confirm.tag(binding)
        val secrets = listOf(tag, info.bundleHash, id)                      // the key itself is never reachable; a tag is derived from it
        val grantWrites = h.grantStore.writes.size
        val codeBefore = h.backend.auth.pairingCode()
        val sessionBefore = h.backend.auth.session()
        val stops = h.serverStops
        val segments = listOf("helper", "helper-status", "helper-jobs", "jobs", id, "accept", "accept-pairing", "grant", "grants", "setGrant", "py_exec", "disconnect", "confirm", "decline", "cancel",
            "pending", "confirmations", "tag", "key", "poll", "lease", "payload", "heartbeat", "result", "fail", "runs", "session", "terminate", "stop")
        val paths = buildSet {
            for (a in segments) { add("/api/$a"); for (c in segments) { add("/api/$a/$c"); if (a == "helper" || a == "helper-jobs") add("/api/$a/$id/$c") } }
        }
        val bodies = listOf("", """{"grant":"py_exec","on":true}""", """{"jobId":"$id","tag":"$tag"}""", """{"helperProtocol":1,"active":[],"cleanupAcks":[],"ready":{"py_exec":true}}""", """{"leaseGen":1}""")
        val dispatched = mutableMapOf<String, MutableSet<Principal>>()
        var n = 0
        for (p in paths) for (m in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) for ((label, tok) in listOf("anon" to null, "helper" to helperToken, "browser" to browserToken)) {
            if (p == "/api/helper/pair" || p == "/api/pair") continue            // the pairing routes themselves are covered by HelperAuthTest
            for (body in bodies.take(if (tok == null) 1 else bodies.size)) {
                val res = h.call(m, p, tok, body, query = mapOf("leaseGen" to "1"))
                val text = String(res.body, Charsets.UTF_8)
                for (s in secrets) assertFalse("$label $m $p leaks a secret", text.contains(s))
                for (hv in res.headers.values) for (s in secrets) assertFalse("$label $m $p header leaks a secret", hv.contains(s))
                if (res.status in 200..299 && tok != null) dispatched.getOrPut(label) { mutableSetOf() } += principalFor(p)
                n++
            }
        }
        assertTrue("matrix ran $n requests", n > 20_000)
        assertTrue("no confirm reached the port: ${h.port.confirms}", h.port.confirms.isEmpty())
        assertTrue("no decline reached the port: ${h.port.declines}", h.port.declines.isEmpty())
        assertEquals("no grant write", grantWrites, h.grantStore.writes.size)
        assertEquals("no pairing window opened", codeBefore?.code, h.backend.auth.pairingCode()?.code)
        assertEquals("the helper session is untouched", sessionBefore, h.backend.auth.session())
        assertEquals("no session end", stops, h.serverStops)
        assertTrue("the job is still waiting for the tap", runBlocking { h.backend.service.awaitingConfirm(id) })
        assertTrue("helper credential reached only helper routes: ${dispatched["helper"]}", dispatched["helper"].orEmpty().all { it == Principal.HELPER })
        assertTrue("browser credential reached only browser routes: ${dispatched["browser"]}", dispatched["browser"].orEmpty().all { it == Principal.BROWSER })
        assertTrue("both credentials did reach their routes", dispatched["helper"].orEmpty().isNotEmpty() && dispatched["browser"].orEmpty().isNotEmpty())
        assertTrue("anonymous reached nothing", dispatched["anon"].orEmpty().isEmpty())
    }

    @Test fun theStatusRouteReportsAwaitingConfirmAndExpiryAndNeverAControlOrAnId() {
        h.startRun()
        val id = h.awaitPendingJob()
        val r = h.call("GET", "/api/helper-status", browserToken)
        assertEquals(200, r.status)
        val o = h.json(r)
        assertEquals(setOf("paired", "grants", "ready", "readyStale", "awaitingConfirm", "confirmExpiresAtMs", "serverTimeMs"), o.keys)
        assertEquals("the phone's clock, so the page counts down without trusting its own", h.now.toString(), (o["serverTimeMs"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("1", (o["awaitingConfirm"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals((h.now + com.rokid.context.canvas.helper.HelperProtocol.CONFIRM_TTL_MS).toString(), (o["confirmExpiresAtMs"] as kotlinx.serialization.json.JsonPrimitive).content)
        val text = String(r.body, Charsets.UTF_8)
        assertFalse(text.contains(id)); assertFalse(text.contains("tag")); assertFalse(text.contains("key"))
        // the helper principal cannot read it
        assertEquals(401, h.call("GET", "/api/helper-status", helperToken).status)
    }

    @Test fun theBrowserHelperJobsRouteRejectsSubmissionOfPyExec() {
        val r = h.call("POST", "/api/helper-jobs", browserToken, """{"jobType":"py_exec","source":"def run(x): return x","items":[]}""")
        assertEquals(403, r.status)
        assertEquals("forbidden", h.json(r)["error"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(runBlocking { h.backend.service.pendingConfirmations() }.isEmpty())
        assertEquals(401, h.call("POST", "/api/helper-jobs", helperToken, "{}").status)
    }

    @Test fun notConfirmedAnswersNeverCarryATag() {
        h.startRun()
        val id = h.awaitPendingJob()
        val info = runBlocking { h.backend.service.pendingConfirmations().first() }
        val tag = h.backend.confirm.tag(com.rokid.context.memory.canvas.ConfirmBinding(id, info.bundleHash, info.sessionGen, info.expiresAtMs))
        for (r in listOf(h.payload(helperToken, id, 1), h.heartbeat(helperToken, id, 1), h.result(helperToken, id, 1, h.okResult()))) {
            assertEquals(409, r.status)
            assertFalse(String(r.body, Charsets.UTF_8).contains(tag))
            assertEquals(setOf("error", "message", "problems"), h.json(r).keys)
        }
    }

    @Test fun theRoutesHoldNoConfirmDeclineGrantOrKeyReference() {
        val base = java.io.File("src/main/java/com/rokid/context/phone/canvas/helper")
        for (n in listOf("HelperRoutes.kt", "HelperBrowserRoutes.kt")) {
            val t = java.io.File(base, n).readText()
            assertFalse("$n imports the adapter", t.contains("HelperConfirmAdapter"))
            assertFalse("$n imports the grant writers", Regex("""\.(set|allOff)\(""").containsMatchIn(t))
        }
        assertTrue(Base64.getEncoder().encodeToString(ByteArray(1)).isNotEmpty())
    }
}
