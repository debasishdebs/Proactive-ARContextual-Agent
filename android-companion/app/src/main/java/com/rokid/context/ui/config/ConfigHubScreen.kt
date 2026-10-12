package com.rokid.context.ui.config

import com.rokid.context.BuildConfig
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

data class ConfigExport(
    val providerType: String,
    val endpoint: String,
    val model: String,
    val timeoutMs: Long,
    val apiKeyMasked: String,
    val localRuntimeMode: String,
    val nativeModelConfigured: Boolean,
)

data class ConfigUiState(
    val pipelineEnabled: Boolean,
    val localModeEnabled: Boolean,
    val encryptionEnabled: Boolean,
    val memoryWindow: String,
    val persona: String,
    val providerType: String,
    val endpoint: String,
    val apiKeyMasked: String,
    val deploymentOrModel: String,
    val nativeModelPath: String,
    val glassesSilencyMode: String,
    val testResult: String,
)

object ConfigHubScreen {
    fun buildExportPayload(
        providerType: String,
        endpoint: String,
        model: String,
        timeoutMs: Long,
        apiKey: String,
        localRuntimeMode: String = BuildConfig.LOCAL_RUNTIME_MODE,
        nativeModelPath: String = BuildConfig.LOCAL_NATIVE_MODEL_PATH,
    ): ConfigExport {
        val masked = if (apiKey.isBlank()) "" else "****"
        return ConfigExport(
            providerType = providerType,
            endpoint = endpoint,
            model = model,
            timeoutMs = timeoutMs,
            apiKeyMasked = masked,
            localRuntimeMode = localRuntimeMode,
            nativeModelConfigured = nativeModelPath.isNotBlank(),
        )
    }

    @Composable
    fun Render(
        state: ConfigUiState,
        showAdvancedReadOnly: Boolean,
        onTogglePipeline: (Boolean) -> Unit,
        onToggleLocalMode: (Boolean) -> Unit,
        onToggleEncryption: (Boolean) -> Unit,
        onCycleMemoryWindow: () -> Unit,
        onSelectProvider: (String) -> Unit,
        onUpdateEndpoint: (String) -> Unit,
        onUpdateApiKey: (String) -> Unit,
        onUpdateDeploymentOrModel: (String) -> Unit,
        onUpdateNativePath: (String) -> Unit,
        onSelectGlassesSilencyMode: (String) -> Unit,
        onTestConnectivity: () -> Unit,
        onBack: () -> Unit,
    ) {
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Config Hub", style = MaterialTheme.typography.headlineMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Provider Config", style = MaterialTheme.typography.titleMedium)
                    val selectedProvider = state.providerType.lowercase()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSelectProvider("openai") },
                            colors = if (selectedProvider == "openai") {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        ) { Text("OpenAI") }
                        Button(
                            onClick = { onSelectProvider("llama_cpp") },
                            colors = if (selectedProvider == "llama_cpp") {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        ) { Text("Llama.cpp") }
                        Button(
                            onClick = { onSelectProvider("ollama") },
                            colors = if (selectedProvider == "ollama") {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        ) { Text("Ollama") }
                    }
                    Text("Selected: ${state.providerType}")

                    OutlinedTextField(
                        value = state.endpoint,
                        onValueChange = onUpdateEndpoint,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state.providerType == "openai") "Endpoint" else "Base URL") },
                    )
                    OutlinedTextField(
                        value = state.deploymentOrModel,
                        onValueChange = onUpdateDeploymentOrModel,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state.providerType == "openai") "Deployment" else "Model") },
                    )
                    OutlinedTextField(
                        value = state.apiKeyMasked,
                        onValueChange = onUpdateApiKey,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key / Token") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = state.nativeModelPath,
                        onValueChange = onUpdateNativePath,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Native Model Path (llama.cpp)") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onTestConnectivity) { Text("Test") }
                        Text(state.testResult)
                    }

                    Text("Local Mode / Runtime: ${BuildConfig.LOCAL_RUNTIME_MODE}")
                    Text(
                        if (BuildConfig.LOCAL_NATIVE_MODEL_PATH.isBlank()) {
                            "Native model path: not configured"
                        } else {
                            "Native model path: configured"
                        },
                    )
                    Text("Prompt Persona: ${state.persona}")
                    Text("Glasses Verbosity")
                    val selectedSilency = state.glassesSilencyMode.lowercase()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSelectGlassesSilencyMode("stealth") },
                            colors = if (selectedSilency == "stealth") {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        ) { Text("Silent") }
                        Button(
                            onClick = { onSelectGlassesSilencyMode("hud") },
                            colors = if (selectedSilency == "hud") {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        ) { Text("Medium") }
                        Button(
                            onClick = { onSelectGlassesSilencyMode("compact") },
                            colors = if (selectedSilency == "compact") {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        ) { Text("Max") }
                    }
                    val selectedLabel = when (selectedSilency) {
                        "stealth" -> "SILENT"
                        "compact" -> "MAX"
                        else -> "MEDIUM"
                    }
                    Text("Selected verbosity: $selectedLabel")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Pipeline Enabled")
                        Switch(checked = state.pipelineEnabled, onCheckedChange = onTogglePipeline)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Local Mode")
                        Switch(checked = state.localModeEnabled, onCheckedChange = onToggleLocalMode)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Local Encryption")
                        Switch(checked = state.encryptionEnabled, onCheckedChange = onToggleEncryption)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Memory Window: ${state.memoryWindow}")
                        Button(onClick = onCycleMemoryWindow) { Text("Change") }
                    }
                    if (showAdvancedReadOnly) Text("Advanced controls are visible as read-only for v1.1")
                }
            }
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
