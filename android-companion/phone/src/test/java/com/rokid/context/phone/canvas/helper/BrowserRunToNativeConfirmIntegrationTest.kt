package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.helper.HelperProtocol
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.canvas.FakeCanvasHost
import com.rokid.context.phone.canvas.ui.CanvasHomeScreen
import com.rokid.context.phone.canvas.ui.CanvasHomeViewModel
import com.rokid.context.phone.ui.MainDispatcherRule
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
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
 * c8 #3, WP9: the whole production chain of one experiment run. A browser-authenticated `POST /api/runs/estimate` and `POST /api/runs` with `frameIds` over a photo/frame
 * fixture creates the run (`/api/helper-jobs` refuses a browser submission, retained P12); the real `CanvasRunner` and `PythonExecutor`, the real `HelperJobService` and
 * `HelperConfirmAdapter`, the real `CanvasHomeViewModel` and the Compose screen show the card; a Compose tap on Confirm lets the fake helper lease and complete the job; and
 * the result is fetched through `GET /api/runs/{id}`. No notification is involved. The card goes away after the window ends and after the session ends.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h3000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserRunToNativeConfirmIntegrationTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var k: BrowserRunKit
    private lateinit var vm: CanvasHomeViewModel
    private lateinit var pipeline: String

    @Before fun setUp() {
        k = BrowserRunKit(ApplicationProvider.getApplicationContext(), tmp.root)
        k.seedFrame("f1", 1_000); k.seedFrame("f2", 2_000)
        pipeline = k.savePythonPipeline()
        // the host the screen reads: its confirmation actions ARE the production adapter, its native actions the production ones
        val host = FakeCanvasHost(confirm = k.h.backend.adapter, native = k.h.backend.native)
        vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { k.h.now }, io = main.dispatcher)
        compose.setContent {
            PcTheme {
                val ui = vm.state.collectAsState().value
                CanvasHomeScreen(
                    ui, onBack = {}, onOpenEditor = {}, onOpenBrowser = {}, onLan = {}, onOpenPipeline = {}, onRollback = {}, onSignOutAll = {}, onMessageShown = {},
                    onConfirmRun = vm::confirmRun, onDeclineRun = vm::declineRun,
                )
            }
        }
        vm.onStart()
    }
    @After fun tearDown() { vm.onStop(); k.close() }

    private fun second() = main.dispatcher.scheduler.advanceTimeBy(1_001)
    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what" }; second(); Thread.sleep(10) }
    }
    private fun cardShown() = compose.onAllNodesWithTagExists("confirm_card")
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun browserRunToNativeTapToFakeHelperToResult() {
        assertEquals("the browser cannot submit a py_exec job itself", 403, k.browser("POST", "/api/helper-jobs", kotlinx.serialization.json.buildJsonObject { put("jobType", "py_exec") }).status)
        val runId = k.startRun(pipeline, listOf("f1", "f2"))
        val job = k.awaitPending()
        assertNull("no payload and no lease before the tap", k.helperLease())
        waitFor("the card in the pending state") { vm.state.value.pending.any { it.row.jobId == job } }
        compose.waitUntil(5_000) { cardShown() }
        compose.onNodeWithTag("confirm_ok_$job").assertExists()

        compose.onNodeWithTag("confirm_ok_$job").performClick()            // the user's tap on the phone
        k.waitUntil { k.jobState(job) == "QUEUED" }
        val done = k.helperCompletes(items = 2)                              // the fake laptop leases, fetches the payload and answers
        assertEquals(job, done)

        assertEquals("DONE", k.awaitStatus(runId, "DONE", "CANCELLED", "STOPPED_FORGET"))
        val items = k.fnItems(runId)
        assertEquals(2, items.size)
        assertTrue(items.all { it["error"] is JsonNull && k.fnN(it) == "1" })
        waitFor("the card to drop after the tap") { vm.state.value.pending.isEmpty() }
        compose.waitUntil(5_000) { !cardShown() }
        assertEquals("the one tap went through the production adapter", listOf(job), k.h.port.confirms)
    }

    @Test fun pendingRemovedAfterExpiry() {
        val runId = k.startRun(pipeline, listOf("f1"))
        val job = k.awaitPending()
        waitFor("the card") { vm.state.value.pending.any { it.row.jobId == job } }
        compose.waitUntil(5_000) { cardShown() }

        k.h.now += HelperProtocol.CONFIRM_TTL_MS
        vm.tick()
        compose.waitUntil(5_000) { !cardShown() }
        k.awaitStatus(runId, "DONE", "CANCELLED")
        assertEquals(listOf("not_confirmed"), k.fnItems(runId).map { it["error"]!!.jsonPrimitive.content })
        assertNull(k.helperLease())
    }

    @Test fun pendingRemovedAfterSessionTermination() {
        val runId = k.startRun(pipeline, listOf("f1"))
        val job = k.awaitPending()
        waitFor("the card") { vm.state.value.pending.any { it.row.jobId == job } }
        compose.waitUntil(5_000) { cardShown() }

        kotlinx.coroutines.runBlocking { k.h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).join() }
        waitFor("the pending list to empty") { vm.state.value.pending.isEmpty() }
        compose.waitUntil(5_000) { !cardShown() }
        k.awaitStatus(runId, "DONE", "CANCELLED")
        assertEquals("VOIDED", k.jobState(job))
        assertEquals(listOf("voided"), k.fnItems(runId).map { it["error"]!!.jsonPrimitive.content })
        assertTrue(k.h.port.confirms.isEmpty())
    }
}
