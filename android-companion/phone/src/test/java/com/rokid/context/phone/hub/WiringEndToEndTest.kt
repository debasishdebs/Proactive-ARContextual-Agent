package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.plan.GlassesState
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.MarkAck
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.Wire
import com.rokid.context.data.frames.DirSync
import com.rokid.context.link.FakeGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.GlassesEvent
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.model.ModelProvider
import com.rokid.context.phone.canvas.MarkGraph
import com.rokid.context.phone.canvas.PlanGraph
import com.rokid.context.phone.hub.plan.PlanSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Task 33: the composed paths over a REAL [Hub] with a fake glasses link, the real [PlanSender], the real [PlanGraph] (and its publish groups) and the real [MarkRecorder] over
 * a real database. The two lambdas under test are the ones [HubGraph] gives the hub ([PlanGraph.feedAck] after the sender; the mark recorder).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WiringEndToEndTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx).also { MemoryGraph.resetForTests(it); MarkGraph.resetForTests() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val link = FakeGlassesLink()
    private val install = "inst-1"
    private val prefs = ctx.getSharedPreferences("wiring_e2e_plan", 0).also { it.edit().clear().commit() }
    private val phoneRt = ArrayList<String?>()
    init { DirSync.testOverride = DirSync { true } }
    @After fun close() { scope.cancel(); DirSync.testOverride = null; PlanGraph.installForTests(null); AiGraph.resetForTests(null); MarkGraph.resetForTests(); db.close(); MemoryGraph.resetForTests(null); prefs.edit().clear().commit() }

    private val sources = object : HubSources {
        override suspend fun today() = listOf<MemoryLine>()
        override suspend fun queueDepth() = 0
        override suspend fun aiHealth(nowMs: Long): Health = Health.Ok
    }
    private val sender = PlanSender(link, prefs, { install })
    private lateinit var graph: PlanGraph
    private lateinit var hub: Hub

    private fun build() {
        val ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ -> object : ModelProvider { override suspend fun generate(prompt: String) = "{}" } }).also { AiGraph.resetForTests(it) }
        val publisher = CanvasPublisher(db.canvasDao(), ai.prompts, { _, _ -> emptyList() }, System::currentTimeMillis, CanvasPublisher.inTransaction(db))
        graph = PlanGraph(db.canvasDao(), db.canvasSlice3Dao(), publisher, sender, { install }, { GlassesState.LINKED_CAPABLE }, { phoneRt += it }, scope, tickEveryMs = 60_000L)
        PlanGraph.installForTests(graph)
        graph.start()
        val pauses = HubPauses(db.pauseDao())
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ingestor = FrameIngestor(File(tmp.root, "inbox"), FrameReassembler(), FrameAdmission(ledger, pauses), ledger, onStored = {})
        val settings = HubSettingsStore(ctx.getSharedPreferences("wiring_e2e_hub", 0)) { "hud" }
        hub = Hub(
            scope, link, pauses, settings, ingestor, sources,
            phoneInstall = { install },
            onPlanAck = { ack -> sender.onAck(ack); PlanGraph.feedAck(ack) },
            onMark = { m, a -> MarkGraph.recorder(ctx).onMark(m, a) },
            onMarkPhoto = { r -> MarkGraph.recorder(ctx).onPhotoResult(r) },
            onGlassesListenStart = { t -> MarkGraph.recorder(ctx).onListenStart(t) },
            onSessionReady = { start -> sender.onSessionReady(start) },
        )
        hub.start()
    }
    private suspend fun until(c: suspend () -> Boolean) = withTimeout(10_000) { while (!c()) delay(10) }
    private fun sentOn(ch: String) = link.sent.filter { it.first == ch }

    private suspend fun openSession(): Long {
        link.emitReady()
        delay(100)
        link.emit(GlassesEvent.HelloReceived(Hello(glassesBatteryPct = 77, bufferSize = 4, glassesNowMs = System.currentTimeMillis(), pauses = emptyList())))
        delay(100)
        return sessionStart()
    }
    private fun sessionStart(): Long = (sentOn(Channels.CAPTURE_PLAN).lastOrNull()?.let { (Wire.decode<com.rokid.context.core.model.CapturePlanMessage>(it.second) as Decoded.Ok).value.sessionStartMs } ?: sessionStartViaReflection())
    private fun sessionStartViaReflection(): Long = Hub::class.java.getDeclaredField("sessionStartMs").also { it.isAccessible = true }.getLong(hub)

    @Test fun markFlowWiredEndToEnd() = runBlocking {
        build(); openSession()
        link.emit(GlassesEvent.MarkReceived(MarkMessage(epoch = "e9", seq = 4, atMs = System.currentTimeMillis(), atElapsedMs = 5)))
        until { sentOn(Channels.MARK_ACK).isNotEmpty() }
        val ack = (Wire.decode<MarkAck>(sentOn(Channels.MARK_ACK).last().second) as Decoded.Ok).value
        assertEquals(MarkAck(epoch = "e9", seqs = listOf(4L)), ack)
        val labels = db.canvasSlice3Dao().labels()
        assertEquals("one MOMENT row from the tap", 1, labels.size)
        assertEquals("MOMENT", labels.single().kind); assertEquals("tap", labels.single().source)
        assertEquals("the same mark again is the same row", 1, run { link.emit(GlassesEvent.MarkReceived(MarkMessage(epoch = "e9", seq = 4, atMs = System.currentTimeMillis(), atElapsedMs = 5))); delay(300); db.canvasSlice3Dao().labels().size })
    }

    /** v0.9.6 slice C over the real Hub and the real recorder: a mark that will take a photo waits for it; the glasses' failure report is stored; the undo forgets the linked photo through the real Forget path. */
    @Test fun markPhotoFlowWiredEndToEnd() = runBlocking {
        build(); openSession()
        val now = System.currentTimeMillis()
        link.emit(GlassesEvent.MarkReceived(MarkMessage(epoch = "e9", seq = 4, atMs = now, atElapsedMs = 5, photo = true)))
        until { sentOn(Channels.MARK_ACK).isNotEmpty() }
        assertEquals("pending", db.canvasSlice3Dao().label("mk:e9:4")!!.photo)
        link.emit(GlassesEvent.MarkPhotoReceived(com.rokid.context.core.model.MarkPhotoResult(epoch = "e9", seq = 4, reason = "camera_busy")))
        until { db.canvasSlice3Dao().label("mk:e9:4")!!.photo == "camera_busy" }
        // a second mark with its photo linked: the undo forgets the photo (the real ForgetActions path) and the mark with it
        link.emit(GlassesEvent.MarkReceived(MarkMessage(epoch = "e9", seq = 5, atMs = now + 10_000, atElapsedMs = 5, photo = true)))
        until { db.canvasSlice3Dao().label("mk:e9:5") != null }
        db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("pf", now + 11_000, File(tmp.root, "pf.jpg").also { it.writeBytes(byteArrayOf(1)) }.path, "rokid_glasses"))
        db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity("pf", "w_pf", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = now + 11_000, markRef = "e9:5"))
        db.canvasSlice3Dao().linkMarkPhoto("mk:e9:5")
        link.emit(GlassesEvent.MarkReceived(MarkMessage(epoch = "e9", seq = 6, atMs = now + 12_000, atElapsedMs = 5, undo = 5)))
        until { db.canvasSlice3Dao().label("mk:e9:5") == null }
        assertEquals("the photo is forgotten with its mark", 0, db.frameDao().count())
        until { sentOn(Channels.MARK_ACK).any { (Wire.decode<MarkAck>(it.second) as Decoded.Ok).value.seqs == listOf(6L) } } // the undo was acknowledged
    }

    @Test fun publishFlowWiredEndToEnd() = runBlocking {
        build(); val start = openSession()
        // a TRIAL group published through the real groups and the real sender, as the publisher does after its gates
        graph.groups.publish("g1", 7L, PlanMode.TRIAL, "{}", "h1", 3_600_000L)
        val m = sentOn(Channels.CAPTURE_PLAN).map { (Wire.decode<com.rokid.context.core.model.CapturePlanMessage>(it.second) as Decoded.Ok).value }.last { it.publishId == "7" }
        assertEquals("the session stamp and install are the hub's", install, m.phoneInstall)
        assertEquals("SENT", db.canvasSlice3Dao().groupState("g1")!!.state)
        // the glasses answer: the hub classifies it and feeds BOTH the sender and the groups
        link.emit(GlassesEvent.PlanAckReceived(CapturePlanAck(gen = m.gen, publishId = "7", mode = PlanMode.TRIAL, effectiveHash = "h1", state = PlanAckState.EFFECTIVE, phoneInstall = install, sessionStartMs = m.sessionStartMs)))
        until { db.canvasSlice3Dao().groupState("g1")!!.state == "EFFECTIVE" }
        assertEquals(PlanAckState.EFFECTIVE, sender.lastAck.value!!.state)
        assertTrue("phone RT activated after EFFECTIVE", phoneRt.contains("{}"))
        // the trial ends on the glasses: REVERTED is final for the group and the gen is NOT sent again next session
        link.emit(GlassesEvent.PlanAckReceived(CapturePlanAck(gen = m.gen, publishId = "7", mode = PlanMode.TRIAL, state = PlanAckState.REVERTED, reason = "auto:trial_end", phoneInstall = install, sessionStartMs = m.sessionStartMs)))
        until { db.canvasSlice3Dao().groupState("g1")!!.state == "REVERTED" }
        val before = sentOn(Channels.CAPTURE_PLAN).size
        sender.onSessionReady(start + 1)
        assertEquals("a reverted gen is not re-sent", before, sentOn(Channels.CAPTURE_PLAN).size)
    }
}
