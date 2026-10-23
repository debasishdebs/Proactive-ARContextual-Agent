package com.rokid.context.canvas.helper

import com.rokid.context.canvas.helper.ProtocolFixtures.arr
import com.rokid.context.canvas.helper.ProtocolFixtures.load
import com.rokid.context.canvas.helper.ProtocolFixtures.num
import com.rokid.context.canvas.helper.ProtocolFixtures.o
import com.rokid.context.canvas.helper.ProtocolFixtures.obj
import com.rokid.context.canvas.helper.ProtocolFixtures.str
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperJobState
import com.rokid.context.memory.canvas.JobEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** WP1 RED group (per-run confirmation, user decision 2026-10-23 evening): binding vectors and the state machine; the HMAC itself is :phone (WP2). */
class ConfirmVectorsTest {
    private val v = load("confirm-vectors.json")
    private fun binding(o: JsonObject) = ConfirmBinding(o.str("jobId"), o.str("bundleHash"), o.num("sessionGen"), o.num("expiresAtMs"))
    private fun hmacHex(keyHex: String, msg: String): String {
        val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Test fun messageAndTagMatchTheVectors() {
        for (c in v.arr("cases").map { it.o() }) {
            val b = binding(c.obj("binding"))
            assertEquals(c.str("name"), c.str("expectedMessage"), b.message())
            assertEquals(c.str("name"), c.str("expectedTag"), hmacHex(v.str("testKeyHex"), b.message()))
        }
    }

    @Test fun anyOneChangedFieldChangesTheMessageAndTag() {
        val cases = v.arr("cases").map { it.o() }
        val base = binding(cases.first { it.str("name") == "accepted" }.obj("binding"))
        val baseTag = hmacHex(v.str("testKeyHex"), base.message())
        for (c in cases.filter { it.str("name") != "accepted" }) {
            val b = binding(c.obj("binding"))
            assertNotEquals(c.str("name"), base.message(), b.message())
            assertNotEquals(c.str("name"), baseTag, hmacHex(v.str("testKeyHex"), b.message()))
        }
    }

    private fun fakePort(): Pair<InMemoryHelperJobPort, () -> Long> {
        var now = 1_700_000_000_000L
        return InMemoryHelperJobPort(now = { now }, sessionGen = { 7 }, newId = { "job-1" }) to { now }
    }

    private fun request() = fakeRequest()

    @Test fun sameJobHashSessionAndExpiryIsAcceptedAnyChangedFieldIsRefused() = runBlocking {
        val cases = v.arr("cases").map { it.o() }
        for (c in cases) {
            val (port, _) = fakePort()
            val job = port.submitForTest(request())
            val real = port.pendingConfirmations().single()
            // the vectors carry the shape; the stored job's own binding is the accepted one, each case perturbs exactly one field of it
            val stored = ConfirmBinding(real.jobId, real.bundleHash, real.sessionGen, real.expiresAtMs)
            val tried = when (c.str("name")) {
                "accepted" -> stored
                "otherJobId" -> stored.copy(jobId = "job-2")
                "otherBundleHash" -> stored.copy(bundleHash = "0".repeat(64))
                "otherSessionGen" -> stored.copy(sessionGen = stored.sessionGen + 1)
                "otherExpiresAtMs" -> stored.copy(expiresAtMs = stored.expiresAtMs + 1)
                else -> error("unknown vector ${c.str("name")}")
            }
            val r = port.confirm(job, tried)
            val accepted = c.getValue("accept").jsonPrimitive.booleanOrNull!!
            assertEquals(c.str("name"), accepted, r == ConfirmResult.CONFIRMED)
            assertEquals(c.str("name"), if (accepted) HelperJobState.QUEUED else HelperJobState.AWAITING_CONFIRM, port.state(job))
        }
    }

    @Test fun confirmAfterExpiryIsRefusedAndTheJobFailsNotConfirmed() = runBlocking {
        var now = 1_700_000_000_000L
        val port = InMemoryHelperJobPort(now = { now }, sessionGen = { 7 }, newId = { "job-1" })
        val job = port.submitForTest(request())
        val p = port.pendingConfirmations().single()
        now += HelperProtocol.CONFIRM_TTL_MS
        assertEquals(ConfirmResult.EXPIRED, port.confirm(job, ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs)))
        assertTrue(port.pendingConfirmations().isEmpty())
        assertEquals(PyOutcome.NotConfirmed, port.outcomeOf(job))
        assertEquals(null, port.leaseNext())
    }

    @Test fun unconfirmedJobIsNeverLeasedAndConfirmReleasesItOnce() = runBlocking {
        val (port, _) = fakePort()
        val job = port.submitForTest(request())
        assertTrue(port.awaitingConfirm(job))
        assertEquals(null, port.leaseNext())
        val p = port.pendingConfirmations().single()
        val b = ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs)
        assertEquals(ConfirmResult.CONFIRMED, port.confirm(job, b))
        assertEquals(ConfirmResult.DUPLICATE, port.confirm(job, b))
        assertEquals(job, port.leaseNext())
        assertEquals(null, port.leaseNext())
    }

    @Test fun declineEndsTheJobNotConfirmed() = runBlocking {
        val (port, _) = fakePort()
        val job = port.submitForTest(request())
        assertTrue(port.declineConfirm(job))
        assertEquals(HelperJobState.CANCELLED, port.state(job))
        assertEquals(PyOutcome.NotConfirmed, port.outcomeOf(job))
        val p = ConfirmBinding(job, "x", 7, 0)
        assertEquals(ConfirmResult.NOT_AWAITING, port.confirm(job, p))
    }

    @Test fun stateTransitionsFromTheVectorsAndQueuedOnlyByConfirm() {
        val rows = v.arr("transitions").map { it.jsonArray }
        assertTrue(rows.size >= 25)
        for (r in rows) {
            val from = HelperJobState.valueOf(r[0].jsonPrimitive.content)
            val event = JobEvent.valueOf(r[1].jsonPrimitive.content)
            val expected = r[2].let { if (it is kotlinx.serialization.json.JsonNull) null else HelperJobState.valueOf(it.jsonPrimitive.content) }
            assertEquals("$from $event", expected, from.next(event))
        }
    }

    @Test fun leasingAnAwaitingJobIsRefusedByTheMachine() {
        assertEquals(null, HelperJobState.AWAITING_CONFIRM.next(JobEvent.LEASE))
        assertEquals(HelperJobState.QUEUED, HelperJobState.AWAITING_CONFIRM.next(JobEvent.CONFIRM))
    }

    @Test fun staleFenceRefusesConfirmAndVoidsTheJob() = runBlocking {
        var rev = 5L
        val fence0 = fenceAt(rev)
        val port = InMemoryHelperJobPort(now = { 1_700_000_000_000L }, sessionGen = { 7 }, newId = { "job-1" }, fenceNow = { fenceAt(rev) })
        val job = port.submitForTest(fakeRequest(fence0))
        rev += 1
        val p = port.pendingConfirmations().single()
        assertEquals(ConfirmResult.STALE_FENCE, port.confirm(job, ConfirmBinding(p.jobId, p.bundleHash, p.sessionGen, p.expiresAtMs)))
        assertEquals(HelperJobState.VOIDED, port.state(job))
        assertEquals(PyOutcome.Voided, port.outcomeOf(job))
    }
}
