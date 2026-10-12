package com.rokid.context.ui

import com.rokid.context.ui.config.ConfigHubScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigExportTest {
    @Test
    fun configExportExcludesSecrets() {
        val export = ConfigHubScreen.buildExportPayload(
            providerType = "cloud",
            endpoint = "https://api.example.com",
            model = "gpt-5.4",
            timeoutMs = 10_000,
            apiKey = "super-secret-key",
        )
        assertEquals("****", export.apiKeyMasked)
    }
}
