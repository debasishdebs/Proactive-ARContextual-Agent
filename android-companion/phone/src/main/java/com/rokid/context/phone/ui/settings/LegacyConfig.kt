package com.rokid.context.phone.ui.settings

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Role
import com.rokid.context.ai.TestResult
import com.rokid.context.core.ui.components.Banner
import com.rokid.context.core.ui.components.BannerTone
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.orchestration.LegacyConfigBridge
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.ui.config.ConfigHubScreen
import com.rokid.context.ui.config.ConfigUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Test button text: `EndpointTester`'s short message (its fixed `humanize` set) + latency — never provider text. */
object ConfigTestText {
    const val NOT_SET_UP = "Not set up — enter an address and key"
    const val FAILED = "Test failed"
    const val NOT_APPLIED = "Not tested — settings not applied"

    fun of(result: TestResult?): String = when {
        result == null -> NOT_SET_UP
        result.ok -> "OK · ${result.latencyMs} ms"
        else -> "${result.message} · ${result.latencyMs} ms"
    }
}

data class LegacyConfigUiState(val config: ConfigUiState, val notice: String? = null)

/**
 * The legacy Config screen's state and callbacks, moved out of the old god-composable (kept until Phase 5's AI settings).
 * Prefs writes and registry syncs run on one serial IO lane so they apply in typing order; text edits sync after a
 * 600 ms debounce (Phase 2 ruling); Test flushes the pending sync first so it tests what the workers will use.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyConfigViewModel(
    private val context: Context,
    private val pipeline: PipelineControl,
    private val ai: () -> AiGraph = { AiGraph.get(context) },
) : ViewModel() {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var syncJob: Job? = null
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<LegacyConfigUiState> = _state.asStateFlow()

    private fun initial(): LegacyConfigUiState {
        val c = AppConfigStore.get(context)
        return LegacyConfigUiState(
            ConfigUiState(
                pipelineEnabled = pipeline.enabled.value, localModeEnabled = c.localModeEnabled, encryptionEnabled = c.encryptionEnabled,
                memoryWindow = c.memoryWindow, persona = c.persona, providerType = c.providerType, endpoint = c.endpoint, apiKeyMasked = "", apiKeySaved = c.apiKey.isNotBlank(),
                deploymentOrModel = c.deploymentOrModel, nativeModelPath = c.nativeModelPath,
                glassesSilencyMode = AppConfigStore.glassesSilencyMode(context), testResult = NOT_TESTED,
            ),
        )
    }

    private fun config(change: ConfigUiState.() -> ConfigUiState) = _state.update { it.copy(config = it.config.change()) }

    fun togglePipeline(enabled: Boolean) { pipeline.setEnabled(enabled); config { copy(pipelineEnabled = enabled) } }

    fun toggleLocalMode(enabled: Boolean) {
        config { copy(localModeEnabled = enabled) }
        viewModelScope.launch(io) { AppConfigStore.setLocalModeEnabled(context, enabled, syncRegistry = false) }
        syncLater(0)
    }

    fun toggleEncryption(enabled: Boolean) {
        config { copy(encryptionEnabled = enabled) }
        viewModelScope.launch(io) { AppConfigStore.setEncryptionEnabled(context, enabled) }
    }

    fun cycleMemoryWindow() {
        val next = when (_state.value.config.memoryWindow) { "24h" -> "7d"; "7d" -> "30d"; else -> "24h" }
        config { copy(memoryWindow = next) }
        viewModelScope.launch(io) { AppConfigStore.setMemoryWindow(context, next) }
    }

    fun selectProvider(type: String) { config { copy(providerType = type, testResult = NOT_TESTED) }; persist(); syncLater(0) }
    fun updateEndpoint(value: String) { config { copy(endpoint = value) }; persist(); syncLater(TYPING_DEBOUNCE_MS) }
    /** Write-only field: the state holds only what was typed this session (cleared once synced); the stored key is never read into it. */
    fun updateApiKey(value: String) {
        config { copy(apiKeyMasked = value) }
        persist()
        syncLater(TYPING_DEBOUNCE_MS)
    }

    fun clearApiKey() {
        config { copy(apiKeyMasked = "", apiKeySaved = false) }
        viewModelScope.launch(io) { saveProvider(_state.value.config, key = "") }
        syncLater(0)
    }

    fun updateModel(value: String) { config { copy(deploymentOrModel = value) }; persist(); syncLater(TYPING_DEBOUNCE_MS) }
    fun updateNativePath(value: String) { config { copy(nativeModelPath = value) }; persist(); syncLater(TYPING_DEBOUNCE_MS) }

    /** Stored only: the RFCOMM push this used is removed (Phase 3); HUD verbosity is pushed via HudSettings (Phase 3/5). */
    fun selectGlassesSilencyMode(mode: String) {
        config { copy(glassesSilencyMode = mode) }
        viewModelScope.launch(io) { AppConfigStore.setGlassesSilencyMode(context, mode) }
    }

    fun testConnectivity() {
        syncJob?.cancel()
        config { copy(testResult = "Testing…") }
        val sent = _state.value.config.apiKeyMasked
        viewModelScope.launch {
            val text: String? = try {
                withContext(io) {
                    if (LegacyConfigBridge.sync(context).isFailure) {
                        null
                    } else {
                        val graph = ai()
                        val binding = graph.bindings.get(Role.ANSWER)
                        ConfigTestText.of(binding?.let { graph.tester.test(it.endpointId, it.model) })
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.i(TAG, "config test failed (${e.javaClass.simpleName})")
                ConfigTestText.FAILED
            }
            if (text == null) {
                config { copy(testResult = ConfigTestText.NOT_APPLIED) }
                _state.update { it.copy(notice = NOT_APPLIED) }
            } else {
                _state.update { it.copy(notice = null) }
                config { copy(testResult = text) }
                typedKeySynced(sent)
            }
        }
    }

    private fun persist() {
        val c = _state.value.config
        viewModelScope.launch(io) { saveProvider(c, key = c.apiKeyMasked.ifEmpty { null }) }
    }

    /** [key] null keeps the stored key; "" clears it. */
    private fun saveProvider(c: ConfigUiState, key: String?) {
        AppConfigStore.setProvider(context, c.providerType, c.endpoint, key ?: AppConfigStore.get(context).apiKey, c.deploymentOrModel, c.nativeModelPath, syncRegistry = false)
    }

    /** After a successful sync the typed key is stored: drop the buffer (unless the user kept typing) and show "Key saved". */
    private fun typedKeySynced(sent: String) {
        if (sent.isNotEmpty()) config { if (apiKeyMasked == sent) copy(apiKeyMasked = "", apiKeySaved = true) else this }
    }

    private fun syncLater(debounceMs: Long) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val sent = _state.value.config.apiKeyMasked
            val ok = withContext(io) { LegacyConfigBridge.sync(context).isSuccess }
            _state.update { it.copy(notice = if (ok) null else NOT_APPLIED) }
            if (ok) typedKeySynced(sent)
        }
    }

    companion object {
        const val NOT_TESTED = "Not tested"
        const val NOT_APPLIED = "AI settings not applied — check the address, key and model"
        const val TYPING_DEBOUNCE_MS = 600L
        private const val TAG = "LegacyConfig"
    }
}

@Composable
fun LegacyConfigScreen(
    state: LegacyConfigUiState,
    onBack: () -> Unit,
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
    onClearApiKey: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface)) {
        ScreenHeader("Model endpoint", onBack)
        state.notice?.let { Banner(BannerTone.AMBER, it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            ConfigHubScreen.Render(
                state = state.config, showAdvancedReadOnly = true, onTogglePipeline = onTogglePipeline, onToggleLocalMode = onToggleLocalMode,
                onToggleEncryption = onToggleEncryption, onCycleMemoryWindow = onCycleMemoryWindow, onSelectProvider = onSelectProvider,
                onUpdateEndpoint = onUpdateEndpoint, onUpdateApiKey = onUpdateApiKey, onUpdateDeploymentOrModel = onUpdateDeploymentOrModel,
                onUpdateNativePath = onUpdateNativePath, onSelectGlassesSilencyMode = onSelectGlassesSilencyMode,
                onTestConnectivity = onTestConnectivity, onBack = onBack, onClearApiKey = onClearApiKey,
            )
        }
    }
}
