package com.rokid.context.canvas

import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoutedReply
import com.rokid.context.ai.canvas.ExtractComposite
import com.rokid.context.ai.canvas.LocalTrust
import com.rokid.context.ai.canvas.MotionModel
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.NumberCompare
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.PromptSource
import com.rokid.context.ai.canvas.SummaryNode
import com.rokid.context.ai.canvas.WindowAggregate
import com.rokid.context.ai.canvas.promptSource
import com.rokid.context.ai.contract.CriticOutput
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.ai.schema.JsonSchemaValidator
import com.rokid.context.ai.schema.ModelJson
import com.rokid.context.ai.schema.StructuredOutput
import com.rokid.context.model.ModelRequest
import com.rokid.context.orchestration.CallUsage
import com.rokid.context.orchestration.ExtractFollowUp
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.prompt.NowText
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptVars
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * The dry-run executors. They run the live AI path (ExtractSave.extract, RoleRunner, the critic helpers) with an in-memory prompt
 * override and write nothing: no memory, frame_state, frame_source, embedding, nudge or job (spec section 3, R10).
 */
object Executors {
    fun all(): Map<String, NodeExecutor> =
        listOf(VisionExtractExecutor, LocalCriticExecutor, CriticExecutor, PromptExecutor, SummaryExecutor, WindowAggregateExecutor, CompareExecutor, MotionModelExecutor, SwitchExecutor, MemoryWritePreviewExecutor).associateBy { it.type }
}

private fun PipelineNode.draft(): PromptDraft? = (promptSource() as? PromptSource.Draft)?.let { PromptDraft(it.template, it.schema) }

private fun failure(e: Exception): String = if (e is ModelCallException) e.kind.name else e.javaClass.simpleName

/** Summed tokens of a call's attempts: null when no attempt reported any (the provider may not say). */
private fun sumOrNull(values: List<Int?>): Int? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

private fun List<RoutedReply>.asUsage() = map { CallUsage(it.promptTokens, it.completionTokens, it.model, it.latencyMs) }

/** The meta of the ONE row that owns a provider call: the summed tokens of its [attempts] and their count (1 when unknown); S32. */
private fun ownerMeta(latencyMs: Long, attempts: List<CallUsage>, promptVersion: Int?, batchId: String?) = CallMeta(
    latencyMs, sumOrNull(attempts.map { it.promptTokens }), sumOrNull(attempts.map { it.completionTokens }), attempts.lastOrNull { it.model != null }?.model,
    promptVersion, batchId, calls = attempts.size.coerceAtLeast(1),
)

private fun noCalls(node: PipelineNode, items: Int) = 0

private fun ceilDiv(n: Int, d: Int) = (n + d - 1) / d

object VisionExtractExecutor : NodeExecutor {
    override val type = NodeTypes.VISION_EXTRACT

    private fun batch(node: PipelineNode) = (node.params["batch"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.coerceIn(1, ExtractSave.MAX_BATCH) ?: ExtractComposite.DEFAULT_BATCH

    override fun chunkSize(node: PipelineNode) = batch(node)
    override fun nominalCalls(node: PipelineNode, items: Int) = ceilDiv(items, batch(node))
    override fun maxCalls(node: PipelineNode, items: Int) = nominalCalls(node, items) * 2 + if (batch(node) > 1) items else 0

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val override = node.draft()
        val out = ArrayList<NodeResult>(items.size)
        items.chunked(batch(node)).forEach { chunk ->
            val batchId = "${ctx.runId}:${node.id}:${chunk.first().frameId}" // one model call: shared by exactly its frames (S25)
            val upstream = chunk.mapNotNull { (it.value as? CanvasValue.Json)?.value?.toString() }
            val sources = if (upstream.isEmpty()) "" else PromptComposer.dataBlock("upstream", upstream)
            val started = System.nanoTime()
            fun ms() = (System.nanoTime() - started) / 1_000_000
            try {
                val b = ExtractSave.extract(ctx.runner, chunk.map { it.photo }, ctx.callClass, override, sources)
                // One provider call (or a few attempts) behind the whole chunk: only its first item owns the usage, counted once (S32).
                val owner = ownerMeta(ms(), b.attempts, b.promptVersion, batchId)
                val mate = CallMeta(owner.latencyMs, null, null, null, b.promptVersion, batchId)
                chunk.forEachIndexed { i, it ->
                    val facts = b.output.facts.filter { f -> it.frameId in f.sourceFrameIds }
                    val captions = b.output.captions.filter { c -> c.frameId == it.frameId }.take(1)
                    out += NodeResult(it.frameId, CanvasValue.Extraction(facts, captions), meta = if (i == 0) owner else mate, calls = if (i == 0) owner.calls!! else 0, extracted = b)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                val owner = ownerMeta(ms(), (e as? ModelCallException)?.replies.orEmpty().asUsage(), null, batchId)
                val mate = CallMeta(owner.latencyMs, null, null, null, null, batchId)
                chunk.forEachIndexed { i, it -> out += NodeResult(it.frameId, null, error = failure(e), meta = if (i == 0) owner else mate, calls = if (i == 0) owner.calls!! else 0, cause = e) }
            }
        }
        return out
    }
}

object LocalCriticExecutor : NodeExecutor {
    override val type = NodeTypes.LOCAL_CRITIC
    override fun nominalCalls(node: PipelineNode, items: Int) = noCalls(node, items)
    override fun maxCalls(node: PipelineNode, items: Int) = noCalls(node, items)

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> = items.map {
        val settings = LocalTrust.settings(node.params) // absent params: today's mean >= 0.70 (see LocalTrust)
        val v = it.value as? CanvasValue.Extraction ?: return@map NodeResult(it.frameId, null, error = "BAD_INPUT")
        val trusted = LocalTrust.decide(v.facts.map { f -> f.confidence }, settings)
        NodeResult(it.frameId, v.copy(trusted = trusted), branch = if (trusted) "trusted" else "unsure")
    }
}

object CriticExecutor : NodeExecutor {
    override val type = NodeTypes.CRITIC
    override fun nominalCalls(node: PipelineNode, items: Int) = items
    override fun maxCalls(node: PipelineNode, items: Int) = items * 2

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val override = node.draft()
        val bound = ctx.bindings(Role.CRITIC) != null
        return items.mapIndexed { i, it ->
            val v = it.value as? CanvasValue.Extraction ?: return@mapIndexed NodeResult(it.frameId, null, error = "BAD_INPUT")
            if (v.facts.isEmpty()) return@mapIndexed NodeResult(it.frameId, v.copy(trusted = true), branch = "pass") // nothing to review: no model call
            if (!bound) return@mapIndexed NodeResult(it.frameId, v.copy(trusted = false, note = "unbound"), branch = "fail")
            val started = System.nanoTime()
            fun ms() = (System.nanoTime() - started) / 1_000_000
            try {
                val r = ctx.runner.run(RoleCall(PromptKey.CRITIC, ExtractFollowUp.criticVars(v.facts, listOf(it.photo.description)), callClass = ctx.callClass, override = override))
                val pass = (r.output as CriticOutput).pass
                val meta = ownerMeta(r.reply.latencyMs, r.replies.asUsage(), r.promptVersion, "${ctx.runId}:${node.id}:${it.frameId}")
                NodeResult(it.frameId, v.copy(trusted = pass), branch = if (pass) "pass" else "fail", meta = meta, calls = meta.calls!!)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // Live keeps the local verdict (not trusted) when the critic fails.
                val meta = ownerMeta(ms(), (e as? ModelCallException)?.replies.orEmpty().asUsage(), null, "${ctx.runId}:${node.id}:${it.frameId}")
                NodeResult(it.frameId, v.copy(trusted = false), branch = "fail", error = failure(e), meta = meta, calls = meta.calls!!, cause = e)
            }
        }
    }
}

object PromptExecutor : NodeExecutor {
    override val type = NodeTypes.PROMPT
    override fun nominalCalls(node: PipelineNode, items: Int) = items
    override fun maxCalls(node: PipelineNode, items: Int) = items

    private fun str(node: PipelineNode, key: String) = (node.params[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val images = (node.params["images"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull == "true"
        val role = Role.entries.firstOrNull { it.name == str(node, "role") } ?: if (images) Role.VISION_EXTRACT else Role.SUMMARIZE
        val template = str(node, "template").orEmpty()
        val name = str(node, "name") ?: node.id
        val schemaObj = str(node, "schema")?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val bound = ctx.bindings(role) != null
        val system = ctx.prompts.active(PromptKey.SYSTEM).template
        return items.mapIndexed { i, it ->
            if (schemaObj == null) return@mapIndexed NodeResult(it.frameId, null, error = "BAD_SCHEMA")
            if (!bound) return@mapIndexed NodeResult(it.frameId, null, error = "NOT_SET")
            val upstream = when (val v = it.value) {
                is CanvasValue.Json -> v.value.toString()
                is CanvasValue.Extraction -> CanvasValueJson.encode(v)
                else -> null
            }
            val vars = PromptVars(
                recentContext = PromptComposer.dataBlock("frames", listOf("frame_id: ${it.frameId} - ${it.photo.description}")),
                contextSources = upstream?.let { u -> PromptComposer.dataBlock("upstream", listOf(u)) }.orEmpty(),
            )
            val prompt = PromptComposer.compose(system, template, vars.toMap(ctx.persona.persona.value, NowText.of(ctx.now())))
            val batchId = "${ctx.runId}:${node.id}:${it.frameId}" // per photo (S25)
            val started = System.nanoTime()
            try {
                val reply = ctx.model.complete(role, ctx.callClass) { e ->
                    val plan = StructuredOutput.plan(e, schemaObj, name)
                    ModelRequest(prompt + plan.promptSuffix, if (images) listOf(it.photo.image) else emptyList(), format = plan.format)
                }
                val meta = ownerMeta(reply.latencyMs, listOf(reply).asUsage(), null, batchId)
                val json = ModelJson.parse(reply.text) ?: return@mapIndexed NodeResult(it.frameId, null, error = "NOT_JSON", meta = meta, calls = 1)
                val problems = JsonSchemaValidator.validate(schemaObj, json)
                NodeResult(it.frameId, CanvasValue.Json(json), error = if (problems.isEmpty()) null else "INVALID_OUTPUT", meta = meta, calls = 1)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                NodeResult(it.frameId, null, error = failure(e), meta = CallMeta((System.nanoTime() - started) / 1_000_000, null, null, null, null, batchId, calls = 1), calls = 1, cause = e)
            }
        }
    }
}

/**
 * `proc.summary`: one model call per item, on the same text path as [PromptExecutor] (the user's SUMMARIZE binding through the router, structured output, the item inside a data block),
 * with the fixed [SummaryNode.TEMPLATE]. The value is an Extraction with ONE fact of type [SummaryNode.FACT_TYPE] carrying the model's confidence, so the Confidence check and Save memory
 * (preview) read it as they read any fact. Rows get the lineage token of their own call, and the runner adds the member tokens of a Moment or Scene item, so a Forget reaches them.
 */
object SummaryExecutor : NodeExecutor {
    override val type = NodeTypes.SUMMARY

    /**
     * True when the item holds words a model could summarise. A Scene or Moment item (itemType scene or moment) holds words only in its `text` (metadata such as ids, times, counts and the
     * rule are not words); another JSON value (a Prompt's answer) needs at least one non-blank string; an Extraction needs a non-blank fact or caption.
     */
    internal fun hasVisibleText(v: CanvasValue): Boolean = when (v) {
        is CanvasValue.Json -> {
            val o = v.value as? JsonObject
            val kind = (o?.get("itemType") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            if (kind == "scene" || kind == "moment") ((o?.get("text") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content).orEmpty().isNotBlank() else hasString(v.value)
        }
        is CanvasValue.Extraction -> v.facts.any { f -> f.text.isNotBlank() } || v.captions.any { c -> c.caption.isNotBlank() }
        else -> false
    }

    private fun hasString(e: kotlinx.serialization.json.JsonElement): Boolean = when (e) {
        is JsonPrimitive -> e.isString && e.content.isNotBlank()
        is JsonObject -> e.values.any { hasString(it) }
        is kotlinx.serialization.json.JsonArray -> e.any { hasString(it) }
    }
    override fun nominalCalls(node: PipelineNode, items: Int) = items
    override fun maxCalls(node: PipelineNode, items: Int) = items

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val bound = ctx.bindings(Role.SUMMARIZE) != null
        val system = ctx.prompts.active(PromptKey.SYSTEM).template
        return items.map { it ->
            val upstream = when (val v = it.value) {
                is CanvasValue.Json -> v.value.toString()
                is CanvasValue.Extraction -> CanvasValueJson.encode(v)
                else -> return@map NodeResult(it.frameId, null, error = "BAD_INPUT")
            }
            // nothing visible to summarise (a Scene or Moment with no visible text, an empty extraction): no model call, an item error, never a made-up result
            if (!hasVisibleText(it.value)) return@map NodeResult(it.frameId, null, error = "NO_INPUT")
            if (!bound) return@map NodeResult(it.frameId, null, error = "NOT_SET")
            val vars = PromptVars(contextSources = PromptComposer.dataBlock("upstream", listOf(upstream)))
            val prompt = PromptComposer.compose(system, SummaryNode.TEMPLATE, vars.toMap(ctx.persona.persona.value, NowText.of(ctx.now())))
            val batchId = "${ctx.runId}:${node.id}:${it.frameId}"
            val started = System.nanoTime()
            try {
                val reply = ctx.model.complete(Role.SUMMARIZE, ctx.callClass) { e ->
                    val plan = StructuredOutput.plan(e, SummaryNode.schema, "summary")
                    ModelRequest(prompt + plan.promptSuffix, emptyList(), format = plan.format)
                }
                val meta = ownerMeta(reply.latencyMs, listOf(reply).asUsage(), null, batchId)
                val json = ModelJson.parse(reply.text) ?: return@map NodeResult(it.frameId, null, error = "NOT_JSON", meta = meta, calls = 1)
                if (JsonSchemaValidator.validate(SummaryNode.schema, json).isNotEmpty()) return@map NodeResult(it.frameId, null, error = "INVALID_OUTPUT", meta = meta, calls = 1)
                val o = json as JsonObject
                val fact = Fact((o["summary"] as JsonPrimitive).content, SummaryNode.FACT_TYPE, (o["confidence"] as JsonPrimitive).doubleOrNull ?: 0.0, emptyList(), listOf(it.frameId))
                NodeResult(it.frameId, CanvasValue.Extraction(listOf(fact), emptyList()), meta = meta, calls = 1)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                NodeResult(it.frameId, null, error = failure(e), meta = CallMeta((System.nanoTime() - started) / 1_000_000, null, null, null, null, batchId, calls = 1), calls = 1, cause = e)
            }
        }
    }
}

/**
 * `agg.window`: a pure, deterministic number per item (no model, no clock): [WindowAggregate.over] the sensor summaries of the items in run order. The runner hands it the whole run in ONE
 * chunk (a window looks back over earlier items). A JSON item with no sensor summary adds nothing to a window; an empty window has no mean, min or max, which is the fixed error NO_DATA.
 */
object WindowAggregateExecutor : NodeExecutor {
    override val type = NodeTypes.AGG_WINDOW
    override fun chunkSize(node: PipelineNode) = Int.MAX_VALUE
    override fun nominalCalls(node: PipelineNode, items: Int) = noCalls(node, items)
    override fun maxCalls(node: PipelineNode, items: Int) = noCalls(node, items)

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val s = WindowAggregate.settings(node.params)
        val valid = items.filter { it.value is CanvasValue.Json }
        val values = valid.map { WindowAggregate.featureOf((it.value as CanvasValue.Json).value, s.feature) }
        val numbers = WindowAggregate.over(values, s.op, s.window)
        val windowed = valid.indices.associate { valid[it].frameId to it }
        return items.map { item ->
            val i = windowed[item.frameId] ?: return@map NodeResult(item.frameId, null, error = "BAD_INPUT")
            val v = numbers[i] ?: return@map NodeResult(item.frameId, null, error = "NO_DATA")
            val n = values.subList(maxOf(0, i - s.window + 1), i + 1).count { it != null }
            NodeResult(item.frameId, CanvasValue.Json(buildJsonObject {
                put("value", if (s.op == "count") JsonPrimitive(v.toLong()) else JsonPrimitive(v)); put("n", n)
                put("feature", s.feature); put("op", s.op); put("window", s.window)
            }))
        }
    }
}

/**
 * `proc.motion_model`: the tunable heuristic, no model call and no clock. The runner hands it the whole run in ONE chunk, in run order, because the hysteresis keeps one state across
 * items ([MotionModel.decide]). An item with no usable number is the fixed error BAD_INPUT and leaves the state as it was.
 */
object MotionModelExecutor : NodeExecutor {
    override val type = NodeTypes.PROC_MOTION_MODEL
    override fun chunkSize(node: PipelineNode) = Int.MAX_VALUE
    override fun nominalCalls(node: PipelineNode, items: Int) = noCalls(node, items)
    override fun maxCalls(node: PipelineNode, items: Int) = noCalls(node, items)

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val s = MotionModel.settings(node.params)
        val numbers = items.map { (it.value as? CanvasValue.Json)?.let { v -> MotionModel.numberOf(v.value) } }
        val moving = MotionModel.decide(numbers, s)
        return items.indices.map { i ->
            val m = moving[i] ?: return@map NodeResult(items[i].frameId, null, error = "BAD_INPUT")
            NodeResult(items[i].frameId, CanvasValue.Json(buildJsonObject {
                put("label", if (m) MotionModel.MOVING else MotionModel.STILL); put("value", if (m) 1.0 else 0.0); put("score", numbers[i]!!)
                put("kind", s.kind); put("threshold", s.threshold); put("exit", s.exit)
            }))
        }
    }
}

/** `gate.compare`: the aggregate's `value` against a cutoff; branch `yes` or `no`, the number passes through unchanged. No model. */
object CompareExecutor : NodeExecutor {
    override val type = NodeTypes.COMPARE
    override fun nominalCalls(node: PipelineNode, items: Int) = noCalls(node, items)
    override fun maxCalls(node: PipelineNode, items: Int) = noCalls(node, items)

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val s = NumberCompare.settings(node.params)
        return items.map {
            val v = it.value as? CanvasValue.Json
            val n = ((v?.value as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull
                ?: return@map NodeResult(it.frameId, null, error = "BAD_INPUT")
            NodeResult(it.frameId, v, branch = if (NumberCompare.holds(n, s)) "yes" else "no")
        }
    }
}

object SwitchExecutor : NodeExecutor {
    override val type = NodeTypes.SWITCH
    override fun nominalCalls(node: PipelineNode, items: Int) = noCalls(node, items)
    override fun maxCalls(node: PipelineNode, items: Int) = noCalls(node, items)

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        val field = (node.params["field"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val cases = NodeTypes.switchCases(node.params)
        return items.map {
            val v = it.value as? CanvasValue.Json ?: return@map NodeResult(it.frameId, null, error = "BAD_INPUT")
            val label = field?.let { f -> ((v.value as? JsonObject)?.get(f) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            NodeResult(it.frameId, v, branch = if (label != null && label in cases) label else NodeTypes.OTHER)
        }
    }
}

/** What saving would write, as rows. It writes nothing: ExtractSave.saveExtraction is never called. */
object MemoryWritePreviewExecutor : NodeExecutor {
    override val type = NodeTypes.MEMORY_WRITE
    override fun nominalCalls(node: PipelineNode, items: Int) = noCalls(node, items)
    override fun maxCalls(node: PipelineNode, items: Int) = noCalls(node, items)

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> = items.map {
        val v = it.value as? CanvasValue.Extraction ?: return@map NodeResult(it.frameId, null, error = "BAD_INPUT")
        val review = if (v.trusted == true) "OK" else "NEEDS_REVIEW"
        val rows = v.facts.filter { f: Fact -> f.text.isNotBlank() }.map { f -> PreviewRow("fact", f.text, f.type, "SHORT", review) } +
            v.captions.filter { c -> c.caption.isNotBlank() }.map { c -> PreviewRow("caption", c.caption, "caption", "LONG", "OK") }
        NodeResult(it.frameId, CanvasValue.Preview(rows))
    }
}
