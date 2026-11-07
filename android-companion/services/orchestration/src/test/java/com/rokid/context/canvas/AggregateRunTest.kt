package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.moment.DaoMomentSource
import com.rokid.context.canvas.moment.DaoSceneRunSource
import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.canvas.moment.MomentFixture
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.canvas.moment.MomentResolver
import com.rokid.context.canvas.moment.SceneBuilder
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.moment.ObjectKind
import com.rokid.context.memory.moment.ObjectRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** `input.scenes -> agg.window -> gate.compare` through the REAL runner: the sensor summary of each Scene feeds a window over the run's Scenes in time order, with no model call. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AggregateRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val fx get() = MomentFixture(h.db)
    private val hour = Math.floorDiv(MomentFixture.MS, 3_600_000L) * 3_600_000L
    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private val modelCalls = java.util.concurrent.atomic.AtomicInteger()
    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))
    private fun sid(sec: Long) = "S" + mid(sec)

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    /** Three Scenes (photos at 0, 400 and 800 s) whose one sensor second has gyroMean 0.2, 0.6 and 1.0; a fourth photo at 1200 s has no sensor row. */
    private suspend fun seed() {
        val secs = listOf(0L, 400L, 800L, 1200L)
        for (s in secs) fx.frame("f$s", t(s) + 5)
        MomentBuilder(h.db, enabled = { true }, clock = { 5 }).build(secs.map(::mid))
        listOf(0L to 0.2, 400L to 0.6, 800L to 1.0).forEach { (s, g) ->
            h.db.momentDao().replaceSensor(mid(s), listOf(MomentSensorEntity(mid(s), t(s) / 1000, "trace-aaaa-0001", 1, "OFFSET_ESTIMATED", 40, g, g, 0.0, 0.0, 0.0, 0.0, 0.0, "STILL", null, 5, 5)), 1, "build")
        }
        SceneBuilder(h.db, enabled = { true }, clock = { 5 }).rebuildWindow(t(0), t(3599))
    }

    private fun resolver() = MomentResolver(DaoMomentSource(h.dao, h.dao3))
    private fun runner() = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) },
        mapOf(NodeTypes.AGG_WINDOW to WindowAggregateExecutor, NodeTypes.COMPARE to CompareExecutor),
        { id ->
            val ai = com.rokid.context.orchestration.TestAi.graph(app) { modelCalls.incrementAndGet(); "" }
            RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive)
        },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, moments = resolver(), scenes = DaoSceneRunSource(h.db, resolver()),
    )
    private fun pipeline(op: String = "mean", window: Int = 2) = Pipeline(2, "ap", "P", listOf(
        PipelineNode("scenes", NodeTypes.INPUT_SCENES, "scenes", JsonObject(emptyMap())),
        PipelineNode("agg", NodeTypes.AGG_WINDOW, "Window", buildJsonObject { put("feature", "gyroMean"); put("op", op); put("window", window) }),
        PipelineNode("cmp", NodeTypes.COMPARE, "Compare", buildJsonObject { put("operator", ">="); put("cutoff", 0.5) }),
    ), listOf(PipelineEdge("scenes", "agg"), PipelineEdge("agg", "cmp")))

    private suspend fun runAll(r: CanvasRunner, p: Pipeline, ids: List<String>): String {
        val e = r.estimateScenes(p, ids, label) as Estimate.Ok
        assertEquals("a window and a compare cost nothing", 0, e.nominal); assertEquals(0, e.max); assertTrue(e.models.isEmpty())
        val id = r.startScenes(p, "ap", null, ids, e.confirmToken, h.scope)
        h.finish(r); return id
    }
    private fun value(items: List<com.rokid.context.memory.db.CanvasRunItemEntity>, node: String, scene: Long) =
        items.first { it.nodeId == node && it.frameId == "scene:${sid(scene)}" }

    @Test fun aSlidingMeanOfEachScenesGyroThenAYesOrNoPerScene() = runBlocking<Unit> {
        seed()
        val id = runAll(runner(), pipeline(), listOf(sid(0), sid(400), sid(800)))
        assertEquals("DONE", h.dao.run(id)!!.status)
        val items = h.dao.items(id)
        fun mean(sc: Long) = Json.parseToJsonElement(value(items, "agg", sc).outputJson!!).jsonObject.getValue("value").jsonObject.getValue("value").jsonPrimitive.double
        assertEquals(0.2, mean(0), 1e-9); assertEquals(0.4, mean(400), 1e-9); assertEquals(0.8, mean(800), 1e-9)
        assertEquals(listOf("no", "no", "yes"), listOf(0L, 400L, 800L).map { value(items, "cmp", it).branch })
        assertEquals(0, modelCalls.get()); assertEquals(0, h.dao.run(id)!!.calls)
    }

    @Test fun aSceneWithoutSensorDataIsANoDataErrorNotAZero() = runBlocking<Unit> {
        seed()
        val id = runAll(runner(), pipeline(window = 1), listOf(sid(800), sid(1200)))
        val items = h.dao.items(id)
        assertEquals("OK", value(items, "agg", 800).status)
        assertEquals("NO_DATA", value(items, "agg", 1200).error)
        assertTrue("nothing flows on from an error", items.none { it.nodeId == "cmp" && it.frameId == "scene:${sid(1200)}" })
    }

    @Test fun forgettingAMemberPhotoDeletesTheAggregateRowsOfThatSceneOnly() = runBlocking<Unit> {
        seed()
        val id = runAll(runner(), pipeline(), listOf(sid(0), sid(400)))
        assertEquals(6, h.dao.items(id).size)
        val forget = ForgetService(h.db, ProposalRepository(h.db.proposalDao(), h.db.actionDao(), h.db)) { h.now }
        assertTrue(forget.forgetMoment("f0").framesToDelete.isNotEmpty())
        assertEquals(setOf("scene:${sid(400)}"), h.dao.items(id).map { it.frameId }.toSet())
    }
}
