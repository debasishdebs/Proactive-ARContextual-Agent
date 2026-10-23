package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.helper.ConfirmResult
import com.rokid.context.canvas.helper.HelperProtocol
import com.rokid.context.memory.canvas.ConfirmBinding
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * WP9 per-run confirmation privacy suite (user decision 2026-10-23 evening), through the PRODUCTION browser API and the REAL route chain with the fake helper
 * ([BrowserRunKit]): the only way to confirm is the native tap, bound to one job hash, one session and one expiry, and a run the user did not tap never leaves the phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerRunConfirmPrivacyTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var k: BrowserRunKit
    private lateinit var pipeline: String
    private val service get() = k.h.backend.service

    @Before fun setUp() {
        k = BrowserRunKit(ApplicationProvider.getApplicationContext(), tmp.root)
        k.seedFrame("f1", 1_000)
        pipeline = k.savePythonPipeline()
    }
    @After fun tearDown() { k.close() }

    private class Started(val runId: String, val jobId: String, val binding: ConfirmBinding)

    /** A run started through the browser API that now waits for its tap. */
    private fun startPending(): Started {
        val runId = k.startRun(pipeline, listOf("f1"))
        val job = k.awaitPending()
        val i = runBlocking { service.pendingConfirmations().single { it.jobId == job } }
        return Started(runId, job, ConfirmBinding(job, i.bundleHash, i.sessionGen, i.expiresAtMs))
    }

    private fun assertStillWaiting(s: Started) {
        assertEquals("AWAITING_CONFIRM", k.jobState(s.jobId))
        assertEquals(listOf(s.jobId), runBlocking { service.pendingConfirmations() }.map { it.jobId })
        assertTrue("the port's confirm was never called", k.h.port.confirms.isEmpty())
        assertTrue("the port's decline was never called", k.h.port.declines.isEmpty())
        assertNull("nothing is leasable", k.helperLease())
    }

    private fun fnError(runId: String) = k.fnItems(runId).map { it["error"]!!.jsonPrimitive.content }

    // ---- no HTTP surface ----

    @Test fun noHttpConfirmSurface() {
        val s = startPending()
        val real = k.h.backend.confirm.tag(s.binding)
        val tokens = listOf<Pair<String, String?>>("none" to null, "browser" to k.browserToken, "helper" to k.helperToken, "junk" to "x".repeat(32))
        val job = s.jobId
        val paths = listOf(
            "/api/helper-status", "/api/helper-jobs", "/api/helper-jobs/$job", "/api/helper-jobs/$job/confirm", "/api/helper-jobs/$job/decline", "/api/helper-jobs/pending",
            "/api/helper-confirm", "/api/helper-pending", "/api/helper-grants", "/api/confirm", "/api/pending", "/api/jobs", "/api/jobs/$job/confirm",
            "/api/helper", "/api/helper/", "/api/helper/status", "/api/helper/pending", "/api/helper/confirm", "/api/helper/confirm/$job", "/api/helper/jobs", "/api/helper/jobs/pending",
            "/api/helper/jobs/$job", "/api/helper/jobs/$job/confirm", "/api/helper/jobs/$job/decline", "/api/helper/jobs/$job/cancel", "/api/helper/grants", "/api/helper/grant",
            "/api/helper/poll", "/api/helper/lease", "/api/helper/jobs/$job/payload", "/api/helper/jobs/$job/heartbeat", "/api/helper/jobs/$job/result", "/api/helper/jobs/$job/fail",
            "/api/runs/${s.runId}/confirm", "/api/runs/${s.runId}/decline", "/api/runs/${s.runId}", "/api/runs", "/api/node-types", "/api/session",
        )
        val bodies = listOf(
            "", "{}",
            """{"confirm":true,"decision":"confirm","jobId":"$job","tag":"$real","bundleHash":"${s.binding.bundleHash}","sessionGen":${s.binding.sessionGen},"expiresAtMs":${s.binding.expiresAtMs}}""",
            """{"helperProtocol":1,"types":["py_exec"],"active":[],"cleanupAcks":[],"ready":{"py_exec":true},"leaseGen":0,"code":"unknown"}""",
        )
        class Answer(val who: String, val method: String, val path: String, val r: com.rokid.context.phone.canvas.server.HttpResponse)
        val answers = mutableListOf<Answer>()
        for ((who, t) in tokens) for (m in listOf("GET", "POST", "PUT", "DELETE", "PATCH")) for (p in paths) for (b in bodies) {
            if (m == "GET" && b.isNotEmpty()) continue
            answers += Answer(who, m, p, k.router.handle(k.h.req(m, p, t, b, if (p.contains("payload")) mapOf("leaseGen" to "0") else emptyMap())))
        }
        assertStillWaiting(s)
        fun isHelperPath(p: String) = p == "/api/helper" || p.startsWith("/api/helper/")
        for (a in answers) {
            val what = "${a.who} ${a.method} ${a.path}"
            val body = String(a.r.body, Charsets.UTF_8)
            // nothing lists pending jobs: no answer to anyone names the job, its hash or the tag
            assertFalse("$what leaked the job id", body.contains(job))
            assertFalse("$what leaked the bundle hash", body.contains(s.binding.bundleHash))
            assertFalse("$what leaked a tag", body.contains(real))
            assertTrue("$what is a 5xx", a.r.status < 500)
            // a route that sounds like a confirmation never succeeds, whoever asks
            if (a.r.status in 200..299) assertFalse("$what answered 2xx", Regex("confirm|decline|pending|grant").containsMatchIn(a.path.removePrefix("/api")))
            // principals stay apart
            if (a.who == "none" || a.who == "junk") assertEquals(what, 401, a.r.status)
            if (a.who == "browser" && isHelperPath(a.path)) assertEquals(what, 401, a.r.status)
            if (a.who == "helper" && !isHelperPath(a.path)) assertEquals(what, 401, a.r.status)
        }
        assertTrue(answers.any { it.who == "browser" && it.path == "/api/helper-status" && it.r.status == 200 })
        // the one status route reports a count and an expiry, nothing else
        val status = k.ok(k.browser("GET", "/api/helper-status"))
        assertEquals(setOf("paired", "grants", "ready", "readyStale", "awaitingConfirm", "confirmExpiresAtMs", "serverTimeMs"), status.keys)
        assertEquals("1", status["awaitingConfirm"]!!.jsonPrimitive.content)
        // and the one legitimate path still works: the native tap
        k.tap(s.jobId)
        assertEquals("QUEUED", k.jobState(s.jobId))
    }

    @Test fun browserAndHelperTokensCannotConfirm() {
        val s = startPending()
        val real = k.h.backend.confirm.tag(s.binding)
        // forged and replayed tags are refused by the verifier ...
        val forged = listOf("0".repeat(64), "f".repeat(64), real.uppercase(), real.dropLast(1) + (if (real.last() == '0') '1' else '0'), "", "not a tag", real + "00")
        for (f in forged) assertFalse("forged '$f'", k.h.backend.confirm.verify(s.binding, f, k.h.now))
        assertTrue("the real tag verifies (the key is live)", k.h.backend.confirm.verify(s.binding, real, k.h.now))
        // ... and carrying even the REAL tag in every place a request can hold one changes nothing, with either token
        for (token in listOf(k.browserToken, k.helperToken)) for (p in listOf("/api/helper-status", "/api/helper-jobs", "/api/helper/poll", "/api/helper/lease", "/api/helper/jobs/${s.jobId}/payload", "/api/helper/jobs/${s.jobId}/heartbeat", "/api/helper/jobs/${s.jobId}/result", "/api/runs/${s.runId}")) {
            val body = """{"tag":"$real","confirmTag":"$real","jobId":"${s.jobId}","helperProtocol":1,"types":["py_exec"],"leaseGen":0,"resultHash":"$real","result":{}}"""
            k.router.handle(com.rokid.context.phone.canvas.server.HttpRequest("POST", p, mapOf("tag" to real, "leaseGen" to "0"), mapOf("host" to "127.0.0.1:8765", "authorization" to "Bearer $token", "x-confirm-tag" to real, "x-tag" to real), body.toByteArray(), remoteIp = "10.0.0.9"))
        }
        assertStillWaiting(s)
        // a payload request for the waiting job releases nothing and names the reason
        val payload = k.helperPayload(s.jobId, 0)
        assertEquals(409, payload.status)
        assertEquals("not_confirmed", k.json(payload)["error"]!!.jsonPrimitive.content)
        // a replay in a later session: the key rotates at every dev-session start, the old tag stops verifying
        k.h.backend.confirm.rotate()
        assertFalse("a tag of an earlier session is refused", k.h.backend.confirm.verify(s.binding, real, k.h.now))
    }

    @Test fun confirmBoundToJobHash() {
        val a = startPending()
        val tagA = k.h.backend.confirm.tag(a.binding)
        val b = a.binding
        val wrong = listOf(
            "another bundle hash" to b.copy(bundleHash = "0".repeat(64)),
            "another session" to b.copy(sessionGen = b.sessionGen + 1),
            "another expiry" to b.copy(expiresAtMs = b.expiresAtMs - 1),
            "another job" to b.copy(jobId = "job-other"),
        )
        for ((what, binding) in wrong) {
            assertNotEquals("$what must not confirm", ConfirmResult.CONFIRMED, runBlocking { service.confirm(binding.jobId, binding) })
            assertFalse("$what: the tag of the real binding does not verify", k.h.backend.confirm.verify(binding, tagA, k.h.now))
        }
        assertStillWaiting(a)
        // a tag minted for job A does not confirm the NEXT job B
        k.h.backend.adapter.onCancel(a.jobId); runBlocking { k.h.backend.adapter.awaitIdle() }
        k.awaitStatus(a.runId, "DONE", "CANCELLED")
        val second = startPending()
        assertNotEquals(a.jobId, second.jobId)
        assertFalse(k.h.backend.confirm.verify(second.binding, tagA, k.h.now))
        assertNotEquals(ConfirmResult.CONFIRMED, runBlocking { service.confirm(second.jobId, a.binding) })
        assertNotEquals(ConfirmResult.CONFIRMED, runBlocking { service.confirm(a.jobId, second.binding) })
        assertEquals("AWAITING_CONFIRM", k.jobState(second.jobId))
        // the exact binding still works
        assertEquals(ConfirmResult.CONFIRMED, runBlocking { service.confirm(second.jobId, second.binding) })
    }

    @Test fun confirmExpiryRefused() {
        val s = startPending()
        k.h.now += HelperProtocol.CONFIRM_TTL_MS
        assertNull("the tap after the window is refused before the port", runBlocking { k.h.backend.adapter.confirmNow(s.jobId) })
        assertNotEquals(ConfirmResult.CONFIRMED, runBlocking { service.confirm(s.jobId, s.binding) })
        assertTrue(k.h.port.confirms.isEmpty())
        k.awaitStatus(s.runId, "DONE", "CANCELLED")
        assertEquals(listOf("not_confirmed"), fnError(s.runId))
        assertNull("an expired job is never leased", k.helperLease())
        assertEquals("0", k.ok(k.browser("GET", "/api/helper-status"))["awaitingConfirm"]!!.jsonPrimitive.content)
    }

    @Test fun grantOnButNoConfirmNeverRunsJob() {
        assertEquals("the grant is on", "true", k.ok(k.browser("GET", "/api/helper-status"))["grants"]!!.jsonObject["py_exec"]!!.jsonPrimitive.content)
        val s = startPending()
        val seen = StringBuilder()
        repeat(6) {
            val poll = k.h.poll(k.helperToken); seen.append(String(poll.body))
            assertEquals("nothing is available to the laptop", 0, k.json(poll)["available"]!!.jsonArray.size)
            val lease = k.h.lease(k.helperToken); seen.append(String(lease.body))
            assertTrue(k.json(lease)["job"] is JsonNull)
            seen.append(String(k.helperPayload(s.jobId, 0).body)); seen.append(String(k.h.heartbeat(k.helperToken, s.jobId, 0).body))
            seen.append(String(k.helperResult(s.jobId, 0).body))
        }
        for (secret in listOf("def run", "return", "items", "desk", s.binding.bundleHash)) assertFalse("'$secret' left the phone", seen.contains(secret))
        assertStillWaiting(s)
        // the window ends without a tap: the job fails, still never leased, the run says why
        k.h.now += HelperProtocol.CONFIRM_TTL_MS
        k.awaitStatus(s.runId, "DONE", "CANCELLED")
        assertEquals(listOf("not_confirmed"), fnError(s.runId))
        assertNull(k.helperLease())
        assertTrue(k.h.port.confirms.isEmpty())
        assertEquals("the grant itself did not run anything and is untouched", "true", k.ok(k.browser("GET", "/api/helper-status"))["grants"]!!.jsonObject["py_exec"]!!.jsonPrimitive.content)
    }

    @Test fun staleConfirmAfterForgetRefused() {
        val s = startPending()
        val tag = k.h.backend.confirm.tag(s.binding)
        assertTrue(k.forget("f1").framesToDelete.isNotEmpty())
        assertEquals("CANCELLED", k.awaitStatus(s.runId, "STOPPED_FORGET", "DONE", "CANCELLED"))
        // the card's tap, the adapter and the port are all too late; the tag is still genuine, the job is not
        assertTrue("the tag itself is genuine", k.h.backend.confirm.verify(s.binding, tag, k.h.now))
        assertNull(runBlocking { k.h.backend.adapter.confirmNow(s.jobId) })
        assertNotEquals(ConfirmResult.CONFIRMED, runBlocking { service.confirm(s.jobId, s.binding) })
        assertEquals("VOIDED", k.jobState(s.jobId))
        assertNull(k.helperLease())
        assertEquals(409, k.helperPayload(s.jobId, 0).status)
        assertTrue("the voided run stored nothing", k.fnItems(s.runId).isEmpty())
    }

    @Test fun browserCancelEndsTheRunAndNeverConfirms() {
        val s = startPending()
        assertEquals(200, k.browser("POST", "/api/runs/${s.runId}/cancel").status)
        k.awaitStatus(s.runId, "CANCELLED", "DONE")
        assertTrue(k.h.port.confirms.isEmpty())
        assertNull(k.helperLease())
        assertNotEquals("QUEUED", k.jobState(s.jobId))
    }

    // ---- the page: status only ----

    @Test fun uiShowsAwaitingConfirmDeclinedExpired() {
        // awaiting: a count and an expiry on the phone's clock (the page counts down from it)
        val a = startPending()
        val waiting = k.ok(k.browser("GET", "/api/helper-status"))
        assertEquals("1", waiting["awaitingConfirm"]!!.jsonPrimitive.content)
        assertEquals((k.h.now + HelperProtocol.CONFIRM_TTL_MS).toString(), waiting["confirmExpiresAtMs"]!!.jsonPrimitive.content)
        assertEquals(k.h.now.toString(), waiting["serverTimeMs"]!!.jsonPrimitive.content)
        assertEquals("RUNNING", k.runStatus(a.runId))
        // declined on the phone: the run shows it and the status no longer waits
        k.h.backend.adapter.onCancel(a.jobId); runBlocking { k.h.backend.adapter.awaitIdle() }
        k.awaitStatus(a.runId, "DONE", "CANCELLED")
        assertEquals(listOf("not_confirmed"), fnError(a.runId))
        assertEquals("0", k.ok(k.browser("GET", "/api/helper-status"))["awaitingConfirm"]!!.jsonPrimitive.content)
        // expired: the same fixed code, nothing was sent
        val b = startPending()
        k.h.now += HelperProtocol.CONFIRM_TTL_MS
        k.awaitStatus(b.runId, "DONE", "CANCELLED")
        assertEquals(listOf("not_confirmed"), fnError(b.runId))
        assertEquals("0", k.ok(k.browser("GET", "/api/helper-status"))["awaitingConfirm"]!!.jsonPrimitive.content)
        // the page that shows these states is served by the real router and holds the texts, and no control that could confirm (the DOM check is HelperUiContractTest)
        val js = k.router.handle(k.h.req("GET", "/helperview.js", null))
        assertEquals(200, js.status)
        val src = String(js.body, Charsets.UTF_8)
        assertTrue(src.contains("Waiting for your tap on the phone"))
        assertFalse("the page sends no write", Regex("""api\(\s*['"](POST|PUT|PATCH|DELETE)""").containsMatchIn(src))
        assertFalse(File("src/main/assets/canvas").listFiles()!!.filter { it.isFile && !it.name.startsWith("vendor-") }.any { Regex("""/api/helper(-jobs)?/[^'"\s]*confirm""").containsMatchIn(it.readText()) })
    }
}
