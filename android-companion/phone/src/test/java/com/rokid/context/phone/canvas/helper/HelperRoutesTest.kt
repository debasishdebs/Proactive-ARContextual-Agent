package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.helper.PyOutcome
import com.rokid.context.memory.canvas.HelperDeletionCause
import com.rokid.context.phone.canvas.server.CanvasServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP6 RED group: the helper routes of section 6, driven through the REAL router and the production wiring ([HelperBackend.create]) over an in-memory Room database:
 * pair, poll, lease, payload, heartbeat, result, fail against the WP1 fixtures; every denial code; the deletion table; the body limit of `result`;
 * the wire representation of `cleanupId` (a decimal STRING: the WP11 helper reads `cleanupId` as a str and echoes it in `cleanupAcks`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HelperRoutesTest {
    private lateinit var h: HelperHarness
    private lateinit var token: String

    @Before fun setUp() { h = HelperHarness(ApplicationProvider.getApplicationContext()); token = h.pairHelper() }
    @After fun tearDown() { h.close() }

    private val constants: JsonObject = run {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "tools/canvas-helper/protocol").isDirectory) d = d.parentFile
        Json.parseToJsonElement(File(requireNotNull(d), "tools/canvas-helper/protocol/constants.json").readText()).jsonObject
    }

    private fun long(o: JsonObject, k: String) = (o[k] as JsonPrimitive).content.toLong()

    // ---- the whole flow ----

    @Test fun pairPollLeasePayloadHeartbeatResultCompletesTheRun() {
        val run = h.startRun()
        val jobId = h.awaitPendingJob()
        // unconfirmed: the helper learns nothing
        assertEquals(JsonArray(emptyList()), h.json(h.poll(token))["available"])
        assertTrue(h.json(h.lease(token))["job"] is kotlinx.serialization.json.JsonNull)
        assertEquals(com.rokid.context.canvas.helper.ConfirmResult.CONFIRMED, h.tap(jobId))
        val p = h.json(h.poll(token))
        assertEquals(setOf("sessionGen", "caps", "serverTimeMs", "available", "cancel", "cleanup"), p.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("py_exec"))), p["available"])
        assertEquals(true, (p["caps"]!!.jsonObject["py_exec"] as JsonPrimitive).content.toBoolean())
        val lease = h.json(h.lease(token))
        assertEquals(setOf("job", "serverTimeMs"), lease.keys)
        val j = lease["job"]!!.jsonObject
        assertEquals(setOf("jobId", "type", "leaseGen", "leaseUntilMs", "deadlineMs", "bundleHash", "payloadBytes"), j.keys)
        assertEquals(jobId, j["jobId"]!!.jsonPrimitive.content)
        assertEquals("py_exec", j["type"]!!.jsonPrimitive.content)
        val gen = long(j, "leaseGen")
        val pay = h.payload(token, jobId, gen)
        assertEquals(200, pay.status)
        assertEquals(long(j, "payloadBytes").toInt(), pay.body.size)
        val payloadJson = Json.parseToJsonElement(String(pay.body, Charsets.UTF_8)).jsonObject
        assertEquals(setOf("helperProtocol", "jobType", "source", "entry", "items", "outSchema", "limits"), payloadJson.keys)
        val hb = h.json(h.heartbeat(token, jobId, gen))
        assertEquals(setOf("leaseUntilMs", "serverTimeMs"), hb.keys)
        val res = h.result(token, jobId, gen, h.okResult())
        assertEquals(200, res.status)
        assertEquals(setOf("committed", "duplicate"), h.json(res).keys)
        val outcome = runBlocking { run.await() }
        assertTrue("$outcome", outcome is PyOutcome.Done)
        // an identical repeat is idempotent
        val again = h.json(h.result(token, jobId, gen, h.okResult()))
        assertEquals("true", (again["duplicate"] as JsonPrimitive).content)
    }

    @Test fun failEnumCodeEndsTheRunAndAnUnknownCodeIsRefused() {
        val run = h.startRun()
        val (id, gen) = h.confirmAndLease(token)
        assertEquals(400, h.fail(token, id, gen, "made_up").status)
        assertEquals(200, h.fail(token, id, gen, "timeout").status)
        assertEquals(PyOutcome.Failed("timeout"), runBlocking { run.await() })
    }

    @Test fun heartbeatPayloadAndResultAnswer409NotConfirmedWhileAwaitingAndLeaseNeverReturnsIt() {
        h.startRun()
        val id = h.awaitPendingJob()
        for (r in listOf(h.payload(token, id, 1), h.heartbeat(token, id, 1), h.result(token, id, 1, h.okResult()))) {
            assertEquals(409, r.status)
            assertEquals("not_confirmed", h.json(r)["error"]!!.jsonPrimitive.content)
        }
        val l = h.json(h.lease(token))
        assertTrue("lease never returns an unconfirmed job", l["job"] is kotlinx.serialization.json.JsonNull)
    }

    @Test fun anyNotConfirmed409NeverCarriesATagKeyOrHash() {
        h.startRun()
        val id = h.awaitPendingJob()
        val binding = runBlocking { h.backend.service.pendingConfirmations().first() }
        val secrets = listOf(binding.bundleHash, id.removePrefix("hj_"))
        val body = String(h.heartbeat(token, id, 1).body, Charsets.UTF_8)
        for (s in secrets) assertFalse("409 body leaks '${s.take(4)}..'", s in body)
        assertEquals(setOf("error", "message", "problems"), Json.parseToJsonElement(body).jsonObject.keys)
    }

    // ---- poll shape, cleanup wire representation, cancel ----

    @Test fun cleanupIdIsADecimalStringOnTheWireAndAcksDeleteOnlyTheNamedRows() {
        val run = h.startRun()
        val (id, gen) = h.confirmAndLease(token)
        assertEquals(200, h.fail(token, id, gen, "killed").status)
        runBlocking { run.await() }
        val cleanup = h.json(h.poll(token))["cleanup"]!!.jsonArray
        assertTrue(cleanup.isNotEmpty())
        val first = cleanup[0].jsonObject
        assertEquals(setOf("cleanupId", "jobId"), first.keys)
        val cid = first["cleanupId"] as JsonPrimitive
        assertTrue("cleanupId is a JSON string", cid.isString)
        assertTrue(Regex("^[0-9]{1,18}$").matches(cid.content))
        assertEquals(id, first["jobId"]!!.jsonPrimitive.content)
        // re-sent until acked; an ack of an unknown id deletes nothing; a numeric (non string) ack is refused
        assertEquals(cleanup.size, h.json(h.poll(token, acks = "[\"999999\"]"))["cleanup"]!!.jsonArray.size)
        assertEquals(400, h.poll(token, acks = "[${cid.content}]").status)
        assertEquals(0, h.json(h.poll(token, acks = "[\"${cid.content}\"]"))["cleanup"]!!.jsonArray.count { it.jsonObject["cleanupId"]!!.jsonPrimitive.content == cid.content })
    }

    @Test fun pollNamesAnActiveJobThePhoneNoLongerHoldsInCancel() {
        h.startRun()
        val (id, gen) = h.confirmAndLease(token)
        val active = """[{"jobId":"$id","leaseGen":$gen}]"""
        assertEquals(JsonArray(emptyList()), h.json(h.poll(token, active = active))["cancel"])
        runBlocking { h.backend.service.cancel(id) }
        assertEquals(JsonArray(listOf(JsonPrimitive(id))), h.json(h.poll(token, active = active))["cancel"])
        // a job the phone never heard of is named too
        assertEquals(JsonArray(listOf(JsonPrimitive("ghost"))), h.json(h.poll(token, active = """[{"jobId":"ghost","leaseGen":1}]"""))["cancel"])
    }

    @Test fun readyFieldIsRecordedAndStaleAfterFifteenSeconds() {
        assertTrue(h.backend.service.readiness().stale)
        h.poll(token, ready = true)
        assertTrue(h.backend.service.readiness().ready)
        h.now += 15_000
        assertTrue(h.backend.service.readiness().stale)
    }

    @Test fun badBodiesProtocolMismatchAndUnknownRoutes() {
        assertEquals(409, h.call("POST", "/api/helper/poll", token, """{"helperProtocol":2,"active":[],"cleanupAcks":[],"ready":{"py_exec":true}}""").status)
        assertEquals("protocol_mismatch", h.json(h.call("POST", "/api/helper/lease", token, """{"helperProtocol":2,"types":["py_exec"]}"""))["error"]!!.jsonPrimitive.content)
        assertEquals(400, h.call("POST", "/api/helper/poll", token, "not json").status)
        assertEquals(400, h.call("POST", "/api/helper/poll", token, """{"helperProtocol":1,"active":[],"cleanupAcks":[],"ready":{"py_exec":true},"extra":1}""").status)
        assertEquals(400, h.call("POST", "/api/helper/lease", token, """{"helperProtocol":1,"types":["tool_build"]}""").status)
        assertEquals(400, h.call("GET", "/api/helper/jobs/x/payload", token, query = mapOf("leaseGen" to "-1")).status)
        for ((m, p) in listOf("GET" to "/api/helper/poll", "POST" to "/api/helper/jobs", "POST" to "/api/helper/jobs/a/b/c", "POST" to "/api/helper/jobs/x/confirm", "DELETE" to "/api/helper/jobs/x/result", "POST" to "/api/helper/nothing")) {
            assertEquals("$m $p", 404, h.call(m, p, token).status)
        }
        assertEquals(404, h.call("GET", "/api/helper/jobs/${"a".repeat(65)}/payload", token, query = mapOf("leaseGen" to "1")).status)
    }

    @Test fun invalidResultIs422AndTheLeaseSurvives() {
        h.startRun()
        val (id, gen) = h.confirmAndLease(token)
        val bad = h.result(token, id, gen, """{"helperProtocol":1,"jobType":"py_exec","outputs":[],"stats":{"ms":1}}""")
        assertEquals(422, bad.status)
        assertEquals("invalid_result", h.json(bad)["error"]!!.jsonPrimitive.content)
        assertEquals(200, h.heartbeat(token, id, gen).status)
    }

    @Test fun staleLeaseGenerationIs409() {
        h.startRun()
        val (id, gen) = h.confirmAndLease(token)
        val r = h.heartbeat(token, id, gen + 7)
        assertEquals(409, r.status)
        assertEquals("stale_generation", h.json(r)["error"]!!.jsonPrimitive.content)
    }

    // ---- the denial table (fixtures win) ----

    @Test fun denialStatusTableMatchesTheFixture() {
        val table = constants["denials"]!!.jsonObject
        for ((status, codes) in table) for (c in codes.jsonArray) {
            assertEquals("status of ${c.jsonPrimitive.content}", status.toInt(), HelperRoutes.statusOf(c.jsonPrimitive.content))
        }
        assertEquals(constants["deletionClassDenials"]!!.jsonArray.map { it.jsonPrimitive.content }, com.rokid.context.canvas.helper.HelperProtocol.DELETION_CLASS_DENIALS)
    }

    @Test fun grantOffAnswers403CapabilityRevokedOnEveryJobRouteAndPollStillDeliversCleanup() {
        h.startRun()
        val (id, gen) = h.confirmAndLease(token)
        h.backend.native.setGrant(HelperGrants.PY_EXEC, false)
        for (r in listOf(h.lease(token), h.payload(token, id, gen), h.heartbeat(token, id, gen), h.result(token, id, gen, h.okResult()), h.fail(token, id, gen, "killed"))) {
            assertEquals(403, r.status)
            assertEquals("capability_revoked", h.json(r)["error"]!!.jsonPrimitive.content)
        }
        val p = h.json(h.poll(token))
        assertEquals(JsonArray(emptyList()), p["available"])
        assertFalse((p["caps"]!!.jsonObject["py_exec"] as JsonPrimitive).content.toBoolean())
        assertTrue("cleanup is still delivered with the grant off", p["cleanup"]!!.jsonArray.any { it.jsonObject["jobId"]!!.jsonPrimitive.content == id })
    }

    // ---- body limits ----

    @Test fun resultBodyLimitIsTwoMiBForThatRouteOnly() {
        assertEquals(2 * 1024 * 1024 + 4096, CanvasServer.bodyLimit("POST", "/api/helper/jobs/j1/result"))
        for ((m, p) in listOf("POST" to "/api/helper/poll", "POST" to "/api/helper/jobs/j1/heartbeat", "POST" to "/api/helper/jobs/j1/fail", "POST" to "/api/runs", "GET" to "/api/helper/jobs/j1/result", "POST" to "/api/helper/jobs/j1/result/x")) {
            assertEquals("$m $p", CanvasServer.MAX_BODY, CanvasServer.bodyLimit(m, p))
        }
        // through the router: 300 KB is too large for the heartbeat route and accepted (and then judged on its content) for the result route
        val big = " ".repeat(300 * 1024)
        assertEquals(413, h.call("POST", "/api/helper/jobs/j1/heartbeat", token, """{"leaseGen":1}$big""").status)
        assertNotEquals413(h.call("POST", "/api/helper/jobs/j1/result", token, """{"leaseGen":1,"resultHash":"${"0".repeat(64)}","result":{}}$big"""))
        assertEquals(413, h.call("POST", "/api/helper/jobs/j1/result", token, " ".repeat(2 * 1024 * 1024 + 4097)).status)
    }

    private fun assertNotEquals413(r: com.rokid.context.phone.canvas.server.HttpResponse) = assertTrue("status ${r.status}", r.status != 413)

    // ---- the deletion table: every cause makes the next heartbeat non-200 and enqueues cleanup ----

    private fun leasedJob(): Pair<String, Long> { h.startRun(); return h.confirmAndLease(token) }

    private fun assertDeleted(id: String, gen: Long) {
        val hb = h.heartbeat(token, id, gen)
        assertTrue("heartbeat after the deletion cause answered ${hb.status}", hb.status != 200)
        assertTrue("the answer is a deletion-class denial", h.json(hb)["error"]!!.jsonPrimitive.content in com.rokid.context.canvas.helper.HelperProtocol.DELETION_CLASS_DENIALS || hb.status == 401)
        val cleanup = runBlocking { h.backend.service.pendingCleanup(50) }
        assertTrue("a cleanup row names the job", cleanup.any { it.jobId == id })
    }

    @Test fun deletionTableCancelVoidForgetDeleteAll() {
        run { val (id, gen) = leasedJob(); runBlocking { h.backend.service.cancel(id) }; assertDeleted(id, gen) }
        run { val (id, gen) = leasedJob(); runBlocking { h.backend.service.voidJob(id, "void") }; assertDeleted(id, gen) }
        run { val (id, gen) = leasedJob(); runBlocking { h.dao.bumpFrameForgetRev() }; assertDeleted(id, gen) } // Forget of a frame: the revision the fence reads moves (voidHelperJobsForFrames is internal to :services:memory since WP4's fix and is not a deletion entry point)
        run { val (id, gen) = leasedJob(); h.wipeGen += 1; assertDeleted(id, gen) }                                                           // Delete all (the fence moves)
    }

    @Test fun deletionTableGrantOffDisconnectDeveloperModeOffRestart() {
        run { val (id, gen) = leasedJob(); h.backend.native.setGrant(HelperGrants.PY_EXEC, false); assertDeleted(id, gen) }
        h.close(); setUp()
        run { val (id, gen) = leasedJob(); h.backend.native.disconnectHelper(); runBlocking { h.backend.session.awaitEnd() }; assertDeleted(id, gen) }
        h.close(); setUp()
        run { val (id, gen) = leasedJob(); h.backend.session.terminate(SessionEnd.DEVELOPER_MODE_OFF).let { runBlocking { it.join() } }; assertDeleted(id, gen) }
        h.close(); setUp()
        run { val (id, gen) = leasedJob(); runBlocking { h.backend.session.recoverAtStart().join() }; assertDeleted(id, gen) }
    }

    @Test fun aRouteCannotRenewAVoidedJob() {
        val (id, gen) = leasedJob()
        runBlocking { h.backend.service.voidJob(id, "void") }
        assertEquals(409, h.heartbeat(token, id, gen).status)
        assertEquals(409, h.result(token, id, gen, h.okResult()).status)
        assertEquals(409, h.payload(token, id, gen).status)
    }

    // ---- principals ----

    @Test fun browserAndAnonymousCredentialsNeverReachTheHelperRoutes() {
        val bt = (h.browserAuth.pair(h.browserAuth.newCode().code) as com.rokid.context.phone.canvas.server.CanvasAuth.Pair.Ok).token
        for (p in listOf("/api/helper/poll", "/api/helper/lease")) {
            assertEquals(401, h.call("POST", p, bt, "{}").status)
            assertEquals(401, h.call("POST", p, null, "{}").status)
        }
        assertEquals(401, h.call("POST", "/api/helper/poll", "not-a-token", "{}").status)
    }

    @Test fun afterDisconnectTheTokenIs401() {
        h.backend.native.disconnectHelper()
        assertEquals(401, h.poll(token).status)
    }

    @Test fun routesLogOnlyCountOnlyLines() {
        val lines = mutableListOf<String>()
        h.close()
        h = HelperHarness(ApplicationProvider.getApplicationContext(), log = { _, _, m -> lines += m })
        token = h.pairHelper()
        h.startRun()
        val (id, gen) = h.confirmAndLease(token)
        h.heartbeat(token, id, gen); h.result(token, id, gen, h.okResult())
        assertTrue(lines.isNotEmpty())
        val shape = Regex("^[a-z]+( [a-z_]+){0,4}( [0-9]+)?$")
        for (l in lines) {
            assertTrue("'$l' is not a count-only shape", shape.matches(l))
            assertFalse(l.contains(id)); assertFalse(l.contains(token))
        }
    }

    @Test fun httpPairRejectsWhenTheWindowIsNotOpenAndNeverOpensIt() {
        h.backend.native.disconnectHelper()
        val before = h.backend.auth.pairingCode()
        assertNull(before)
        val r = h.call("POST", "/api/helper/pair", null, """{"helperProtocol":1,"code":"123456","name":"laptop"}""")
        assertEquals(401, r.status)
        assertNull(h.backend.auth.pairingCode())
        assertNotNull(HelperDeletionCause.DISCONNECT)
    }

    // ---- review 1 ----

    @Test fun aMalformedResultEnvelopeAnswers422SoTheHelperPostsFailInsteadOfRetrying() {
        h.startRun()
        val (id, gen) = h.confirmAndLease(token)
        val nonObject = h.call("POST", "/api/helper/jobs/$id/result", token, """{"leaseGen":$gen,"resultHash":"${"0".repeat(64)}","result":null}""")
        assertEquals(422, nonObject.status)
        assertEquals("invalid_result", h.json(nonObject)["error"]!!.jsonPrimitive.content)
        val badHash = h.call("POST", "/api/helper/jobs/$id/result", token, """{"leaseGen":$gen,"resultHash":"xyz","result":${h.okResult()}}""")
        assertEquals(422, badHash.status)
        assertEquals(200, h.heartbeat(token, id, gen).status)
    }

    @Test fun aRequestIsServedForTheHelperWhoseTokenItCarriesNotTheNewestPairing() {
        val old = token
        val oldSession = h.backend.auth.sessionFor(old)
        assertNotNull(oldSession)
        val fresh = h.pairHelper(grantOn = false)                    // a second pairing replaces the first helper
        assertNull("the old token no longer maps to any session", h.backend.auth.sessionFor(old))
        assertEquals(h.backend.auth.session(), h.backend.auth.sessionFor(fresh))
        assertNull(h.backend.auth.sessionFor(null))
        assertNull(h.backend.auth.sessionFor("nope"))
    }
}
