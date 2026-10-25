package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.PlanShape
import com.rokid.context.core.model.context.WatchSample
import com.rokid.context.core.model.context.WatchState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `src.watch_motion` (Source, WATCH lane): the items it hands a run, its registry entry and where the validator lets it sit. */
class WatchSourceTest {
    private val none = JsonObject(emptyMap())
    private fun s(at: Long, st: WatchState = WatchState.WALKING, c: Float = 1f) = WatchSample(at, st, c)
    private fun n(id: String, type: String) = PipelineNode(id, type, id, none)
    private fun pipe(nodes: List<PipelineNode>, edges: List<PipelineEdge>) = Pipeline(2, "w", "W", nodes, edges)

    // ---- the items ----

    @Test fun oneItemPerScoredValueOldestFirstWithTheScoreWhereTheWindowReadsIt() {
        val items = WatchMotion.items(listOf(s(300, WatchState.RUNNING), s(100, WatchState.STILL), s(200, WatchState.WALKING, 0.8f)))
        assertEquals(listOf("watch:100", "watch:200", "watch:300"), items.map { it.key })
        val j = items[1].json
        assertEquals("watch", j["itemType"]!!.jsonPrimitive.content); assertEquals("WALKING", j["state"]!!.jsonPrimitive.content)
        assertEquals(0.4, j["score"]!!.jsonPrimitive.doubleOrNull!!, 1e-6)
        assertEquals(j["score"], j["sensor"]!!.jsonObject["watchMotion"])
        assertEquals("a still watch is a REAL zero", 0.0, items[0].json["score"]!!.jsonPrimitive.doubleOrNull!!, 0.0)
    }

    @Test fun anUnknownValueIsNotAMeasurementAndNoValueIsNoItem() {
        assertTrue(WatchMotion.items(listOf(s(1, WatchState.UNKNOWN))).isEmpty())
        assertTrue(WatchMotion.items(emptyList()).isEmpty())
        assertEquals(WatchMotion.NO_DATA_KEY, WatchMotion.binding(emptyList()))
    }

    @Test fun oneItemPerInstantAndTheBindingChangesWithAnyScore() {
        assertEquals(1, WatchMotion.items(listOf(s(5), s(5, WatchState.RUNNING))).size)
        val a = WatchMotion.binding(WatchMotion.items(listOf(s(1), s(2))))
        assertEquals(a, WatchMotion.binding(WatchMotion.items(listOf(s(2), s(1)))))
        assertTrue(a != WatchMotion.binding(WatchMotion.items(listOf(s(1), s(2, WatchState.RUNNING)))))
        assertTrue(a != WatchMotion.binding(WatchMotion.items(listOf(s(1), s(2), s(3)))))
    }

    // ---- registry ----

    @Test fun registryEntryIsASourceInTheWatchLane() {
        val t = NodeTypes.byType(NodeTypes.SRC_WATCH_MOTION); assertNotNull(t); t!!
        assertEquals(Family.SOURCE, t.family); assertEquals(Domain.WATCH, t.domain); assertEquals(PortType.WATCH, t.output)
        assertTrue(t.root); assertTrue(t.inputs.isEmpty()); assertTrue(t.palette); assertEquals(2, t.sinceSchema); assertEquals(0, t.cost.modelCalls)
        assertNull(NodeTypes.resolve(NodeTypes.SRC_WATCH_MOTION, 1))
        assertEquals("the lane order is watch first", Domain.WATCH, Domain.entries.first())
    }

    @Test fun theWindowAggregateTakesWatchValuesAndReadsTheirScore() {
        val t = NodeTypes.byType(NodeTypes.AGG_WINDOW)!!
        assertTrue(PortType.WATCH in t.inputs)
        assertTrue("watchMotion" in WindowAggregate.FEATURES)
        val item = WatchMotion.items(listOf(s(1, WatchState.RUNNING))).single().json
        assertEquals(1.0, WindowAggregate.featureOf(item, "watchMotion")!!, 0.0)
    }

    // ---- validator ----

    @Test fun watchToAggregateToCompareIsValid() {
        val p = pipe(listOf(n("w", NodeTypes.SRC_WATCH_MOTION), n("a", NodeTypes.AGG_WINDOW), n("c", NodeTypes.COMPARE)), listOf(PipelineEdge("w", "a"), PipelineEdge("a", "c")))
        assertEquals(emptyList<String>(), PipelineValidator.validate(p))
    }

    @Test fun theWatchMayHandOverToTheAsyncHalfButNotToTheGlassesOrPhoneLanes() {
        val toGlasses = pipe(listOf(n("w", NodeTypes.SRC_WATCH_MOTION), n("h", NodeTypes.IMU_HEAD_MOTION)), listOf(PipelineEdge("w", "h")))
        assertTrue(PipelineValidator.validate(toGlasses).any { it.contains("crosses domains") })
        val toPhone = pipe(listOf(n("w", NodeTypes.SRC_WATCH_MOTION), n("p", NodeTypes.HUB_CONTEXT)), listOf(PipelineEdge("w", "p")))
        assertTrue(PipelineValidator.validate(toPhone).any { it.contains("crosses domains") })
    }

    @Test fun nothingMayFlowIntoTheWatchAndItIsTheOnlyInputOfTheAsyncHalf() {
        val into = pipe(listOf(n("w", NodeTypes.SRC_WATCH_MOTION), n("a", NodeTypes.AGG_WINDOW)), listOf(PipelineEdge("w", "a"), PipelineEdge("a", "w")))
        assertTrue(PipelineValidator.validate(into).isNotEmpty())
        val two = pipe(listOf(n("w", NodeTypes.SRC_WATCH_MOTION), n("m", NodeTypes.INPUT_MOMENTS), n("a", NodeTypes.AGG_WINDOW)), listOf(PipelineEdge("w", "a"), PipelineEdge("m", "a")))
        assertTrue(PipelineValidator.validate(two).any { it.contains("exactly one input root") })
        val noWatch = pipe(listOf(n("a", NodeTypes.AGG_WINDOW)), emptyList())
        assertTrue("an async half still needs its one input", PipelineValidator.validate(noWatch).any { it.contains("exactly one input") })
    }

    @Test fun anyGraphHoldingTheWatchNeverPublishes() {
        val p = pipe(listOf(n("w", NodeTypes.SRC_WATCH_MOTION), n("a", NodeTypes.AGG_WINDOW)), listOf(PipelineEdge("w", "a")))
        assertEquals(listOf("w"), PlanShape.tier3Nodes(p))
        assertFalse(PlanShape.tier3Nodes(pipe(listOf(n("a", NodeTypes.AGG_WINDOW)), emptyList())).isNotEmpty())
    }
}
