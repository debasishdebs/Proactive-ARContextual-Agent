package com.rokid.context.ai

enum class EndpointKind { AZURE_OPENAI, OPENAI_COMPAT, OLLAMA, LLAMACPP_LOCAL }
enum class Capability { CHAT, VISION, EMBEDDINGS, JSON_SCHEMA }

/** spec §4.1. For [EndpointKind.LLAMACPP_LOCAL], [baseUrl] holds the on-device model file path. */
data class Endpoint(
    val id: String,
    val name: String,
    val kind: EndpointKind,
    val baseUrl: String,
    val apiVersion: String?,
    val models: List<String>,
    val capabilities: Set<Capability>,
    val createdAt: Long,
)
