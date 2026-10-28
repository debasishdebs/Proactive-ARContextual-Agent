package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.ai.canvas.plan.PlanShape
import com.rokid.context.ai.flow.flows.CaptureFlow
import com.rokid.context.capture.plan.CanonicalJson
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanResolver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPipelinesTest {
    private val seed = "SEED TEXT {{recent_context}}"

    @Test fun bothDefaultsValidate() {
        assertEquals(emptyList<String>(), PipelineValidator.validate(DefaultPipelines.liveExtract()))
        assertEquals(emptyList<String>(), PipelineValidator.validate(DefaultPipelines.intentDraft(seed)))
        assertEquals(listOf(DefaultPipelines.LIVE_EXTRACT, DefaultPipelines.INTENT_DRAFT, DefaultPipelines.DEFAULT_CAPTURE, DefaultPipelines.STARTER, DefaultPipelines.STARTER_MOMENT_SUMMARY, DefaultPipelines.STARTER_WATCH_MOTION, DefaultPipelines.DEFAULT_QUERY, DefaultPipelines.DEFAULT_NUDGE), DefaultPipelines.all)
        assertEquals(emptyList<String>(), PipelineValidator.validate(DefaultPipelines.starterMomentSummary()))
        assertEquals(emptyList<String>(), PipelineValidator.validate(DefaultPipelines.starterWatchMotion()))
        assertEquals(emptyList<String>(), PipelineValidator.validate(DefaultPipelines.starter()))
        assertEquals(emptyList<String>(), PipelineValidator.validate(DefaultPipelines.defaultCapture()))
    }

    @Test fun defaultCaptureCompilesToTheIdentityPlan() {
        val p = DefaultPipelines.defaultCapture()
        assertEquals("default-capture", p.id)
        assertEquals("Default capture", p.name)
        val r = PlanCompiler.compile(p)
        assertEquals(emptyList<String>(), r.problems)
        assertEquals("f5782e0d8610", r.plan!!.configHash)
        val identity = PlanResolver.identity()
        assertEquals(identity, r.plan!!.config)
        assertEquals(CanonicalJson.encode(PlanJson.configJson(identity)), CanonicalJson.encode(PlanJson.configJson(r.plan!!.config)))
    }

    @Test fun defaultCaptureIsTheGlassesLaneAtDefaultParams() {
        val p = DefaultPipelines.defaultCapture()
        assertEquals(PipelineJson.SCHEMA_V2, p.schema)
        assertEquals(listOf("imu.head_motion", "gate.threshold", "imu.settle", "gate.novelty", "policy.capture_rate", "gate.rate_cap", "capture.image", "signal.in", "hub.context", "signal.out").sorted(), p.nodes.map { it.type }.sorted())
        // no node carries an override: only the threshold's required trio and the two signal bindings have params
        assertEquals(setOf("gate.threshold", "signal.in", "signal.out"), p.nodes.filter { it.params.isNotEmpty() }.map { it.type }.toSet())
        assertTrue(p.nodes.none { it.type == NodeTypes.INPUT_PHOTOS })
    }

    @Test fun starterHasBothLanesInOnePipelineAndValidates() {
        val p = DefaultPipelines.starter()
        assertEquals("starter-capture-extract", p.id)
        assertEquals("Starter: capture and extract", p.name)
        assertEquals(PipelineJson.SCHEMA_V2, p.schema)
        assertEquals(emptyList<String>(), PipelineValidator.validate(p))
        assertTrue(DefaultPipelines.STARTER in DefaultPipelines.all)
        // the glasses lane is exactly Default capture's nodes and wires; the phone flow is exactly the live extraction's
        val dc = DefaultPipelines.defaultCapture(); val live = DefaultPipelines.liveExtract()
        assertTrue(p.nodes.containsAll(dc.nodes)); assertTrue(p.edges.containsAll(dc.edges))
        assertTrue(p.nodes.containsAll(live.nodes)); assertTrue(p.edges.containsAll(live.edges))
        // the one link between the lanes: the captured image is the Photo input
        assertTrue(PipelineEdge("cam", "photos") in p.edges)
        assertEquals(dc.nodes.size + live.nodes.size, p.nodes.size)
        assertEquals(dc.edges.size + live.edges.size + 1, p.edges.size)
        val lanes = p.nodes.map { n -> NodeTypes.domainOf(n, NodeTypes.resolve(n.type, p.schema)!!) }.toSet()
        assertEquals(setOf(Domain.RT_GLASSES, Domain.RT_PHONE, Domain.ASYNC), lanes)
    }

    @Test fun starterCompilesToTheIdentityPlanAndKeepsItsAsyncHalf() {
        val p = DefaultPipelines.starter()
        val r = PlanCompiler.compile(p)
        assertEquals(emptyList<String>(), r.problems)
        assertEquals("the glasses plan is Default capture's, the async half changes nothing", "f5782e0d8610", r.plan!!.configHash)
        assertEquals(PlanResolver.identity(), r.plan!!.config)
        assertTrue(PlanShape.hasAsyncHalf(p))
        assertEquals(emptyList<String>(), PlanShape.tier3Nodes(p))
        val async = PlanShape.asyncProjection(p)
        assertEquals(DefaultPipelines.liveExtract().nodes, async.nodes)
        assertEquals(DefaultPipelines.liveExtract().edges, async.edges)
        // Default capture is untouched by the starter existing
        assertEquals("f5782e0d8610", PlanCompiler.compile(DefaultPipelines.defaultCapture()).plan!!.configHash)
    }

    @Test fun liveExtractMirrorsCaptureFlowPhotoPath() {
        val ids = mapOf("photo" to "photos", "extract" to "extract", "confidence" to "trust", "critic" to "critic", "save" to "write", "save_review" to "write")
        val labels = mapOf("low confidence" to "unsure", "fail / unbound" to "fail")
        val path = CaptureFlow.edges.filter { it.from in ids && it.to in ids }
            .map { Triple(ids.getValue(it.from), ids.getValue(it.to), it.label?.let { l -> labels[l] ?: l }) }
            .toSet()
        val live = DefaultPipelines.liveExtract().edges.map { Triple(it.from, it.to, it.`when`) }.toSet()
        assertEquals(path, live)
        assertEquals(6, live.size)
    }

    @Test fun intentRoutesEveryLabel() {
        val p = DefaultPipelines.intentDraft(seed)
        val sw = p.nodes.first { it.id == "route" }
        assertEquals(DefaultPipelines.INTENT_LABELS.take(5), NodeTypes.switchCases(sw.params))
        for (l in DefaultPipelines.INTENT_LABELS) {
            val e = p.edges.single { it.from == "route" && it.`when` == l }
            assertEquals("extract_$l", e.to)
            assertTrue(p.edges.any { it.from == e.to && it.to == "write" })
        }
        val other = p.nodes.first { it.id == "extract_other" }
        assertEquals(PromptSource.Live, other.promptSource())
        assertEquals(PromptSource.Draft(seed, null), p.nodes.first { it.id == "extract_workout" }.promptSource())
        assertEquals(1, p.nodes.count { it.type == NodeTypes.MEMORY_WRITE })
    }

    @Test fun intentSchemaParses() {
        val intent = DefaultPipelines.intentDraft(seed).nodes.first { it.id == "intent" }
        val schema = Json.parseToJsonElement(intent.params["schema"]!!.jsonPrimitive.content).jsonObject
        val labels = schema["properties"]!!.jsonObject["activity"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(DefaultPipelines.INTENT_LABELS, labels)
        assertEquals(listOf("activity", "confidence"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals("VISION_EXTRACT", intent.params["role"]!!.jsonPrimitive.content)
        assertEquals("intent", intent.params["name"]!!.jsonPrimitive.content)
        assertTrue(intent.params["template"]!!.jsonPrimitive.content.endsWith("{{recent_context}}"))
        assertTrue(schema["properties"] is JsonObject && schema["required"] is JsonArray)
    }
}
