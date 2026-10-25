package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.ai.canvas.plan.PlanShape
import com.rokid.context.capture.plan.PlanResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in `starter-watch-motion`: the watch branch of the generic flow next to the glasses lane, read-only, never active, never published. */
class StarterWatchMotionTest {
    private val chain = listOf("watch", "win", "model", "freq")
    private fun short(p: Pipeline) = PipelineHash.short(PipelineHash.of(p))

    // ---- golden: nothing built before it moved ----

    @Test fun everyEarlierBuiltInKeepsItsHashAndItsPlan() {
        assertEquals("live-extract", "0f331f401992", short(DefaultPipelines.liveExtract()))
        assertEquals("intent-draft", "df5b4cbe2fa3", short(DefaultPipelines.intentDraft("SEED {{recent_context}}")))
        assertEquals("default-capture", "5f4b63ac85c4", short(DefaultPipelines.defaultCapture()))
        assertEquals("starter-capture-extract", "1557fc30f2ff", short(DefaultPipelines.starter()))
        for (p in listOf(DefaultPipelines.defaultCapture(), DefaultPipelines.starter(), DefaultPipelines.starterMomentSummary())) assertEquals(p.id, "f5782e0d8610", PlanCompiler.compile(p).plan!!.configHash)
    }

    // ---- the new built-in ----

    @Test fun theChainIsWatchWindowModelFrequencyPreviewAndPassesTheValidator() {
        val p = DefaultPipelines.starterWatchMotion()
        assertEquals("starter-watch-motion", p.id); assertEquals(DefaultPipelines.STARTER_WATCH_MOTION, p.id); assertEquals(PipelineJson.SCHEMA_V2, p.schema)
        assertEquals(emptyList<String>(), PipelineValidator.validate(p))
        val mine = p.nodes.filter { it.id in chain }
        assertEquals(listOf(NodeTypes.SRC_WATCH_MOTION, NodeTypes.AGG_WINDOW, NodeTypes.PROC_MOTION_MODEL, NodeTypes.OUT_SET_FREQUENCY_PREVIEW), chain.map { id -> mine.single { it.id == id }.type })
        assertEquals(setOf("watch" to "win", "win" to "model", "model" to "freq"), p.edges.filter { it.from in chain && it.to in chain }.map { it.from to it.to }.toSet())
        assertTrue(p.edges.filter { it.from in chain }.all { it.`when` == null })
    }

    @Test fun theWindowReadsTheWatchScoreAndTheModelAndPreviewKeepTheirDefaults() {
        val p = DefaultPipelines.starterWatchMotion()
        assertEquals(WindowAggregate.Settings("watchMotion", "max", 5), WindowAggregate.settings(p.nodes.single { it.id == "win" }.params))
        assertEquals(MotionModel.Settings(), MotionModel.settings(p.nodes.single { it.id == "model" }.params))
        assertEquals(SetFrequencyPreview.Settings(), SetFrequencyPreview.settings(p.nodes.single { it.id == "freq" }.params))
    }

    @Test fun theGlassesLaneIsDefaultCaptureUntouchedAndStillCompilesToTheIdentityPlan() {
        val p = DefaultPipelines.starterWatchMotion(); val dc = DefaultPipelines.defaultCapture()
        assertTrue(p.nodes.containsAll(dc.nodes)); assertTrue(p.edges.containsAll(dc.edges))
        assertEquals(dc.nodes.size + chain.size, p.nodes.size); assertEquals(dc.edges.size + 3, p.edges.size)
        val r = PlanCompiler.compile(p)
        assertEquals(emptyList<String>(), r.problems)
        assertEquals("f5782e0d8610", r.plan!!.configHash); assertEquals(PlanResolver.identity(), r.plan!!.config)
        assertEquals("the plan bytes are the plan of the glasses lane alone: the watch side adds nothing to them", PlanCompiler.compile(dc).plan!!.config, r.plan!!.config)
    }

    @Test fun theThreeLanesAreWatchGlassesAndPhone() {
        val p = DefaultPipelines.starterWatchMotion()
        val lanes = p.nodes.map { n -> NodeTypes.domainOf(n, NodeTypes.resolve(n.type, p.schema)!!) }.toSet()
        assertEquals(setOf(Domain.WATCH, Domain.RT_GLASSES, Domain.RT_PHONE, Domain.ASYNC), lanes)
        assertEquals(Domain.WATCH, NodeTypes.domainOf(p.nodes.single { it.id == "watch" }, NodeTypes.byType(NodeTypes.SRC_WATCH_MOTION)!!))
    }

    @Test fun itCanNeverBePublishedOrBeAnInterpreter() {
        val p = DefaultPipelines.starterWatchMotion()
        assertEquals(listOf("freq", "watch"), PlanShape.tier3Nodes(p))
        assertFalse(PlanShape.hasAsyncHalf(p))
        assertTrue(InterpretableShape.problems(p).isNotEmpty())
    }
}
