package com.rokid.context.ui.config

data class ConfigExport(
    val providerType: String,
    val endpoint: String,
    val model: String,
    val timeoutMs: Long,
    val apiKeyMasked: String,
)

object ConfigHubScreen {
    fun buildExportPayload(
        providerType: String,
        endpoint: String,
        model: String,
        timeoutMs: Long,
        apiKey: String,
    ): ConfigExport {
        val masked = if (apiKey.isBlank()) "" else "****"
        return ConfigExport(
            providerType = providerType,
            endpoint = endpoint,
            model = model,
            timeoutMs = timeoutMs,
            apiKeyMasked = masked,
        )
    }
}
