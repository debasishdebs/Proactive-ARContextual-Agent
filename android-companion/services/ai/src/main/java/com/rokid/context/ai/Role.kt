package com.rokid.context.ai

/** spec §4.2. */
enum class Role(val requiredCapability: Capability) {
    VISION_EXTRACT(Capability.VISION),
    EXPLICIT_REMEMBER(Capability.CHAT),
    SUMMARIZE(Capability.CHAT),
    CRITIC(Capability.CHAT),
    ANSWER(Capability.CHAT),
    NUDGE(Capability.CHAT),
    EMBED(Capability.EMBEDDINGS),
}

data class RoleBinding(
    val role: Role,
    val endpointId: String,
    val model: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val fallbackEndpointId: String? = null,
    val fallbackModel: String? = null,
)
