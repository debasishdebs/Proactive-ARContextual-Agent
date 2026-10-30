package com.rokid.context.orchestration.nudge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.NumberCompare
import com.rokid.context.ai.canvas.WindowAggregate
import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.core.model.DeliveryRoute
import com.rokid.context.core.model.DeviceCapabilities
import com.rokid.context.core.model.GlassesReach
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.orchestration.FlowPlans
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * v0.9.8 slice C: the engine under a Nudge flow rooted at a Moment, Scene, signal or schedule trigger. Every existing gate still applies per trigger; the per-pipeline rate limit,
 * the trigger match, the pause, the image query and the delivery targets are the new parts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NudgeTriggerEngineTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.of(2026, 10, 12, 18, 0).atZone(zone).toInstant().toEpochMilli()
    private var hud: NudgeHud? = NudgeHud(null, Verbosity.MEDIUM, zone)
    private var cap = 6
    private var calls = 0
    private var n = 0
    private var plan: FlowPlan? = null
    private var paused = false
    private var reach = GlassesReach.UP
    private var caps = com.rokid.context.core.model.DeviceCapabilities.ALL
    private var image: FloatArray? = null
    private var imageFrame = "f1"
    private var imageAsked = 0
    private var signalValues: List<Double?> = emptyList()
    private val meta = MemoryTriggerMeta()
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        // every reply is a different text, so only the gates under test can drop a run
        ai = TestAi.graph(ctx) { calls++; """{"shouldNudge":true,"text":"Milk is nearly out ${++n}","why":"w","sourceIds":["rokid_mem_1"],"priority":"normal"}""" }
        TestAi.bindAll(ai)
        runBlocking { MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, now - 3_600_000L, "Milk carton nearly empty")) }
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun planOf(trigger: String, target: String = "both", perHour: Int = 4, signal: FlowPlan.Signal? = null) =
        FlowPlan.BUILT_IN_NUDGE.copy(trigger = trigger, maxPerHour = perHour, target = target, signal = signal)

    private fun engine(): NudgeEngine {
        val mem = MemoryGraph.get(ctx)
        val seams = TriggerSeams(
            meta, { _, _, _ -> signalValues }, TriggerImages { imageAsked++; image?.let { v -> TriggerPhoto(imageFrame, "s1", v) } }, { paused }, { reach }, { now }, { caps },
        )
        return NudgeEngine(
            NudgeInputs(mem, ContextSourceProvider { _, _ -> emptyList() }) { zone }, ai.runner, NudgeGates(mem.db.nudgeGateDao(), { 0L }) { cap }, mem,
            ProposalIntake { _, _, _, _ -> "p" }, clock = { now }, hud = { hud }, flows = FlowPlans { plan }, triggers = seams,
        )
    }

    private suspend fun rows() = MemoryGraph.get(ctx).nudges.recent(10).first()
    private suspend fun traces(state: String) = MemoryGraph.get(ctx).traces.recent(20).filter { it.state == state }

    @Test fun aFlowRunsOnlyOnItsOwnTrigger() = runBlocking {
        for (t in listOf(NodeTypes.TRIGGER_MOMENT, NodeTypes.TRIGGER_SCENE, NodeTypes.TRIGGER_SCHEDULE)) {
            plan = planOf(t)
            assertEquals(NudgeRun.NotThisTrigger, engine().runOnce(NodeTypes.TRIGGER_SUMMARIZER))
            assertEquals(NudgeRun.NotThisTrigger, engine().runOnce(NodeTypes.TRIGGER_SIGNAL))
        }
        assertEquals("nothing was read or called", 0, calls)
        plan = planOf(NodeTypes.TRIGGER_MOMENT)
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_MOMENT) is NudgeRun.Shown)
        // the built-in flow (no plan) ignores the event triggers and still runs on the summarizer pass
        plan = null
        assertEquals(NudgeRun.NotThisTrigger, engine().runOnce(NodeTypes.TRIGGER_MOMENT))
        assertTrue(engine().runOnce() is NudgeRun.Shown)
    }

    @Test fun everyTriggerGoesThroughTheSameGates() = runBlocking {
        for (t in listOf(NodeTypes.TRIGGER_MOMENT, NodeTypes.TRIGGER_SCENE, NodeTypes.TRIGGER_SCHEDULE)) {
            plan = planOf(t)
            hud = NudgeHud(QuietHours(17 * 60, 19 * 60), Verbosity.MAX, zone)
            assertEquals(t, NudgeRun.Dropped(Gate.QUIET_HOURS), engine().runOnce(t))
            hud = NudgeHud(null, Verbosity.MEDIUM, zone); cap = 0
            assertEquals(t, NudgeRun.Dropped(Gate.OFF), engine().runOnce(t))
            cap = 6; hud = null
            assertEquals(t, NudgeRun.NoHud, engine().runOnce(t))
            hud = NudgeHud(null, Verbosity.MEDIUM, zone)
        }
        assertEquals(0, calls)
    }

    @Test fun dailyCapDuplicateAndForgetStillHoldForATrigger() = runBlocking {
        plan = planOf(NodeTypes.TRIGGER_MOMENT, perHour = 12); cap = 1
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_MOMENT) is NudgeRun.Shown)
        assertEquals(NudgeRun.Dropped(Gate.CAP_REACHED), engine().runOnce(NodeTypes.TRIGGER_MOMENT))
        // the Forget fence: a source forgotten while the model runs drops the nudge
        cap = 6
        val g = MemoryGraph.get(ctx)
        ai = TestAi.graph(ctx) { com.rokid.context.memory.forget.ForgetService(g.db, g.proposals).forgetFact("rokid_mem_1"); """{"shouldNudge":true,"text":"Milk again","why":"w","sourceIds":["rokid_mem_1"],"priority":"normal"}""" }
        TestAi.bindAll(ai)
        assertEquals(NudgeRun.Forgotten, engine().runOnce(NodeTypes.TRIGGER_MOMENT))
        assertEquals(1, rows().size)
    }

    @Test fun aBurstyTriggerIsRateLimitedBeforeAnyModelCall() = runBlocking {
        plan = planOf(NodeTypes.TRIGGER_MOMENT, perHour = 2)
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_MOMENT) is NudgeRun.Shown); now += 60_000
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_MOMENT) is NudgeRun.Shown); now += 60_000
        assertEquals(NudgeRun.RateLimited, engine().runOnce(NodeTypes.TRIGGER_MOMENT)); now += 60_000
        assertEquals(NudgeRun.RateLimited, engine().runOnce(NodeTypes.TRIGGER_MOMENT))
        assertEquals("two model calls, not four", 2, calls)
        assertEquals(2, traces("NUDGE_DROPPED").count { it.note == "TRIGGER_RATE" })
        assertEquals(2, rows().size)
        // the budget is the trigger's: a schedule flow would start with its own; and an hour later this one runs again
        now += 3_600_000L
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_MOMENT) is NudgeRun.Shown)
    }

    @Test fun aGatedRunDoesNotSpendTheRateBudget() = runBlocking {
        plan = planOf(NodeTypes.TRIGGER_SCENE, perHour = 1)
        hud = NudgeHud(QuietHours(17 * 60, 19 * 60), Verbosity.MAX, zone)
        repeat(3) { assertEquals(NudgeRun.Dropped(Gate.QUIET_HOURS), engine().runOnce(NodeTypes.TRIGGER_SCENE)) }
        hud = NudgeHud(null, Verbosity.MEDIUM, zone)
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_SCENE) is NudgeRun.Shown)
    }

    /** v0.9.8 E: one pause gate for every trigger (the summarizer pass used to ignore it); the default Nudge still runs when nothing is paused. */
    @Test fun aPauseStopsEveryTriggerIncludingTheSummarizerPass() = runBlocking {
        paused = true
        plan = planOf(NodeTypes.TRIGGER_MOMENT)
        assertEquals(NudgeRun.Paused, engine().runOnce(NodeTypes.TRIGGER_MOMENT)); assertEquals(0, calls)
        plan = null
        assertEquals(NudgeRun.Paused, engine().runOnce()); assertEquals(NudgeRun.Paused, engine().runOnce(NodeTypes.TRIGGER_SUMMARIZER, builtInOnly = true))
        assertEquals("no model call, no row", 0, calls); assertTrue(rows().isEmpty())
        paused = false
        assertTrue("the default Nudge keeps working when not paused", engine().runOnce() is NudgeRun.Shown)
    }

    @Test fun theSignalTriggerFiresOnTheEdgeThroughTheGates() = runBlocking {
        val signal = FlowPlan.Signal(WindowAggregate.Settings("gyroMean", "mean", 3), NumberCompare.Settings(">=", 0.5))
        plan = planOf(NodeTypes.TRIGGER_SIGNAL, signal = signal)
        signalValues = listOf(0.1, 0.1, 0.1)
        assertEquals(NudgeRun.NoSignal, engine().runOnce(NodeTypes.TRIGGER_SIGNAL)); assertEquals(0, calls)
        signalValues = listOf(0.9, 0.9, 0.9)
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_SIGNAL) is NudgeRun.Shown)
        assertEquals("still high: not a new edge", NudgeRun.NoSignal, engine().runOnce(NodeTypes.TRIGGER_SIGNAL))
        // the edge is consumed even when a gate drops the run
        signalValues = listOf(0.0, 0.0, 0.0); engine().runOnce(NodeTypes.TRIGGER_SIGNAL)
        signalValues = listOf(0.9, 0.9, 0.9); cap = 0
        assertEquals(NudgeRun.Dropped(Gate.OFF), engine().runOnce(NodeTypes.TRIGGER_SIGNAL))
        assertEquals(NudgeRun.NoSignal, engine().runOnce(NodeTypes.TRIGGER_SIGNAL))
    }

    @Test fun aMomentOrSceneUsesTheTriggersImageButAScheduleDoesNot() = runBlocking {
        image = floatArrayOf(0.1f, 0.2f)
        plan = planOf(NodeTypes.TRIGGER_MOMENT); engine().runOnce(NodeTypes.TRIGGER_MOMENT)
        plan = planOf(NodeTypes.TRIGGER_SCENE); engine().runOnce(NodeTypes.TRIGGER_SCENE)
        assertEquals(2, imageAsked)
        plan = planOf(NodeTypes.TRIGGER_SCHEDULE); engine().runOnce(NodeTypes.TRIGGER_SCHEDULE)
        plan = planOf(NodeTypes.TRIGGER_MOMENT).copy(imageQuery = false); engine().runOnce(NodeTypes.TRIGGER_MOMENT)
        assertEquals("a schedule has no photo, and a flow can switch the image off", 2, imageAsked)
        // an image seam that throws is no image, not a failed nudge
        plan = planOf(NodeTypes.TRIGGER_MOMENT)
        val mem = MemoryGraph.get(ctx)
        val e = NudgeEngine(NudgeInputs(mem, ContextSourceProvider { _, _ -> emptyList() }) { zone }, ai.runner, NudgeGates(mem.db.nudgeGateDao(), { 0L }) { cap }, mem,
            ProposalIntake { _, _, _, _ -> "p" }, clock = { now }, hud = { hud }, flows = FlowPlans { plan }, triggers = TriggerSeams(images = TriggerImages { throw IllegalStateException("x") }))
        cap = 6; now += 3_600_000L
        assertTrue(e.runOnce(NodeTypes.TRIGGER_MOMENT) is NudgeRun.Shown)
    }

    @Test fun deliveryTargetsRouteTheNudgeOnceAndNeverSilently() = runBlocking {
        fun shownOn(target: String): String { plan = planOf(NodeTypes.TRIGGER_MOMENT, target = target, perHour = 12); now += 3_600_000L; runBlocking { engine().runOnce(NodeTypes.TRIGGER_MOMENT) }; return runBlocking { rows().first().shownOn } }
        reach = GlassesReach.UP
        assertEquals("phone", shownOn("phone"))
        assertEquals("toast", shownOn("glasses"))
        assertEquals("both is today's verbosity routing", "glance", shownOn("both"))
        hud = NudgeHud(null, Verbosity.MAX, zone); assertEquals("toast", shownOn("both"))
        assertTrue(traces("NUDGE_DELIVERY_FALLBACK").isEmpty())
        // glasses not connected: the phone, with the reason on record, still one row
        reach = GlassesReach.DOWN
        val before = rows().size
        assertEquals("phone", shownOn("glasses"))
        assertEquals(before + 1, rows().size)
        assertEquals(listOf(DeliveryRoute.GLASSES_NOT_CONNECTED), traces("NUDGE_DELIVERY_FALLBACK").map { it.note })
    }

    @Test fun anExplicitGlassesTargetNeverRaisesSilentAndTheReasonIsRecorded() = runBlocking {
        reach = GlassesReach.UP
        hud = NudgeHud(null, Verbosity.SILENT, zone)
        plan = planOf(NodeTypes.TRIGGER_MOMENT, target = "glasses", perHour = 12)
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_MOMENT) is NudgeRun.Shown)
        assertEquals("phone", rows().first().shownOn)
        assertEquals(listOf(NudgeEngine.VERBOSITY_SILENT), traces("NUDGE_DELIVERY_FALLBACK").map { it.note })
    }

    @Test fun aQueuedOccurrenceOfAReplacedPublicationIsDroppedAndCounted() = runBlocking {
        plan = planOf(NodeTypes.TRIGGER_SCHEDULE).copy(scope = "pub2")
        assertEquals(NudgeRun.StalePublication, engine().runOnce(NodeTypes.TRIGGER_SCHEDULE, scope = "pub1"))
        assertEquals(0, calls)
        assertEquals(listOf("STALE_PUBLICATION"), traces("NUDGE_DROPPED").map { it.note })
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_SCHEDULE, scope = "pub2") is NudgeRun.Shown)
        assertEquals("pub1", NudgeTrigger.scopeOf(NodeTypes.TRIGGER_SCHEDULE, "pub1_1700000000000"))
        assertNull(NudgeTrigger.scopeOf(NodeTypes.TRIGGER_MOMENT, null))
    }

    @Test fun aSwitchedOffDisplayIsNotASurfaceAndTheFallbackIsRecorded() = runBlocking {
        fun shownOn(target: String): String { plan = planOf(NodeTypes.TRIGGER_MOMENT, target = target, perHour = 12); now += 1_900_000L; runBlocking { engine().runOnce(NodeTypes.TRIGGER_MOMENT) }; return runBlocking { rows().first().shownOn } }
        reach = GlassesReach.UP; cap = 20
        hud = NudgeHud(null, Verbosity.MAX, zone)
        caps = caps.with("glasses", "display", false)
        assertEquals("phone", shownOn("glasses")); assertEquals("phone", shownOn("both")); assertEquals("phone", shownOn("phone"))
        assertEquals(listOf(DeviceCapabilities.GLASSES_DISPLAY_OFF, DeviceCapabilities.GLASSES_DISPLAY_OFF), traces("NUDGE_DELIVERY_FALLBACK").map { it.note })
        // the phone display off: a phone target moves to the glasses HUD, both keeps the user's verbosity routing
        caps = com.rokid.context.core.model.DeviceCapabilities.ALL.with("phone", "display", false)
        assertEquals("toast", shownOn("phone")); assertEquals("toast", shownOn("both"))
        assertEquals(2, traces("NUDGE_DELIVERY_FALLBACK").count { it.note == DeviceCapabilities.PHONE_DISPLAY_OFF })
        // no display at all: still one stored row, on the phone, with the reason
        caps = caps.with("glasses", "display", false)
        assertEquals("phone", shownOn("phone")); assertEquals(1, traces("NUDGE_DELIVERY_FALLBACK").count { it.note == DeviceCapabilities.NO_DISPLAY })
        // SILENT is a ceiling: a switched-off phone display never raises it to the HUD; no display at all is recorded even when SILENT
        hud = NudgeHud(null, Verbosity.SILENT, zone)
        caps = com.rokid.context.core.model.DeviceCapabilities.ALL.with("phone", "display", false)
        val rowsBefore = rows().size; val offBefore = traces("NUDGE_DELIVERY_FALLBACK").count { it.note == DeviceCapabilities.PHONE_DISPLAY_OFF }
        assertEquals("phone", shownOn("phone")); assertEquals(rowsBefore + 1, rows().size); assertEquals(offBefore + 1, traces("NUDGE_DELIVERY_FALLBACK").count { it.note == DeviceCapabilities.PHONE_DISPLAY_OFF })
        caps = caps.with("glasses", "display", false)
        assertEquals("phone", shownOn("both")); assertEquals(2, traces("NUDGE_DELIVERY_FALLBACK").count { it.note == DeviceCapabilities.NO_DISPLAY })
    }

    @Test fun theRateStampsSurviveARestart() = runBlocking {
        plan = planOf(NodeTypes.TRIGGER_MOMENT, perHour = 1)
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_MOMENT) is NudgeRun.Shown); now += 60_000
        // a new engine on the same stored state (the app restarted): the budget is still spent
        val mem = MemoryGraph.get(ctx)
        val stored = DaoTriggerMeta(mem.db.canvasSlice3Dao())
        stored.put(TriggerRateLimiter.KEY + "|" + NodeTypes.TRIGGER_MOMENT, meta.get(TriggerRateLimiter.KEY + "|" + NodeTypes.TRIGGER_MOMENT)!!)
        val restarted = NudgeEngine(NudgeInputs(mem, ContextSourceProvider { _, _ -> emptyList() }) { zone }, ai.runner, NudgeGates(mem.db.nudgeGateDao(), { 0L }) { cap }, mem,
            ProposalIntake { _, _, _, _ -> "p" }, clock = { now }, hud = { hud }, flows = FlowPlans { plan }, triggers = TriggerSeams(meta = DaoTriggerMeta(mem.db.canvasSlice3Dao()), clock = { now }))
        assertEquals(NudgeRun.RateLimited, restarted.runOnce(NodeTypes.TRIGGER_MOMENT))
        assertNull(rows().firstOrNull { it.text.endsWith("2") })
    }


    @Test fun aFailingPlanLookupRunsTheBuiltInFlowEvenForAnEventTrigger() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        val e = NudgeEngine(NudgeInputs(mem, ContextSourceProvider { _, _ -> emptyList() }) { zone }, ai.runner, NudgeGates(mem.db.nudgeGateDao(), { 0L }) { cap }, mem,
            ProposalIntake { _, _, _, _ -> "p" }, clock = { now }, hud = { hud }, flows = FlowPlans { throw IllegalStateException("db") })
        assertTrue(e.runOnce(NodeTypes.TRIGGER_MOMENT) is NudgeRun.Shown)
        // a queued built-in fallback runs the built-in flow even when the lookup has recovered to an event-rooted plan
        plan = planOf(NodeTypes.TRIGGER_MOMENT); now += 3_600_000L
        assertTrue(engine().runOnce(NodeTypes.TRIGGER_SUMMARIZER, builtInOnly = true) is NudgeRun.Shown)
    }

    @Test fun theQueryPhotoIsFencedAndStoredAsADependency() = runBlocking {
        image = floatArrayOf(0.1f)
        MemoryGraph.get(ctx).db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity("f1", null, null, "STILL", false, null, null, null, null, null, null, null, null, null, 1_000))
        plan = planOf(NodeTypes.TRIGGER_MOMENT)
        val run = engine().runOnce(NodeTypes.TRIGGER_MOMENT)
        assertTrue(run is NudgeRun.Shown)
        assertTrue(rows().single().sourceIdsJson, rows().single().sourceIdsJson.contains("frame:f1"))
    }
}
