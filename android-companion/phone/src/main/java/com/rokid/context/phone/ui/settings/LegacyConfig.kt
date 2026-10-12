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
    /** The key is never held in UI state (only [KEY_MASK] when one is set); a new one is kept here until persisted. */
    private var typedKey: String? = null
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<LegacyConfigUiState> = _state.asStateFlow()

    private fun initial(): LegacyConfigUiState {
        val c = AppConfigStore.get(context)
        return LegacyConfigUiState(
            ConfigUiState(
                pipelineEnabled = pipeline.enabled.value, localModeEnabled = c.localModeEnabled, encryptionEnabled = c.encryptionEnabled,
                memoryWindow = c.memoryWindow, persona = c.persona, providerType = c.providerType, endpoint = c.endpoint, apiKeyMasked = if (c.apiKey.isBlank()) "" else KEY_MASK,
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
    /** The field shows only [KEY_MASK] (or empty); typed characters arrive as "****x" and a backspace as "***". */
    fun updateApiKey(value: String) {
        val key = when {
            value.startsWith(KEY_MASK) -> (typedKey ?: "") + value.removePrefix(KEY_MASK)
            typedKey.isNullOrEmpty() -> ""
            else -> typedKey!!.dropLast(1)
        }
        typedKey = key
        config { copy(apiKeyMasked = if (key.isBlank()) "" else KEY_MASK) }
        persist()
        syncLater(TYPING_DEBOUNCE_MS)
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
        viewModelScope.launch {
            val text = try {
                withContext(io) {
                    LegacyConfigBridge.sync(context)
                    val graph = ai()
                    val binding = graph.bindings.get(Role.ANSWER)
                    ConfigTestText.of(binding?.let { graph.tester.test(it.endpointId, it.model) })
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.i(TAG, "config test failed (${e.javaClass.simpleName})")
                ConfigTestText.FAILED
            }
            config { copy(testResult = text) }
        }
    }

    private fun persist() {
        val c = _state.value.config
        val typed = typedKey
        viewModelScope.launch(io) {
            val key = typed ?: AppConfigStore.get(context).apiKey
            AppConfigStore.setProvider(context, c.providerType, c.endpoint, key, c.deploymentOrModel, c.nativeModelPath, syncRegistry = false)
        }
    }

    private fun syncLater(debounceMs: Long) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val ok = withContext(io) { LegacyConfigBridge.sync(context).isSuccess }
            _state.update { it.copy(notice = if (ok) null else NOT_APPLIED) }
        }
    }

    companion object {
        const val KEY_MASK = "****"
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
                onTestConnectivity = onTestConnectivity, onBack = onBack,
            )
        }
    }
}
