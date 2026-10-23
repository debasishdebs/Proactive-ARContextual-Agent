package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * WP9 (Option 1: frames only). A Python experiment over photos, entered through the PRODUCTION browser API with the browser token and a photo/frame fixture:
 * `POST /api/runs/estimate`, `POST /api/runs` with `frameIds`, the native tap, the (Kotlin fake) laptop, and the result in `GET /api/runs/{id}`. Then a Forget of one of the
 * frames voids a second run, whether it still waits for its tap or the laptop already holds it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FramesToPyFnIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var k: BrowserRunKit
    private lateinit var pipeline: String

    @Before fun setUp() {
        k = BrowserRunKit(ApplicationProvider.getApplicationContext(), tmp.root)
        k.seedFrame("f1", 1_000); k.seedFrame("f2", 2_000)
        pipeline = k.savePythonPipeline()
    }
    @After fun tearDown() { k.close() }

    @Test fun estimateStartWithFrameIdsConfirmAndTheResultIsInTheResponse() {
        val est = k.ok(k.browser("POST", "/api/runs/estimate", buildJsonObject { put("pipelineId", pipeline); put("frameIds", kotlinx.serialization.json.JsonArray(listOf("f1", "f2").map { kotlinx.serialization.json.JsonPrimitive(it) })) }))
        assertEquals(2, est["photos"]!!.jsonPrimitive.content.toInt())
        assertTrue(est["confirmToken"]!!.jsonPrimitive.content.isNotEmpty())

        val runId = k.startRun(pipeline, listOf("f1", "f2"))
        val job = k.awaitPending()
        assertNull("before the tap the laptop learns of nothing", k.helperLease())
        assertEquals("AWAITING_CONFIRM", k.jobState(job))
        assertEquals("the run is still running, waiting for the tap", "RUNNING", k.runStatus(runId))

        k.tap(job)
        assertEquals("QUEUED", k.jobState(job))
        val (leased, gen) = checkNotNull(k.helperLease())
        assertEquals(job, leased)
        val payload = k.helperPayload(leased, gen)
        assertEquals(200, payload.status)
        val body = String(payload.body, Charsets.UTF_8)
        assertTrue("the helper gets the source and opaque item ids", body.contains("def run") && body.contains("\"i0\"") && body.contains("\"i1\""))
        assertFalse("the helper never learns a frame id", Regex("\"f[12]\"").containsMatchIn(body))
        assertEquals(200, k.helperResult(leased, gen, items = 2).status)

        assertEquals("DONE", k.awaitStatus(runId, "DONE", "CANCELLED", "STOPPED_FORGET"))
        val fn = k.fnItems(runId).sortedBy { it["frameId"]!!.jsonPrimitive.content }
        assertEquals(listOf("f1", "f2"), fn.map { it["frameId"]!!.jsonPrimitive.content })
        for (i in fn) {
            assertEquals("OK", i["status"]!!.jsonPrimitive.content)
            assertTrue("no error", i["error"] is JsonNull)
            assertEquals("the result is in the response", "1", k.fnN(i))
        }
        assertEquals(0, k.run(runId)["failures"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun forgetOfThatFrameVoidsASecondRunWaitingForItsTap() {
        val first = k.startRun(pipeline, listOf("f1", "f2"))
        k.tap(k.awaitPending()); k.helperCompletes(items = 2)
        assertEquals("DONE", k.awaitStatus(first, "DONE"))

        val second = k.startRun(pipeline, listOf("f1", "f2"))
        val job = k.awaitPending()
        assertTrue("precondition: the forget found the frame", k.forget("f1").framesToDelete.isNotEmpty())

        // a Forget of a frame OF the run: the write fence drops the run's rows and the run ends CANCELLED (STOPPED_FORGET is the status of a Forget of a frame outside it)
        assertEquals("CANCELLED", k.awaitStatus(second, "STOPPED_FORGET", "DONE", "CANCELLED"))
        assertEquals("VOIDED", k.jobState(job))
        assertNull("nothing was ever released", k.helperLease())
        assertTrue("nothing of the second run was stored", k.fnItems(second).isEmpty())
        assertTrue("the forgotten frame's rows are gone from the first run too", k.run(first)["items"]!!.jsonArray.none { it.jsonObject["frameId"]!!.jsonPrimitive.content == "f1" })
    }

    @Test fun forgetOfAnyOtherFrameAlsoVoidsTheOpenJobAndTheRunSaysSo() {
        k.seedFrame("f9", 9_000)
        val run = k.startRun(pipeline, listOf("f1", "f2"))
        val job = k.awaitPending()
        assertTrue(k.forget("f9").framesToDelete.isNotEmpty())
        assertEquals("STOPPED_FORGET", k.awaitStatus(run, "STOPPED_FORGET", "DONE", "CANCELLED"))
        assertEquals("VOIDED", k.jobState(job))
        assertEquals(listOf("frame_forget", "frame_forget"), k.fnItems(run).map { it["error"]!!.jsonPrimitive.content })
        assertNull(k.helperLease())
    }

    @Test fun forgetOfThatFrameVoidsASecondRunTheLaptopAlreadyHolds() {
        val second = k.startRun(pipeline, listOf("f1", "f2"))
        val job = k.awaitPending()
        k.tap(job)
        val (leased, gen) = checkNotNull(k.helperLease())
        assertEquals(200, k.helperPayload(leased, gen).status)

        assertTrue(k.forget("f2").framesToDelete.isNotEmpty())

        assertEquals("CANCELLED", k.awaitStatus(second, "STOPPED_FORGET", "DONE", "CANCELLED"))
        assertEquals("VOIDED", k.jobState(job))
        val late = k.helperResult(leased, gen, items = 2)
        assertEquals("the late result is refused", 409, late.status)
        assertEquals("voided", k.json(late)["error"]!!.jsonPrimitive.content)
        assertEquals("the heartbeat is refused too, so the laptop kills the job", 409, k.h.heartbeat(k.helperToken, leased, gen).status)
        assertTrue("no output of the forgotten run was stored", k.fnItems(second).isEmpty())
    }
}
