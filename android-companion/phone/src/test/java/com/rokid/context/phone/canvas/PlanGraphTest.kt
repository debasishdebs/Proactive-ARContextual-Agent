package com.rokid.context.phone.canvas

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.plan.GlassesState
import com.rokid.context.canvas.plan.PlanTransport
import com.rokid.context.canvas.plan.SendOutcome
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.link.PlanPeer
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasGroupStateEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelProvider
import com.rokid.context.phone.canvas.api.CanvasApi
import com.rokid.context.phone.canvas.api.CanvasBackend
import com.rokid.context.phone.canvas.server.HttpRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Task 33: the publish graph binds the hub's acks, recover() at start, tick(), the gate-5 supplier, the Settings control and the route groups. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PlanGraphTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: com.rokid.context.memory.db.ContextDatabase
    private lateinit var graph: PlanGraph
    private var now = 10_000_000L
    private val sent = ArrayList<Pair<PlanMode, String>>()
    private val phoneRt = ArrayList<String?>()
    private val install = "inst"

    @Before fun setUp() {
        db = DatabaseFactory.inMemory(app).also { MemoryGraph.resetForTests(it) }
        val ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ -> object : ModelProvider { override suspend fun generate(prompt: String) = "{}" } }).also { AiGraph.resetForTests(it) }
        val publisher = CanvasPublisher(db.canvasDao(), ai.prompts, { _, _ -> emptyList() }, { now++ }, CanvasPublisher.inTransaction(db))
        var gen = 0L
        val transport = object : PlanTransport {
            override suspend fun send(mode: PlanMode, planJson: String?, configHash: String?, publishId: String, trialMaxMs: Long?): SendOutcome { sent += mode to publishId; return SendOutcome(++gen, true) }
        }
        graph = PlanGraph(db.canvasDao(), db.canvasSlice3Dao(), publisher, transport, { install }, { GlassesState.LINKED_CAPABLE }, { phoneRt += it }, scope, { now }, tickEveryMs = 20L)
    }
    @After fun close() { scope.cancel(); AiGraph.resetForTests(null); db.close(); MemoryGraph.resetForTests(null) }

    private fun row(id: String, state: String, gen: Long = 5, publishId: Long = 9, sentAt: Long? = now, mode: String = "SHADOW") =
        CanvasGroupStateEntity(id, gen, publishId, "h", "capture_plan", state, now, mode, install, sentAt)
    private fun seed(r: CanvasGroupStateEntity) = runBlocking { graph.store.put(r) }
    private fun state(id: String) = runBlocking { graph.store.get(id)?.state }
    private fun await(what: String, c: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!c()) delay(10) } ; Unit }

    @Test fun anAckFedByTheHubMovesTheGroupToEffective() {
        seed(row("g1", "SENT"))
        graph.start()
        graph.onAck(CapturePlanAck(gen = 5, publishId = "9", mode = PlanMode.SHADOW, effectiveHash = "h", state = PlanAckState.EFFECTIVE, phoneInstall = install))
        await("effective") { state("g1") == "EFFECTIVE" }
    }

    @Test fun acksOfAnotherPublishAreIgnored() {
        seed(row("g1", "SENT"))
        graph.start()
        graph.onAck(CapturePlanAck(gen = 5, publishId = "8", mode = PlanMode.SHADOW, effectiveHash = "h", state = PlanAckState.EFFECTIVE, phoneInstall = install))
        runBlocking { delay(200) }
        assertEquals("SENT", state("g1"))
    }

    @Test fun recoverRunsAtStartup() {
        seed(row("g1", "DESIRED"))
        assertEquals("nothing runs before start", "DESIRED", state("g1"))
        graph.start()
        await("failed") { state("g1") == "FAILED" }
    }

    @Test fun anEffectiveGroupActivatesThePhoneHalfAgainAtStartup() {
        seed(row("g1", "EFFECTIVE", mode = "SETTINGS"))
        graph.start()
        await("phone rt") { phoneRt == listOf<String?>(null) }
    }

    @Test fun theTickTimesOutASentGroupWithoutAnAnswer() {
        seed(row("g0", "DESIRED")); graph.start()
        await("recovered first") { state("g0") == "FAILED" }
        seed(row("g1", "SENT", sentAt = now - 11 * 60_000L))   // after recover(): only the periodic tick can move it
        await("unknown") { state("g1") == "UNKNOWN_REMOTE" }
    }

    @Test fun startIsOnce() { assertTrue(graph.start() != null); assertNull(graph.start()) }

    @Test fun gateFiveFollowsTheLinkAndThePeer() {
        assertEquals(GlassesState.LINKED_CAPABLE, PlanGraph.glassesState(LinkState.CONNECTED, PlanPeer.CAPABLE))
        assertEquals(GlassesState.NOT_LINKED, PlanGraph.glassesState(LinkState.OFFLINE, PlanPeer.CAPABLE))
        assertEquals(GlassesState.TOO_OLD, PlanGraph.glassesState(LinkState.CONNECTED, PlanPeer.TOO_OLD))
        assertEquals(GlassesState.UNKNOWN, PlanGraph.glassesState(LinkState.CONNECTED, PlanPeer.UNKNOWN))
    }

    @Test fun useSettingsThroughTheControlSendsSettingsAndTheRowFollows() = runBlocking {
        graph.start()
        assertNull(withTimeout(5_000) { graph.control.active.first() })
        graph.control.useSettings()
        assertEquals(PlanMode.SETTINGS, sent.last().first)
    }

    @Test fun everyRouteGroupIsRegisteredAndTheGatedOnesAreBound() {
        val dao = db.canvasSlice3Dao()
        val dev = MutableStateFlow(true)
        val gate = kotlinx.coroutines.sync.Mutex()
        val fence = { com.rokid.context.memory.db.DaoFence(0, com.rokid.context.memory.db.DeletionEpoch("i", 1, 0), { 0 }, { com.rokid.context.memory.db.DeletionEpoch("i", 1, 0) }) }
        val replay = com.rokid.context.canvas.plan.ReplayService(dao, { null }, fence, scope, dev, jobGate = gate)
        val sweeps = com.rokid.context.canvas.plan.SweepService(dao, { null }, fence, scope, dev, jobGate = gate)
        val extras = CanvasExtras.build(dao, replay, dev, startup = {}, sender = { null }, collect = { null }, plans = { graph.publisher }, m1 = { graph.m1 }, sweeps = sweeps)
        val backend = Proxy.newProxyInstance(CanvasBackend::class.java.classLoader, arrayOf(CanvasBackend::class.java)) { _, m, _ -> error("backend called: ${m.name}") } as CanvasBackend
        val api = CanvasApi(backend, extras = extras.extras)
        fun call(m: String, p: String, body: String = "") = api.handle(HttpRequest(m, p, emptyMap(), emptyMap(), body.toByteArray())).status
        assertEquals(200, call("GET", "/api/sweeps"))
        assertEquals(200, call("GET", "/api/replays"))
        assertEquals(404, call("GET", "/api/sweeps/sw_none"))
        assertEquals("traces", 200, call("GET", "/api/traces"))
        assertEquals("plan status is bound, not 503", 200, call("GET", "/api/plans/status"))
        assertEquals("use-settings is bound", 200, call("POST", "/api/plans/use-settings", "{}"))
        assertEquals("m1 evidence is bound, not 503", 200, call("POST", "/api/plans/m1-evidence", "{}"))
        assertEquals(PlanMode.SETTINGS, sent.last().first)
    }
}
