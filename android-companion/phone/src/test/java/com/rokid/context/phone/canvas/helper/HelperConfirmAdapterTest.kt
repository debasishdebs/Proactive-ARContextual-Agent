package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.helper.ConfirmResult
import com.rokid.context.canvas.helper.PyOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP6 RED group (user decision 2026-10-23 evening): the adapter is the only caller of the port's `confirm`; it mints the tag over the immutable job data the PORT reports
 * (never over anything the card sends), verifies it, and only then confirms. Payload, heartbeat and result answer 409 `not_confirmed` while AWAITING_CONFIRM;
 * `terminate(cause)` voids pending jobs; the Kotlin fake helper completes a job only after `onConfirm`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HelperConfirmAdapterTest {
    private lateinit var h: HelperHarness
    private lateinit var token: String

    @Before fun setUp() { h = HelperHarness(ApplicationProvider.getApplicationContext()); token = h.pairHelper() }
    @After fun tearDown() { h.close() }

    @Test fun theFakeHelperCompletesAJobOnlyAfterOnConfirm() {
        val run = h.startRun()
        val id = h.awaitPendingJob()
        runBlocking { h.backend.adapter.refresh() }
        assertEquals(listOf(id), h.backend.adapter.pending().map { it.jobId })
        assertTrue("nothing is leasable before the tap", h.json(h.lease(token))["job"] is kotlinx.serialization.json.JsonNull)
        h.backend.adapter.onConfirm(id)                                 // the card's callback (asynchronous)
        runBlocking { h.backend.adapter.awaitIdle() }
        val job = h.json(h.lease(token))["job"]!!.jsonObject
        assertEquals(id, job["jobId"]!!.jsonPrimitive.content)
        val gen = job["leaseGen"]!!.jsonPrimitive.content.toLong()
        assertEquals(200, h.payload(token, id, gen).status)
        assertEquals(200, h.result(token, id, gen, h.okResult()).status)
        assertTrue(runBlocking { run.await() } is PyOutcome.Done)
        assertEquals(listOf(id), h.port.confirms)
    }

    @Test fun onConfirmMintsAndVerifiesTheTagOverTheImmutableBindingBeforeCallingThePort() {
        h.startRun()
        val id = h.awaitPendingJob()
        val info = runBlocking { h.backend.service.pendingConfirmations().first() }
        val binding = com.rokid.context.memory.canvas.ConfirmBinding(id, info.bundleHash, info.sessionGen, info.expiresAtMs)
        val tag = h.backend.confirm.tag(binding)
        assertTrue(h.backend.confirm.verify(binding, tag, h.now))
        assertEquals(ConfirmResult.CONFIRMED, runBlocking { h.backend.adapter.confirmNow(id) })
        assertEquals(1, h.port.confirms.size)
        // a second tap on the same job finds nothing waiting: refused before the port, never a second confirmation
        assertNull(runBlocking { h.backend.adapter.confirmNow(id) })
        assertEquals(1, h.port.confirms.size)
    }

    @Test fun aTapAfterTheKeyRotatedIsRefusedAndNeverReachesThePort() {
        h.startRun()
        val id = h.awaitPendingJob()
        val adapter = h.backend.adapter
        adapter.beforeVerify = { h.backend.confirm.rotate() }          // the key changes between minting and verifying
        assertNull(runBlocking { adapter.confirmNow(id) })
        assertTrue(h.port.confirms.isEmpty())
    }

    @Test fun aTapWithTheGrantOffIsRefusedAndNeverReachesThePort() {
        h.startRun()
        val id = h.awaitPendingJob()
        h.grantStore.inner.values.clear()                               // the grant is off (the session still exists)
        assertNull(runBlocking { h.backend.adapter.confirmNow(id) })
        assertTrue(h.port.confirms.isEmpty())
    }

    @Test fun aTapAfterTheWindowExpiredIsRefusedAndTheRunFailsNotConfirmed() {
        val run = h.startRun()
        val id = h.awaitPendingJob()
        h.now += com.rokid.context.canvas.helper.HelperProtocol.CONFIRM_TTL_MS
        assertNull("verify refuses an expired binding before the port is called", runBlocking { h.backend.adapter.confirmNow(id) })
        assertTrue(h.port.confirms.isEmpty())
        assertEquals(PyOutcome.NotConfirmed, runBlocking { run.await() })
    }

    @Test fun declineEndsTheRunNotConfirmedAndNothingIsReleased() {
        val run = h.startRun()
        val id = h.awaitPendingJob()
        h.backend.adapter.onCancel(id)
        assertEquals(PyOutcome.NotConfirmed, runBlocking { run.await() })
        runBlocking { h.backend.adapter.awaitIdle() }
        assertEquals(listOf(id), h.port.declines)
        assertTrue(h.backend.adapter.pending().isEmpty())
        assertTrue(h.json(h.lease(token))["job"] is kotlinx.serialization.json.JsonNull)
    }

    @Test fun payloadHeartbeatAndResultAnswer409NotConfirmedWhileAwaitingAndNeverReleaseThePayload() {
        h.startRun()
        val id = h.awaitPendingJob()
        val payload = h.payload(token, id, 0)
        assertEquals(409, payload.status)
        assertEquals("not_confirmed", h.json(payload)["error"]!!.jsonPrimitive.content)
        assertFalse("no payload byte left the phone", String(payload.body, Charsets.UTF_8).contains("def run"))
        for (r in listOf(h.heartbeat(token, id, 0), h.result(token, id, 0, h.okResult()))) {
            assertEquals(409, r.status)
            assertEquals("not_confirmed", h.json(r)["error"]!!.jsonPrimitive.content)
        }
    }

    @Test fun theAdapterIsTheOnlyCallerOfThePortConfirmInMainSources() {
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("android-companion/phone/src/main/java")
        val callers = root.walkTopDown().filter { it.extension == "kt" }.filter { f -> f.readText().let { Regex("""\.confirm\(\s*[A-Za-z_.]+\s*,""").containsMatchIn(it) && it.contains("HelperJobPort") } }.map { it.name }.toList()
        assertEquals(listOf("HelperConfirmAdapter.kt"), callers)
    }

    @Test fun noRouteReachesTheAdapterOrTheKey() {
        for (name in listOf("HelperRoutes.kt", "HelperBrowserRoutes.kt")) {
            val f = File("src/main/java/com/rokid/context/phone/canvas/helper/$name")
            val src = f.readText()
            for (banned in listOf("HelperConfirm", "ConfirmBinding", ".confirm(", "declineConfirm", "onConfirm", "onCancel", "HelperNative", "acceptPairing", "setGrant", "allOff")) {
                assertFalse("$name mentions $banned", src.contains(banned))
            }
        }
    }

    @Test fun terminateVoidsPendingJobsAndDropsTheCard() {
        h.startRun()
        val id = h.awaitPendingJob()
        runBlocking { h.backend.adapter.refresh() }
        assertEquals(1, h.backend.adapter.pending().size)
        runBlocking { h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).join() }
        assertTrue(h.backend.adapter.pending().isEmpty())
        assertEquals("VOIDED", runBlocking { h.dao.helperJob(id) }!!.state)
        assertNull("a late tap is refused", runBlocking { h.backend.adapter.confirmNow(id) })
    }

    @Test fun pendingRowsCarryOnlyKindCountSizeAndExpiry() {
        h.startRun(items = 3)
        val id = h.awaitPendingJob()
        runBlocking { h.backend.adapter.refresh() }
        val row = h.backend.adapter.pending().single()
        assertEquals(id, row.jobId)
        assertEquals("frames", row.inputKind)
        assertEquals(3, row.itemCount)
        assertTrue(row.sourceChars > 0)
        assertEquals(h.now + com.rokid.context.canvas.helper.HelperProtocol.CONFIRM_TTL_MS, row.expiresAtMs)
    }
}
