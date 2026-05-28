package com.rokid.context.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

    @Composable
    fun Render(showAdvancedReadOnly: Boolean, onBack: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Config Hub", style = MaterialTheme.typography.headlineMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("LLM Config")
                    Text("Local Mode / Runtime")
                    Text("Prompt Personas")
                    Text("Shortcuts")
                    Text("Activity Identification")
                    Text("Memory Controls")
                    if (showAdvancedReadOnly) Text("Advanced controls are visible as read-only for v1.1")
                }
            }
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
