package com.rokid.context.canvas

import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.canvas.InterpretableShape
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.plan.PlanShape
import com.rokid.context.ai.contract.ExtractOutput
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.db.CanvasLiveTraceEntity
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.ExtractedBatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import java.io.IOException
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the pre-save nodes of an interpreter pipeline ([InterpretableShape.preSaveNodes]) over a batch of photos with the same canvas
 * executors as an experiment run ("what you test is what ships"), at the call class of [RunContext.callClass] (spec section 5, S23, S28).
 * The item flow (topological order, the edge `when` rule, the merge conflict) is [ItemFlow], shared with [CanvasRunner].
 *
 * It writes nothing and calls no sink: the caller (`LiveExtract`, Task 6) buffers [Outcome]'s traces and labels, persists the traces
 * with the fenced DAO write and hands the labels to the [FrameIntentSink] only after the save succeeded. It does not enforce a deadline
 * either: the caller wraps it in `withTimeoutOrNull` (S30), and the resulting cancellation passes through here untouched.
 */
class PipelineInterpreter(
    private val executors: Map<String, NodeExecutor> = Executors.all(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** One fresh value per [run]; it prefixes every trace `batchId` so the S25 batch-wide Forget never reaches another batch's rows. */
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface Outcome {
        /**
         * [batch]: facts de-duplicated by (text, type, set of source frames), captions one per frame (the first wins), `provenance` true only when every
         * extract call had it, `promptVersion` 0. [labels]: frame id to (switch field value, numeric `confidence` when present), from the first switch an item passed.
         */
        data class Extracted(val batch: ExtractedBatch, val traces: List<CanvasLiveTraceEntity>, val labels: Map<String, Pair<String, Float?>>) : Outcome

        /** The batch can't be used. [reason] is a fixed short text (a failure kind or class name, `routing`, `merge_conflict`, ...). [traces] include one `_batch` ERROR row per photo. */
        data class Failed(val reason: String, val traces: List<CanvasLiveTraceEntity>) : Outcome
    }

    /**
     * Throws a transient cause (S23: an `IOException` anywhere in its chain, or a retryable TRANSIENT [ModelCallException]) exactly as the live path would
     * propagate it, and [CancellationException] always. Every other failure is [Outcome.Failed]; once one is seen no later node runs.
     */
    suspend fun run(p: Pipeline, publishId: Long, mode: String, photos: List<ExtractPhoto>, ctx: RunContext): Outcome {
        val token = newId()
        val traces = ArrayList<CanvasLiveTraceEntity>()
        val lineage = HashMap<String, LinkedHashSet<String>>() // S25: every call whose output a frame's rows derive from
        fun row(frameId: String, nodeId: String, r: NodeResult): CanvasLiveTraceEntity {
            val batch = CanvasLineage.compose(lineage.getOrPut(frameId) { LinkedHashSet() }, r.meta?.batchId)
            return CanvasLiveTraceEntity(
                publishId, frameId, nodeId, mode, if (r.error == null) "OK" else "ERROR", r.branch, r.value?.let { cap(CanvasValueJson.encode(it)) }, r.error,
                batch?.split(CanvasLineage.SEP)?.joinToString(CanvasLineage.SEP.toString()) { "$token:$it" }, clock(),
            )
        }
        fun batchRows(error: String?) = photos.map { CanvasLiveTraceEntity(publishId, it.frameId, BATCH, mode, if (error == null) "OK" else "ERROR", null, null, error, null, clock()) }
        fun failed(reason: String): Outcome = Outcome.Failed(reason, traces + batchRows(reason))

        if (photos.isEmpty()) return failed("no_photos")
        if (PlanShape.tier3Nodes(p).isNotEmpty()) return failed(TIER3) // S6, fail closed: `py.fn` and `tool.` nodes never run here, and `preSaveNodes` would otherwise skip them silently
        val photoById = photos.associateBy { it.frameId }
        val order = photos.withIndex().associate { it.value.frameId to it.index }
        val outputs = HashMap<String, Map<String, ItemFlow.Out>>()
        val carried = HashMap<String, com.rokid.context.model.PayloadSources>()
        val labels = LinkedHashMap<String, Pair<String, Float?>>()
        val extractedFrames = HashSet<String>()
        val calls = IdentityHashMap<ExtractedBatch, Boolean>() // the distinct extract calls (every item of a chunk shares one instance)
        val callOrder = ArrayList<ExtractedBatch>()
        val loose = ArrayList<CanvasValue.Extraction>() // extractions from an executor that did not set NodeResult.extracted
        var failure: String? = null

        for (node in InterpretableShape.preSaveNodes(p)) {
            if (node.type == NodeTypes.INPUT_PHOTOS) {
                outputs[node.id] = photos.associate { it.frameId to ItemFlow.Out(CanvasValue.Photo, null) }
                continue
            }
            val gathered = ItemFlow.gather(p.edges.filter { it.to == node.id }, outputs)
            for (id in gathered.conflicts.sortedBy { order[it] }) {
                traces += row(id, node.id, NodeResult(id, null, error = "merge_conflict"))
                failure = failure ?: "merge_conflict"
            }
            val isExtract = node.type == NodeTypes.VISION_EXTRACT
            val queue = ArrayList<CanvasItem>()
            for ((id, v) in gathered.inputs.entries.sortedBy { order[it.key] }) {
                if (isExtract && id in extractedFrames) { // a second extraction for one photo: refuse before paying for it
                    traces += row(id, node.id, NodeResult(id, null, error = "routing"))
                    failure = failure ?: "routing"
                } else queue += CanvasItem(id, photoById.getValue(id), v, carried[id] ?: com.rokid.context.model.PayloadSources.NONE)
            }
            val ex = executors[node.type]
            val produced = LinkedHashMap<String, ItemFlow.Out>()
            val provenance = NodeProvenance(ex)
            if (failure == null) for (chunk in queue.chunked(ex?.chunkSize(node)?.coerceAtLeast(1) ?: Int.MAX_VALUE)) {
                provenance.consume(chunk)
                val results = runChunk(ex, node, chunk, ctx)
                results.firstNotNullOfOrNull { r -> r.cause?.takeIf(::isTransient) }?.let { throw it }
                for (item in chunk) {
                    val r = results.firstOrNull { it.frameId == item.frameId } ?: NodeResult(item.frameId, null, error = if (ex == null) "no_executor" else "no_result")
                    // r15: the execution's union is also this row's lineage ("mf:<frameId>"), so a Forget of any of those photos reaches it, as in the runner
                    lineage.getOrPut(item.frameId) { LinkedHashSet() }.addAll(provenance.of(r.value).frameIds.map { CanvasRunner.MEMBER_TOKEN + it })
                    traces += row(item.frameId, node.id, r)
                    if (r.error != null) { failure = failure ?: reasonOf(r); continue }
                    val v = r.value ?: continue
                    produced[item.frameId] = ItemFlow.Out(v, r.branch)
                    carried[item.frameId] = provenance.of(v) // codex r14 RULE A: the union of the whole execution's inputs
                    if (node.type == NodeTypes.SWITCH) labelOf(node, v)?.let { labels.putIfAbsent(item.frameId, it) }
                    if (isExtract) {
                        extractedFrames += item.frameId
                        val b = r.extracted
                        if (b == null) { (v as? CanvasValue.Extraction)?.let(loose::add) } else if (calls.put(b, true) == null) callOrder += b
                    }
                }
                if (failure != null) break
            }
            outputs[node.id] = produced
            if (failure != null) break
        }
        if (failure == null && photos.any { it.frameId !in extractedFrames }) failure = "routing"
        failure?.let { return failed(it) }

        val facts = LinkedHashMap<Triple<String, String, Set<String>>, Fact>()
        val captions = LinkedHashMap<String, FrameCaption>()
        var provenance = true
        for (b in callOrder) {
            provenance = provenance && b.output.provenance
            b.output.facts.forEach { facts.putIfAbsent(Triple(it.text, it.type, it.sourceFrameIds.toSet()), it) }
            b.output.captions.forEach { captions.putIfAbsent(it.frameId, it) }
        }
        for (v in loose) { // no provenance information: the safe answer for the merge is "not proven"
            provenance = false
            v.facts.forEach { facts.putIfAbsent(Triple(it.text, it.type, it.sourceFrameIds.toSet()), it) }
            v.captions.forEach { captions.putIfAbsent(it.frameId, it) }
        }
        // codex r10: delivered only when every call behind the batch proved it (a loose value carries no proof)
        val delivered = loose.isEmpty() && callOrder.isNotEmpty() && callOrder.all { it.output.imagesDelivered }
        val batch = ExtractedBatch(ExtractOutput(facts.values.toList(), captions = captions.values.toList(), provenance = provenance, imagesDelivered = delivered), 0)
        return Outcome.Extracted(batch, traces + batchRows(null), labels)
    }

    /** One executor call. A throwable the executor lets escape (not a cancellation) becomes a failed result per item, classified like a caught one. */
    private suspend fun runChunk(ex: NodeExecutor?, node: PipelineNode, chunk: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        if (ex == null) return chunk.map { NodeResult(it.frameId, null, error = "no_executor") }
        return try {
            ex.run(node, chunk, ctx)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            chunk.map { NodeResult(it.frameId, null, error = nameOf(e), cause = e) }
        }
    }

    /** The label that reaches the sink: trimmed, lower case, at most 64 characters, and only one of the switch's cases or `other` (anything else is `other`). */
    private fun labelOf(node: PipelineNode, v: CanvasValue): Pair<String, Float?>? {
        val key = (node.params["field"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val obj = (v as? CanvasValue.Json)?.value as? JsonObject ?: return null
        val raw = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val cleaned = raw.trim().lowercase().take(MAX_LABEL)
        val label = if (cleaned == NodeTypes.OTHER || cleaned in NodeTypes.switchCases(node.params).map { it.trim().lowercase() }) cleaned else NodeTypes.OTHER
        val conf = (obj["confidence"] as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull?.coerceIn(0f, 1f)
        return label to conf
    }

    private fun reasonOf(r: NodeResult): String = r.cause?.let(::nameOf) ?: r.error ?: "no_result"

    private fun nameOf(e: Throwable): String = when (e) {
        is com.rokid.context.ai.PhotoForgottenException -> FRAME_FORGOTTEN // codex r11: the live seam ends the work item, never falls back
        is ModelCallException -> e.kind.name
        else -> e.javaClass.simpleName
    }

    /** Cut on a code point boundary (never between the halves of a surrogate pair); the trailing marker says the text is cut. */
    private fun cap(s: String): String {
        if (s.length <= OUTPUT_CAP) return s
        var end = OUTPUT_CAP - TRUNCATED.length
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end) + TRUNCATED
    }

    companion object {
        /** The fixed `Failed.reason` of a graph that holds a tier-3 node (S6). */
        const val TIER3 = "tier3_node"
        const val OUTPUT_CAP = 8_000
        const val TRUNCATED = "...[truncated]"
        const val BATCH = "_batch"
        /** Codex r11: the failure reason when a model call of the batch was dropped because a photo was forgotten (or everything deleted) before it left. */
        const val FRAME_FORGOTTEN = "frame_forgotten"
        const val MAX_LABEL = 64
        private const val CHAIN_DEPTH = 8

        /** The failures the live fallback would hit too (S23): an I/O failure anywhere in the chain (as `ExtractOutcome.classify` reads it), or a retryable TRANSIENT model failure. */
        internal fun isTransient(t: Throwable): Boolean =
            generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(CHAIN_DEPTH)
                .any { it is IOException || (it is ModelCallException && it.kind == FailureKind.TRANSIENT && it.retryable) }
    }
}
