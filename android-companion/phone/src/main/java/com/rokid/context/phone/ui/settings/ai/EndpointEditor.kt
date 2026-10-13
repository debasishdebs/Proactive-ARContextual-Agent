package com.rokid.context.phone.ui.settings.ai

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.EndpointRules
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.TextFieldRow
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class EndpointEditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val isNew: Boolean = true,
    val name: String = "",
    val kind: EndpointKind = EndpointKind.OPENAI_COMPAT,
    val baseUrl: String = "",
    val apiVersion: String = "",
    val models: String = "",
    val capabilities: Set<Capability> = setOf(Capability.CHAT),
    /** Write-only buffer: what was typed this session; cleared once saved. The stored key is never read into state. */
    val keyInput: String = "",
    val keySaved: Boolean = false,
    val testModel: String = "",
    val testResult: String = EndpointTestText.NOT_TESTED,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val deleted: Boolean = false,
) {
    val keyless: Boolean get() = kind in EndpointRules.KEYLESS
    val modelList: List<String> get() = models.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** spec §4.1 editor: Save validates through EndpointRules; Test saves pending edits first, then runs EndpointTester. */
class EndpointEditorViewModel(
    private val id: String,
    private val ai: AiGraph,
    newId: () -> String = { "ep_" + UUID.randomUUID().toString().replace("-", "").take(8) },
) : ViewModel() {
    private val endpointId = if (id == Routes.NEW_ENDPOINT) newId() else id
    private val _state = MutableStateFlow(EndpointEditorUiState())
    val state: StateFlow<EndpointEditorUiState> = _state.asStateFlow()
    private var clearKey = false
    private var createdAt = System.currentTimeMillis()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            if (id == Routes.NEW_ENDPOINT) { _state.update { it.copy(loading = false) }; return@launch }
            val e = ai.endpoints.byId(id) ?: run { _state.update { it.copy(loading = false, notFound = true) }; return@launch }
            createdAt = e.createdAt
            _state.update {
                it.copy(loading = false, isNew = false, name = e.name, kind = e.kind, baseUrl = e.baseUrl, apiVersion = e.apiVersion.orEmpty(),
                    models = e.models.joinToString(", "), capabilities = e.capabilities, keySaved = ai.endpoints.keyFor(e) != null, testModel = e.models.firstOrNull().orEmpty())
            }
        }
    }

    private fun edit(f: (EndpointEditorUiState) -> EndpointEditorUiState) = _state.update { f(it).copy(error = null, notice = null) }
    fun onName(v: String) = edit { it.copy(name = v.take(80)) }
    fun onKind(k: EndpointKind) = edit { it.copy(kind = k) }
    fun onBaseUrl(v: String) = edit { it.copy(baseUrl = v.take(500)) }
    fun onApiVersion(v: String) = edit { it.copy(apiVersion = v.take(40)) }
    fun onModels(v: String) = edit { it.copy(models = v.take(500), testModel = it.testModel.ifEmpty { it.modelList.firstOrNull().orEmpty() }) }
    fun onCapability(c: Capability, on: Boolean) = edit { it.copy(capabilities = if (on) it.capabilities + c else it.capabilities - c) }
    fun onKey(v: String) { clearKey = false; edit { it.copy(keyInput = v.take(400)) } }
    fun clearKey() { clearKey = true; edit { it.copy(keyInput = "", keySaved = false) } }
    fun onTestModel(m: String) = edit { it.copy(testModel = m) }

    fun save() = run { persist()?.let { _state.update { it.copy(notice = "Saved") } } }

    fun test() = run {
        if (persist() == null) return@run
        val s = _state.value
        val model = s.testModel.ifEmpty { s.modelList.firstOrNull().orEmpty() }
        _state.update { it.copy(testResult = EndpointTestText.TESTING) }
        _state.update { it.copy(testResult = EndpointTestText.of(ai.tester.test(endpointId, model))) }
    }

    fun delete() = run {
        val used = ai.bindings.usages(endpointId)
        if (used.isNotEmpty()) { _state.update { it.copy(error = "Used by ${used.joinToString(", ") { r -> AiLabels.role(r) }} — choose another model there first") }; return@run }
        ai.endpoints.delete(endpointId)
        _state.update { it.copy(deleted = true) }
    }

    /** Saves the form; returns the stored endpoint, or null with [EndpointEditorUiState.error] set. */
    private suspend fun persist(): Endpoint? {
        val s = _state.value
        val apiKey: String? = when {
            s.keyless -> ""
            clearKey -> ""
            s.keyInput.isNotEmpty() -> s.keyInput
            else -> null
        }
        val draft = Endpoint(endpointId, s.name, s.kind, s.baseUrl, s.apiVersion.ifBlank { null }, s.modelList, s.capabilities, createdAt)
        return ai.endpoints.save(draft, apiKey).fold(
            onSuccess = { saved ->
                clearKey = false
                val hasKey = ai.endpoints.keyFor(saved) != null
                _state.update { it.copy(isNew = false, name = saved.name, baseUrl = saved.baseUrl, models = saved.models.joinToString(", "), apiVersion = saved.apiVersion.orEmpty(), keyInput = "", keySaved = hasKey) }
                saved
            },
            onFailure = { e ->
                if (e is CancellationException) throw e
                val msg = (e as? IllegalArgumentException)?.message?.takeIf { it.length <= 120 } ?: "Couldn't save the endpoint"
                if (e !is IllegalArgumentException) Log.i(TAG, "endpoint save failed (${e.javaClass.simpleName})")
                _state.update { it.copy(error = msg) }
                null
            },
        )
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        job = viewModelScope.launch { try { block() } finally { _state.update { it.copy(busy = false) } } }
    }

    private companion object { const val TAG = "EndpointEditor" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EndpointEditorScreen(
    state: EndpointEditorUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onKind: (EndpointKind) -> Unit,
    onBaseUrl: (String) -> Unit,
    onApiVersion: (String) -> Unit,
    onModels: (String) -> Unit,
    onCapability: (Capability, Boolean) -> Unit,
    onKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onTestModel: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    SettingsPage(if (state.isNew) "New endpoint" else state.name.ifEmpty { "Endpoint" }, onBack, tag = "endpoint_editor") {
        if (state.notFound) { item { SettingsCard { NoticeText("This endpoint no longer exists") } }; return@SettingsPage }
        // One item: the Save/Test card is composed with the form (and reachable by tests) however tall the form grows.
        item {
            Column {
                SettingsCard {
                    TextFieldRow("Name", state.name, onName, tag = "endpoint_name")
                    val kinds = EndpointKind.entries
                    ChoiceRow("Type", kinds.map(AiLabels::kind), kinds.indexOf(state.kind), { onKind(kinds[it]) }, tag = "endpoint_kind")
                    TextFieldRow(if (state.kind == EndpointKind.LLAMACPP_LOCAL) "Model file path" else "Address", state.baseUrl, onBaseUrl, tag = "endpoint_url",
                        supporting = if (state.kind == EndpointKind.OLLAMA) "Plain http works for localhost or a .local / .lan name" else null)
                    if (state.kind == EndpointKind.AZURE_OPENAI) TextFieldRow("API version", state.apiVersion, onApiVersion, tag = "endpoint_api_version", placeholder = "2024-10-21")
                    TextFieldRow("Models (comma-separated)", state.models, onModels, tag = "endpoint_models", singleLine = false)
                    Text("Can do", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // four chips do not fit one phone-width row
                        Capability.entries.forEach { c ->
                            FilterChip(selected = c in state.capabilities, onClick = { onCapability(c, c !in state.capabilities) }, label = { Text(AiLabels.capability(c)) },
                                modifier = Modifier.testTag("cap_${c.name}"))
                        }
                    }
                    if (!state.keyless) {
                        TextFieldRow("API key", state.keyInput, onKey, tag = "endpoint_key", secret = true,
                            placeholder = if (state.keySaved) "Key saved" else "Paste the key",
                            supporting = if (state.keySaved) "Saved — type to replace" else null)
                        if (state.keySaved) SecondaryAction("Remove key", onClearKey, tag = "endpoint_clear_key")
                    }
                }
                SettingsCard {
                    val models = state.modelList
                    if (models.size > 1) ChoiceRow("Test with", models, models.indexOf(state.testModel).coerceAtLeast(0), { onTestModel(models[it]) }, tag = "endpoint_test_model")
                    PrimaryAction("Save", onSave, tag = "endpoint_save", enabled = !state.busy)
                    SecondaryAction("Test", onTest, tag = "endpoint_test", enabled = !state.busy)
                    NoticeText(state.testResult, tag = "endpoint_test_result")
                    state.error?.let { NoticeText(it, error = true, tag = "endpoint_error") }
                    state.notice?.let { NoticeText(it, tag = "endpoint_notice") }
                    if (!state.isNew) SecondaryAction("Delete endpoint", onDelete, tag = "endpoint_delete", enabled = !state.busy)
                }
            }
        }
    }
}
