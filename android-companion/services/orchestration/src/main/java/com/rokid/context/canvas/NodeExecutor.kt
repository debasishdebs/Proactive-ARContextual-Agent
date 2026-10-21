package com.rokid.context.canvas

import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.model.LanguageModel
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.prompt.PersonaSettings
import com.rokid.context.prompt.PromptRepository
import java.time.LocalDateTime

data class CanvasItem(val frameId: String, val photo: ExtractPhoto, val value: CanvasValue)

data class CallMeta(
    val latencyMs: Long, val promptTokens: Int?, val completionTokens: Int?, val model: String?, val promptVersion: Int?, val batchId: String?,
)

/** [error] is a fixed short text (a failure kind or a canvas code), never provider text. */
data class NodeResult(
    val frameId: String, val value: CanvasValue?, val branch: String? = null, val error: String? = null, val meta: CallMeta? = null, val calls: Int = 0,
)

class RunContext(
    val runId: String,
    val runner: RoleRunner,
    val model: LanguageModel,
    val prompts: PromptRepository,
    val persona: PersonaSettings,
    val bindings: suspend (Role) -> RoleBinding?,
    val now: () -> LocalDateTime = LocalDateTime::now,
)

interface NodeExecutor {
    val type: String
    fun chunkSize(node: PipelineNode): Int = 1
    fun nominalCalls(node: PipelineNode, items: Int): Int
    fun maxCalls(node: PipelineNode, items: Int): Int
    /** One result per input item, in the same order. */
    suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult>
}
