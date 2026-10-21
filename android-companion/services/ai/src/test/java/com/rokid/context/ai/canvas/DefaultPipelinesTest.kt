package com.rokid.context.ai.canvas

import com.rokid.context.ai.flow.flows.CaptureFlow
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
        assertEquals(listOf(DefaultPipelines.LIVE_EXTRACT, DefaultPipelines.INTENT_DRAFT), DefaultPipelines.all)
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
