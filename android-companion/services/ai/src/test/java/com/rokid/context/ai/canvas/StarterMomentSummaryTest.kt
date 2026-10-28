package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.ai.canvas.plan.PlanShape
import com.rokid.context.capture.plan.PlanResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's starter chain on the phone, `Moments -> Prompt -> Summary -> Confidence check -> Save memory (preview)`, next to the glasses lane; and the proof that nothing built-in before it moved. */
class StarterMomentSummaryTest {
    private val seed = "SEED {{recent_context}}"
    private fun short(p: Pipeline) = PipelineHash.short(PipelineHash.of(p))

    // ---- golden: every pipeline that existed before v0.9.4 is byte-identical in encoding, hash and compile ----

    @Test fun everyEarlierBuiltInKeepsItsHashAndItsPlan() {
        assertEquals("live-extract", "0f331f401992", short(DefaultPipelines.liveExtract()))
        assertEquals("intent-draft", "df5b4cbe2fa3", short(DefaultPipelines.intentDraft(seed)))
        assertEquals("default-capture", "5f4b63ac85c4", short(DefaultPipelines.defaultCapture()))
        assertEquals("starter-capture-extract", "1557fc30f2ff", short(DefaultPipelines.starter()))
        assertEquals("f5782e0d8610", PlanCompiler.compile(DefaultPipelines.defaultCapture()).plan!!.configHash)
        assertEquals("f5782e0d8610", PlanCompiler.compile(DefaultPipelines.starter()).plan!!.configHash)
    }

    // ---- the new built-in ----

    private val chain = listOf("moments", "ask", "sum", "trust", "write")

    @Test fun theChainIsMomentsPromptSummaryConfidenceCheckSaveAndPassesTheValidator() {
        val p = DefaultPipelines.starterMomentSummary()
        assertEquals("starter-moment-summary", p.id); assertEquals(DefaultPipelines.STARTER_MOMENT_SUMMARY, p.id)
        assertEquals("Starter: moments to summary", p.name); assertEquals(PipelineJson.SCHEMA_V2, p.schema)
        assertEquals(emptyList<String>(), PipelineValidator.validate(p))
        val phone = p.nodes.filter { it.id in chain }
        assertEquals(listOf(NodeTypes.INPUT_MOMENTS, NodeTypes.PROMPT, NodeTypes.SUMMARY, NodeTypes.LOCAL_CRITIC, NodeTypes.MEMORY_WRITE), chain.map { id -> phone.single { it.id == id }.type })
        assertEquals(
            setOf(Triple("moments", "ask", null), Triple("ask", "sum", null), Triple("sum", "trust", null), Triple("trust", "write", "trusted"), Triple("trust", "write", "unsure")),
            p.edges.filter { it.from in chain && it.to in chain }.map { Triple(it.from, it.to, it.`when`) }.toSet(),
        )
        assertEquals("Confidence check", phone.single { it.id == "trust" }.title)
    }

    @Test fun thePromptIsReadyToRunWithAFixedTemplateAndSchema() {
        val ask = DefaultPipelines.starterMomentSummary().nodes.single { it.id == "ask" }
        assertTrue(ask.params["template"]!!.jsonPrimitive.content.contains("{{context_sources}}"))
        val schema = Json.parseToJsonElement(ask.params["schema"]!!.jsonPrimitive.content).jsonObject
        assertEquals(listOf("description"), schema["required"]!!.let { r -> (r as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content } })
    }

    @Test fun theGlassesLaneIsDefaultCaptureUntouchedAndStillCompilesToTheIdentityPlan() {
        val p = DefaultPipelines.starterMomentSummary(); val dc = DefaultPipelines.defaultCapture()
        assertTrue(p.nodes.containsAll(dc.nodes)); assertTrue(p.edges.containsAll(dc.edges))
        assertEquals(dc.nodes.size + chain.size, p.nodes.size); assertEquals(dc.edges.size + 5, p.edges.size)
        val r = PlanCompiler.compile(p)
        assertEquals(emptyList<String>(), r.problems)
        assertEquals("f5782e0d8610", r.plan!!.configHash); assertEquals(PlanResolver.identity(), r.plan!!.config)
    }

    @Test fun itIsOnlyAReadOnlyPictureForPublishingBecauseMomentsAreTier3() {
        val p = DefaultPipelines.starterMomentSummary()
        assertEquals(listOf("moments"), PlanShape.tier3Nodes(p))
        assertFalse(PlanShape.hasAsyncHalf(p))
        assertTrue(InterpretableShape.problems(p).isNotEmpty())
    }

    @Test fun itIsListedAfterTheOthersSoTheirOrderIsKept() {
        assertEquals(
            listOf(DefaultPipelines.LIVE_EXTRACT, DefaultPipelines.INTENT_DRAFT, DefaultPipelines.DEFAULT_CAPTURE, DefaultPipelines.STARTER, DefaultPipelines.STARTER_MOMENT_SUMMARY, DefaultPipelines.STARTER_WATCH_MOTION, DefaultPipelines.DEFAULT_QUERY, DefaultPipelines.DEFAULT_NUDGE),
            DefaultPipelines.all,
        )
    }
}
