package com.rokid.context.canvas

import com.rokid.context.ai.Role
import com.rokid.context.ai.canvas.ClassifySpec
import com.rokid.context.ai.canvas.ModelNodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.RuleTable
import com.rokid.context.ai.resolve.ModelNodeBackend
import com.rokid.context.ai.resolve.NodeBackendPref
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.schema.ModelJson
import com.rokid.context.ai.schema.StructuredOutput
import com.rokid.context.model.ModelRequest
import com.rokid.context.prompt.NowText
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * Canvas v2.2 executors, merged into the runner's map only by the phone wiring while the Developer flag `canvasModelNodes` is ON (the node types themselves resolve only then). They are test and experiment
 * nodes: no memory write, no delivery, no publish. A model call goes through `ctx.model` (the one router choke point: fallbacks, data saver, liveness, EXPERIMENT call class); the node's own `backend` choice
 * is intersected with the privacy mode first ([ModelNodeBackend], fixed refusal tokens), and nothing is sent when it says no.
 */
object ModelNodeExecutors {
    private val map: Map<String, NodeExecutor> = listOf<NodeExecutor>(ModelClassifyExecutor(ModelNodeTypes.CLASSIFY), ModelClassifyExecutor(ModelNodeTypes.ROUTE), RuleTableExecutor).associateBy { it.type }
    fun all(): Map<String, NodeExecutor> = map
}

/** `model.classify` (the label and its confidence) and `model.route` (the same, with the item carried in the output). One call per item. */
class ModelClassifyExecutor(override val type: String) : NodeExecutor {
    override val chunkLocal = true
    override fun nominalCalls(node: PipelineNode, items: Int) = items
    override fun maxCalls(node: PipelineNode, items: Int) = items

    private fun str(n: PipelineNode, k: String) = (n.params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val labels = ClassifySpec.labels(node.params)
        if (labels.size !in ClassifySpec.MIN_LABELS..ClassifySpec.MAX_LABELS) return items.map { NodeResult(it.frameId, null, error = "BAD_PARAMS") }
        val images = (node.params["images"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull == "true"
        val role = if (images) Role.VISION_EXTRACT else Role.SUMMARIZE
        val threshold = ClassifySpec.threshold(node.params)
        val maxMs = ((node.params["maxLatencyMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 30_000).coerceIn(1_000, 120_000).toLong()
        val skip = str(node, "onUnavailable") == "skip"

        // layer two of the privacy intersection (layer one is the estimate); evaluated per run, before any request is built
        val refusal = try { ModelNodeBackend.check(NodeBackendPref.parse(str(node, "backend")), ctx.modelPrivacy(), ctx.boundBackend(role)) } catch (c: CancellationException) { throw c } catch (e: Exception) { ModelNodeBackend.NOT_BOUND }
        if (refusal != null) return items.map { if (skip) skipped(it, refusal) else NodeResult(it.frameId, null, error = refusal) }

        val schema = ClassifySpec.schema(labels)
        val template = ClassifySpec.instruction(labels, str(node, "instruction")) + "\n\n{{context_sources}}\n\n{{recent_context}}"
        val system = ctx.prompts.active(PromptKey.SYSTEM).template
        return items.map { item ->
            val upstream = when (val v = item.value) {
                is CanvasValue.Json -> v.value.toString()
                is CanvasValue.Extraction -> CanvasValueJson.encode(v)
                else -> null
            }
            val vars = PromptVars(
                recentContext = PromptComposer.dataBlock("frames", listOf("frame_id: ${item.frameId} - ${item.photo.description}")),
                contextSources = upstream?.let { u -> PromptComposer.dataBlock("upstream", listOf(u)) }.orEmpty(),
            )
            val prompt = PromptComposer.compose(system, template, vars.toMap(ctx.persona.persona.value, NowText.of(ctx.now())))
            val batchId = "${ctx.runId}:${node.id}:${item.frameId}"
            val started = System.nanoTime()
            try {
                val reply = withTimeoutOrNull(maxMs) {
                    ctx.model.complete(role, ctx.callClass) { e ->
                        val plan = StructuredOutput.plan(e, schema, "classify")
                        ModelRequest(prompt + plan.promptSuffix, if (images) listOf(item.photo.image.copy(ref = item.photo.frameId)) else emptyList(), format = plan.format,
                            requireImages = images, sources = sourcesOf(item), liveness = ctx.liveness)
                    }
                } ?: return@map NodeResult(item.frameId, null, error = "TIMEOUT", meta = CallMeta((System.nanoTime() - started) / 1_000_000, null, null, null, null, batchId, calls = 1), calls = 1)
                val meta = ownerMeta(reply.latencyMs, listOf(reply).asUsage(), null, batchId)
                val verdict = ClassifySpec.normalize(ModelJson.parse(reply.text), labels, threshold) ?: return@map NodeResult(item.frameId, null, error = "NOT_JSON", meta = meta, calls = 1)
                val out = buildJsonObject {
                    put("label", verdict.label); put("confidence", verdict.confidence)
                    if (type == ModelNodeTypes.ROUTE) (item.value as? CanvasValue.Json)?.let { put("item", it.value) }
                }
                NodeResult(item.frameId, CanvasValue.Json(out), branch = verdict.label, meta = meta, calls = 1, imageDelivered = if (images) reply.imagesDelivered else null)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                NodeResult(item.frameId, null, error = failure(e), meta = CallMeta((System.nanoTime() - started) / 1_000_000, null, null, null, null, batchId, calls = 1), calls = 1, cause = e)
            }
        }
    }

    /** `onUnavailable = skip`: the item goes to the `none` branch with a fixed reason; no call was made. */
    private fun skipped(item: CanvasItem, reason: String) =
        NodeResult(item.frameId, CanvasValue.Json(buildJsonObject { put("label", ClassifySpec.NONE); put("confidence", 0.0); put("skipped", reason) }), branch = ClassifySpec.NONE)
}

/** `rule.table`: deterministic, no model, no network. The item passes through unchanged; the branch is the first matching row's, else `other`. */
object RuleTableExecutor : NodeExecutor {
    override val chunkLocal = true
    override val type = ModelNodeTypes.RULE_TABLE
    override fun nominalCalls(node: PipelineNode, items: Int) = noCalls(node, items)
    override fun maxCalls(node: PipelineNode, items: Int) = noCalls(node, items)

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val rows = RuleTable.parse(node.params) ?: return items.map { NodeResult(it.frameId, null, error = "BAD_PARAMS") }
        return items.map {
            val v = it.value as? CanvasValue.Json ?: return@map NodeResult(it.frameId, null, error = "BAD_INPUT")
            NodeResult(it.frameId, v, branch = RuleTable.pick(rows, v.value))
        }
    }
}

/** Layer one of the privacy intersection: refuses at ESTIMATE a pipeline whose model node cannot run under the current mode (fixed tokens, each once). */
object ModelNodePreflight {
    suspend fun reasons(p: com.rokid.context.ai.canvas.Pipeline, privacy: () -> com.rokid.context.core.net.ModelPrivacy, bound: suspend (Role) -> com.rokid.context.core.net.Backend?): List<String> {
        val out = linkedSetOf<String>()
        for (n in p.nodes) {
            if (n.type != ModelNodeTypes.CLASSIFY && n.type != ModelNodeTypes.ROUTE) continue
            val images = (n.params["images"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull == "true"
            val pref = NodeBackendPref.parse((n.params["backend"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
            val r = try { ModelNodeBackend.check(pref, privacy(), bound(if (images) Role.VISION_EXTRACT else Role.SUMMARIZE)) } catch (c: CancellationException) { throw c } catch (e: Exception) { ModelNodeBackend.NOT_BOUND }
            // "skip" turns a refusal into the none branch at run time; the estimate still says so, so nobody is surprised
            if (r != null) out += r
        }
        return out.toList()
    }
}
