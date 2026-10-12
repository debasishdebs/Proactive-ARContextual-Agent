package com.rokid.context.ai

/** Provider-relevant subset of the legacy AppConfig; replaced by the endpoint registry in Phase 2. */
data class ProviderSettings(
    val providerType: String,
    val endpoint: String,
    val apiKey: String,
    val deploymentOrModel: String,
    val nativeModelPath: String,
    val localModeEnabled: Boolean,
)
