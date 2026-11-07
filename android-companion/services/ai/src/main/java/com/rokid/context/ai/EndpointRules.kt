package com.rokid.context.ai

/** Endpoint editor input rules (Phase 2 "hardening minors"): every saved endpoint passes through [clean]. */
object EndpointRules {
    /** Kinds whose providers never send a key. */
    val KEYLESS = setOf(EndpointKind.OLLAMA, EndpointKind.LLAMACPP_LOCAL)
    private const val AZURE_DEFAULT_VERSION = "2024-10-21"
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val CONTROL = Regex("[\\p{Cntrl}\\u2028\\u2029]")
    private val SPACES = Regex("\\s+")

    /** Kinds whose provider cannot put a photo on the wire (the on-device llama.cpp runtime takes text only). */
    val NO_IMAGES = setOf(EndpointKind.LLAMACPP_LOCAL)
    const val NO_IMAGES_MESSAGE = "On-device models can't read photos: turn off Vision"

    /** Codex r10: true when a photo sent to [e] reaches the model (VISION declared AND a kind that can send images). */
    fun deliversImages(e: Endpoint): Boolean = Capability.VISION in e.capabilities && e.kind !in NO_IMAGES

    fun clean(endpoint: Endpoint, hasKey: Boolean): Result<Endpoint> = runCatching {
        require(ID.matches(endpoint.id)) { "Invalid endpoint id" }
        require(!(endpoint.kind in NO_IMAGES && Capability.VISION in endpoint.capabilities)) { NO_IMAGES_MESSAGE }
        require(!(hasKey && endpoint.kind in KEYLESS)) { "This endpoint type doesn't use a key" }
        require(endpoint.capabilities.isNotEmpty()) { "Pick at least one capability" }
        val models = endpoint.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(models.none { m -> m.any { it == ',' || it.isWhitespace() } }) { "Model names can't contain commas or spaces" }
        val url = UrlNormalizer.normalize(endpoint.baseUrl, endpoint.kind, hasKey).getOrThrow()
        val name = endpoint.name.replace(CONTROL, " ").replace(SPACES, " ").trim().take(40).ifEmpty { hostOf(url) }
        val version = endpoint.apiVersion?.trim()?.ifEmpty { null }
        endpoint.copy(
            name = name, baseUrl = url, models = models,
            apiVersion = if (endpoint.kind == EndpointKind.AZURE_OPENAI) version ?: AZURE_DEFAULT_VERSION else null,
        )
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { "Endpoint" }.take(40)
}
