package com.rokid.context.canvas.plan

import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Test-only graphs: the default capture half (every node at its defaults, which compiles to today's behaviour) and the full three-domain graph (capture half + slice 2's live extraction). */
object PlanTestFixtures {
    private val none = JsonObject(emptyMap())
    private fun node(id: String, type: String, params: JsonObject = none) = PipelineNode(id, type, id, params)
    private fun sig(id: String, type: String, domain: String) = node(id, type, buildJsonObject { put("name", "ctx"); put("domain", domain) })

    private fun rtNodes(settleMs: Int?) = listOf(
        node("head", NodeTypes.IMU_HEAD_MOTION), node("thresh", NodeTypes.GATE_THRESHOLD, buildJsonObject { put("feature", "gyroPeak"); put("op", ">"); put("value", 0.6) }),
        node("settle", NodeTypes.IMU_SETTLE, if (settleMs == null) none else buildJsonObject { put("settleMs", settleMs) }), node("novel", NodeTypes.GATE_NOVELTY), node("rate", NodeTypes.POLICY_CAPTURE_RATE), node("cap", NodeTypes.GATE_RATE_CAP),
        node("cam", NodeTypes.CAPTURE_IMAGE), sig("cin", NodeTypes.SIGNAL_IN, "RT_GLASSES"), node("hub", NodeTypes.HUB_CONTEXT), sig("cout", NodeTypes.SIGNAL_OUT, "RT_PHONE"),
    )
    private val rtEdges = listOf(
        PipelineEdge("head", "thresh"), PipelineEdge("thresh", "settle"), PipelineEdge("settle", "novel"), PipelineEdge("novel", "rate"), PipelineEdge("cin", "rate"),
        PipelineEdge("rate", "cap"), PipelineEdge("cap", "cam"), PipelineEdge("hub", "cout"),
    )

    /** The capture half alone: publishes as a `capture_plan` and has no ASYNC half. */
    fun captureHalf(name: String = "Capture", id: String = "p", settleMs: Int? = null): Pipeline = Pipeline(2, id, name, rtNodes(settleMs), rtEdges)

    /** Capture half + slice 2's live extraction, joined by the photos binding (`capture.image` -> `input.photos`). */
    fun full(name: String = "Full", id: String = "p", settleMs: Int? = null): Pipeline {
        val async = DefaultPipelines.liveExtract()
        return Pipeline(2, id, name, rtNodes(settleMs) + async.nodes, rtEdges + async.edges + PipelineEdge("cam", "photos"))
    }
}
