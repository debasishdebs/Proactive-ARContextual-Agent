package com.rokid.context.canvas

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.helper.HelperJobPort
import com.rokid.context.canvas.helper.HelperProtocol
import com.rokid.context.canvas.helper.PyItem
import com.rokid.context.canvas.helper.PyOutcome
import com.rokid.context.canvas.helper.PyRequest
import com.rokid.context.canvas.helper.SchemaSubset
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.model.CallClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * The `py.fn` node of an experiment run (S6 tier 3, ASYNC only): the user's Python over the per-frame JSON that flows into the node. It NEVER runs anything on the phone:
 * a chunk of at most [PY_ITEMS_PER_JOB] frames becomes ONE `py_exec` job submitted through [helper] (`runPy` waits for the native tap, the helper and the result, and ends the
 * helper job when this coroutine is cancelled), and the answer is mapped back to one [NodeResult] per frame.
 *
 * What the helper sees: opaque item ids `i0`.. and each item's upstream JSON; frame ids stay on the phone ([PyRequest.frameIds], authoritative from the runner's `PhotoSource`).
 * The [HelperFence] in every request is the one [CanvasRunner] captured BEFORE it loaded the frames and handed down as [RunHelperFence]; an executor never reads a fence of
 * its own for a job (without one it fails closed, `no_fence`). [fenceNow] is used for two things only: [captureFence] for the runner, and telling a Forget-void from another void.
 *
 * Item error codes are fixed short texts, never helper or provider text: refusals before a job exists `no_helper`, `not_experiment`, `no_fence`, `bad_schema`, `bad_input`
 * (that item only), `refused`; helper outcomes `not_confirmed` (declined or expired tap: nothing was released), `killed` (helper silent / lease expired), `timeout`, `resources_busy`,
 * `needs_runtime`, `oom`, `runtime_error`, `bad_output`, `too_large`, `disk_low`; `frame_forget` (a Forget voided the job: the runner ends the run `STOPPED_FORGET`), `voided` (grant off,
 * Disconnect, session end), `cancelled`, `no_helper`; per item `timeout`, `schema_mismatch`, `exception`, `oom`, `too_big`; `no_result` (the helper did not answer this item).
 * Helper payloads (so the inputs) are integer-only for good: an input with a float is refused as a whole chunk (`refused`). Results may carry floats (decision D1, 2026-10-24): the helper hashes the exact text it
 * sends, the phone verifies the hash over that text and parses it ([com.rokid.context.canvas.helper.ResultWire]), and a float output passes an `outSchema` member of type `number` and fails `integer`.
 */
class PythonExecutor(private val helper: HelperJobPort?, private val fenceNow: suspend () -> HelperFence) : HelperExecutor {
    override val type = NodeTypes.PY_FN

    override suspend fun captureFence(): HelperFence = fenceNow()

    override fun chunkSize(node: PipelineNode) = PY_ITEMS_PER_JOB
    override fun nominalCalls(node: PipelineNode, items: Int) = 0
    override fun maxCalls(node: PipelineNode, items: Int) = 0

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        fun all(code: String) = items.map { NodeResult(it.frameId, null, error = code) }
        if (ctx.callClass != CallClass.EXPERIMENT) return all(NOT_EXPERIMENT)
        val port = helper ?: return all(NO_HELPER)
        val fence = coroutineContext[RunHelperFence]?.fence ?: return all(NO_FENCE)
        val source = (node.params["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val schema = parseSchema(node) ?: return all(BAD_SCHEMA)

        // an item whose input is not JSON (a photo, an extraction) cannot go to the helper: it fails alone, the rest are sent
        val sendable = items.filter { (it.value as? CanvasValue.Json) != null }
        if (sendable.isEmpty()) return all(BAD_INPUT)
        val request = PyRequest(
            source = source,
            items = sendable.mapIndexed { i, it -> PyItem("i$i", (it.value as CanvasValue.Json).value) },
            outSchema = schema,
            frameIds = sendable.map { it.frameId },
            fence = fence,
            perItemMs = HelperProtocol.PY_PER_ITEM_MS,
            jobMs = HelperProtocol.PY_JOB_MS,
        )
        val byId = sendable.withIndex().associate { "i${it.index}" to it.value.frameId }
        val results: Map<String, NodeResult> = when (val o = port.runPy(request)) {
            is PyOutcome.Done -> {
                val answered = o.outputs.associateBy { it.id }
                sendable.withIndex().associate { (i, item) ->
                    val out = answered["i$i"]
                    item.frameId to when {
                        out == null -> NodeResult(item.frameId, null, error = NO_RESULT)
                        out.ok && out.output != null -> NodeResult(item.frameId, CanvasValue.Json(out.output))
                        else -> NodeResult(item.frameId, null, error = out.code?.takeIf { it in HelperProtocol.ITEM_CODES } ?: NO_RESULT)
                    }
                }
            }
            else -> {
                val code = failureCode(o, fence)
                sendable.associate { it.frameId to NodeResult(it.frameId, null, error = code) }
            }
        }
        return items.map { results[it.frameId] ?: NodeResult(it.frameId, null, error = BAD_INPUT) }
    }

    private fun parseSchema(node: PipelineNode): JsonElement? {
        val text = (node.params["outSchema"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val schema = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return schema.takeIf { SchemaSubset.validateContract(it).isEmpty() }
    }

    /** One fixed code per non-Done outcome. A void is a `frame_forget` when the frame-Forget revision moved since the runner's capture, else a plain `voided`. */
    private suspend fun failureCode(o: PyOutcome, captured: HelperFence): String = when (o) {
        is PyOutcome.Done -> NO_RESULT
        is PyOutcome.Failed -> o.code.takeIf { it in HelperProtocol.FAIL_CODES } ?: "runtime_error"
        is PyOutcome.Refused -> REFUSED
        PyOutcome.NotConfirmed -> NOT_CONFIRMED
        PyOutcome.Voided -> if (runCatching { fenceNow().frameForgetRev }.getOrNull().let { it != null && it != captured.frameForgetRev }) FRAME_FORGET else VOIDED
        PyOutcome.Cancelled -> CANCELLED
        PyOutcome.NoHelper -> NO_HELPER
    }

    companion object {
        /** Items per job = `CanvasRunner.MAX_PHOTOS` = the protocol's item maximum. */
        const val PY_ITEMS_PER_JOB = HelperProtocol.PY_ITEMS_MAX

        const val NO_HELPER = "no_helper"
        const val NOT_EXPERIMENT = "not_experiment"
        const val NO_FENCE = "no_fence"
        const val BAD_SCHEMA = "bad_schema"
        const val BAD_INPUT = "bad_input"
        const val REFUSED = "refused"
        const val NOT_CONFIRMED = "not_confirmed"
        const val FRAME_FORGET = "frame_forget"
        const val VOIDED = "voided"
        const val CANCELLED = "cancelled"
        const val NO_RESULT = "no_result"
    }
}
