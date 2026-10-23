package com.rokid.context.phone.canvas

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.phone.canvas.helper.HelperGrants
import com.rokid.context.phone.canvas.helper.HelperHarness
import com.rokid.context.phone.canvas.helper.SessionEnd
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasServer
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** WP6: the production composition in [CanvasGraph]: the helper pieces are bound, the card is live, and every stop path of the graph is the session's termination. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CanvasGraphHelperWiringTest {
    private val core = object : CanvasCore {
        override val api = ApiHandler { HttpResponse.error(404, "not_found", "x") }
        override suspend fun start() {}
        override fun runActive() = false
        override suspend fun cancelActive() {}
        override suspend fun home() = HomeData(emptyList(), emptyList(), false)
        override suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome = CanvasPublisher.Outcome.Blocked(emptyList())
        override suspend fun pipeline(id: String): Pipeline? = null
    }
    private lateinit var h: HelperHarness
    private val dev = MutableStateFlow(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var graph: CanvasGraph
    private var token = ""

    @Before fun setUp() {
        h = HelperHarness(ApplicationProvider.getApplicationContext())
        graph = CanvasGraph(
            h.browserAuth, core, AssetSource { null }, dev, wifiIp = { "192.168.1.20" }, scope = scope, clock = { h.now },
            newServer = { r, lan, t -> CanvasServer(r, lan, t) },
            helperAuth = h.backend.auth, helperApi = h.backend.routes, helperNative = h.backend.native, helperConfirm = h.backend.adapter, helperSession = h.backend.session,
        )
        h.onStop = { graph.stop() }
        token = h.pairHelper()
    }
    @After fun tearDown() { graph.stop(); scope.cancel(); h.close() }

    @Test fun theProductionHostExposesTheBoundNativeActionsAndTheAdapter() {
        assertTrue(graph.helperNative.state().available)
        assertEquals(h.backend.adapter, graph.helperConfirm)
    }

    @Test fun developerModeOffEndsTheHelperSessionAndStopsTheServer() {
        graph.start(false)
        h.startRun(); h.awaitPendingJob()
        dev.value = false
        runBlocking { h.backend.session.awaitEnd() }
        assertFalse(h.backend.grants.isOn(HelperGrants.PY_EXEC))
        assertFalse(h.backend.auth.check(token))
        assertFalse(graph.status.value.on)
        assertEquals(emptyList<Any>(), runBlocking { h.backend.service.pendingConfirmations() })
    }

    @Test fun everyGraphStopPathIsTheSessionTermination() {
        for (stopper in listOf<(CanvasGraph) -> Unit>({ it.terminateSession(SessionEnd.NOTIFICATION_STOP) }, { it.stopWithNotice("x") }, { it.terminateSession(SessionEnd.IDLE_STOP) })) {
            h.close(); tearDownGraph(); setUp()
            graph.start(false)
            stopper(graph)
            runBlocking { h.backend.session.awaitEnd() }
            assertFalse(h.backend.grants.isOn(HelperGrants.PY_EXEC))
            assertFalse(h.backend.auth.check(token))
            assertFalse(graph.status.value.on)
        }
    }

    private fun tearDownGraph() { graph.stop() }

    @Test fun theScreenFlagFeedsTheSessionsInactivityClock() {
        h.now += 3 * 3_600_000L
        graph.screenOpen = true                 // opening and closing the canvas screen is user activity, even between two checks of the session
        graph.screenOpen = false
        h.now += 3 * 3_600_000L
        assertFalse("six hours after the start but three after the last screen moment", h.backend.session.checkExpiry())
        h.now += 3_600_000L
        assertTrue(h.backend.session.checkExpiry())
    }
}
