package com.rokid.context.ai.canvas

import com.rokid.context.ai.contract.PromptKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class PortType { PHOTOS, EXTRACTION, JSON, PREVIEW }

data class NodeType(
    val type: String,
    val label: String,
    val inputs: Set<PortType>,
    val output: PortType,
    val promptKey: PromptKey? = null,
    val branches: (JsonObject) -> List<String> = { emptyList() },
)

object NodeTypes {
    const val INPUT_PHOTOS = "input.photos"
    const val VISION_EXTRACT = "ai.vision_extract"
    const val LOCAL_CRITIC = "gate.local_critic"
    const val CRITIC = "ai.critic"
    const val PROMPT = "ai.prompt"
    const val SWITCH = "route.switch"
    const val MEMORY_WRITE = "memory.write"

    const val OTHER = "other"

    /** The `cases` labels of a switch; non-string entries are ignored (the validator rejects them via the count). */
    fun switchCases(params: JsonObject): List<String> =
        (params["cases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    val all: List<NodeType> = listOf(
        NodeType(INPUT_PHOTOS, "Photo", emptySet(), PortType.PHOTOS),
        NodeType(VISION_EXTRACT, "Facts from frames", setOf(PortType.PHOTOS, PortType.JSON), PortType.EXTRACTION, PromptKey.VISION_EXTRACT),
        NodeType(LOCAL_CRITIC, "Local trust check", setOf(PortType.EXTRACTION), PortType.EXTRACTION, branches = { listOf("trusted", "unsure") }),
        NodeType(CRITIC, "Critic", setOf(PortType.EXTRACTION), PortType.EXTRACTION, PromptKey.CRITIC, branches = { listOf("pass", "fail") }),
        NodeType(PROMPT, "Prompt", setOf(PortType.PHOTOS, PortType.JSON, PortType.EXTRACTION), PortType.JSON),
        NodeType(SWITCH, "Switch", setOf(PortType.JSON), PortType.JSON, branches = { switchCases(it) + OTHER }),
        NodeType(MEMORY_WRITE, "Save memory (preview)", setOf(PortType.EXTRACTION), PortType.PREVIEW),
    )

    private val index = all.associateBy { it.type }
    fun byType(t: String): NodeType? = index[t]
}
