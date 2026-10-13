package com.rokid.context.phone.ui.settings.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.TextFieldRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EndpointChoice(val id: String, val name: String, val models: List<String>)

data class RoleRowUi(
    val role: Role,
    val label: String,
    val choices: List<EndpointChoice>,
    val endpointId: String?,
    val model: String,
    val temperature: String,
    val maxTokens: String,
    val fallbackId: String?,
    val error: String?,
)

data class RolesUiState(val rows: List<RoleRowUi> = emptyList())

/** spec §4.2: one binding per role; the picker lists only endpoints with the capability the role needs. */
class RolesViewModel(private val ai: AiGraph, private val onEmbedChanged: () -> Unit) : ViewModel() {
    /** Unsaved text for temperature/maxTokens and per-role errors. */
    private data class Draft(val temperature: String? = null, val maxTokens: String? = null, val error: String? = null)
    private val drafts = MutableStateFlow<Map<Role, Draft>>(emptyMap())
    private val writes = Mutex()

    val state: StateFlow<RolesUiState> = combine(ai.endpoints.observeAll(), ai.bindings.observeAll(), drafts) { endpoints, bindings, d ->
        RolesUiState(Role.entries.map { role ->
            val b = bindings.firstOrNull { it.role == role }
            val draft = d[role] ?: Draft()
            RoleRowUi(
                role, AiLabels.role(role),
                endpoints.filter { role.requiredCapability in it.capabilities }.map { EndpointChoice(it.id, it.name, it.models) },
                b?.endpointId, b?.model.orEmpty(),
                draft.temperature ?: b?.temperature?.toString().orEmpty(), draft.maxTokens ?: b?.maxTokens?.toString().orEmpty(),
                b?.fallbackEndpointId, draft.error,
            )
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RolesUiState())

    fun setEndpoint(role: Role, endpointId: String) = change(role) { b, eps ->
        val e = eps.firstOrNull { it.id == endpointId } ?: return@change null
        val current = b ?: RoleBinding(role, endpointId, "")
        if (current.endpointId == endpointId && b != null) return@change current // same endpoint: keep its model (no-op, no reindex)
        current.copy(endpointId = endpointId, model = e.models.firstOrNull().orEmpty()).let {
            // The fallback picker never offers the primary, so a fallback equal to the new primary is cleared.
            if (it.fallbackEndpointId == endpointId) it.copy(fallbackEndpointId = null, fallbackModel = null) else it
        }
    }

    fun setModel(role: Role, model: String) = change(role) { b, _ -> b?.copy(model = model) }

    fun setFallback(role: Role, endpointId: String?) = change(role) { b, eps ->
        b?.copy(fallbackEndpointId = endpointId, fallbackModel = endpointId?.let { id -> eps.firstOrNull { it.id == id }?.models?.firstOrNull() })
    }

    fun setTemperature(role: Role, text: String) {
        val t = text.trim()
        val value = t.toDoubleOrNull()
        if (t.isNotEmpty() && (value == null || value.isNaN() || value !in 0.0..2.0)) { draft(role) { it.copy(temperature = text, error = "Temperature is 0–2") }; return }
        draft(role) { it.copy(temperature = text, error = null) }
        change(role) { b, _ -> b?.copy(temperature = value) }
    }

    fun setMaxTokens(role: Role, text: String) {
        val t = text.trim()
        val value = t.toIntOrNull()
        if (t.isNotEmpty() && (value == null || value !in 1..8_192)) { draft(role) { it.copy(maxTokens = text, error = "Max tokens is 1–8192") }; return }
        draft(role) { it.copy(maxTokens = text, error = null) }
        change(role) { b, _ -> b?.copy(maxTokens = value) }
    }

    fun clear(role: Role) = viewModelScope.launch { ai.bindings.clear(role); if (role == Role.EMBED) onEmbedChanged() }

    private fun draft(role: Role, f: (Draft) -> Draft) = drafts.update { it + (role to f(it[role] ?: Draft())) }

    /** Serialised read-modify-write, so two quick edits (temperature, then max tokens) never overwrite each other. */
    private fun change(role: Role, f: (RoleBinding?, List<Endpoint>) -> RoleBinding?) = viewModelScope.launch {
        writes.withLock {
            val current = ai.bindings.get(role)
            val next = f(current, ai.bindings.eligibleEndpoints(role)) ?: return@withLock
            if (next == current) return@withLock // nothing changed: no write, and no reindex for EMBED
            ai.bindings.set(next).fold(
                onSuccess = { if (role == Role.EMBED) onEmbedChanged() },
                onFailure = { e -> draft(role) { it.copy(error = (e as? IllegalArgumentException)?.message ?: "Couldn't save") } },
            )
        }
    }
}

@Composable
fun RolesScreen(
    state: RolesUiState,
    onBack: () -> Unit,
    onEndpoint: (Role, String) -> Unit,
    onModel: (Role, String) -> Unit,
    onFallback: (Role, String?) -> Unit,
    onTemperature: (Role, String) -> Unit,
    onMaxTokens: (Role, String) -> Unit,
    onClear: (Role) -> Unit,
) {
    SettingsPage("Models by role", onBack, tag = "roles") {
        state.rows.forEach { r ->
            item(key = r.role.name) {
                SettingsCard {
                    Text(r.label, style = MaterialTheme.typography.titleMedium)
                    if (r.choices.isEmpty()) {
                        NoticeText("No endpoint can do this yet — add one with ${if (r.role == Role.EMBED) "Embeddings" else if (r.role == Role.VISION_EXTRACT) "Vision" else "Chat"}")
                    } else {
                        val ids = r.choices.map { it.id }
                        ChoiceRow("Endpoint", r.choices.map { it.name }, ids.indexOf(r.endpointId), { onEndpoint(r.role, ids[it]) }, tag = "role_${r.role.name}_endpoint")
                        val models = r.choices.firstOrNull { it.id == r.endpointId }?.models.orEmpty()
                        if (models.isNotEmpty()) ChoiceRow("Model", models, models.indexOf(r.model), { onModel(r.role, models[it]) }, tag = "role_${r.role.name}_model")
                        if (r.role != Role.EMBED && r.endpointId != null) {
                            TextFieldRow("Temperature", r.temperature, { onTemperature(r.role, it) }, tag = "role_${r.role.name}_temperature", number = true, placeholder = "provider default")
                            TextFieldRow("Max tokens", r.maxTokens, { onMaxTokens(r.role, it) }, tag = "role_${r.role.name}_max_tokens", number = true, placeholder = "provider default")
                            val fallbacks = listOf<String?>(null) + ids.filter { it != r.endpointId }
                            ChoiceRow("Fallback", fallbacks.map { id -> r.choices.firstOrNull { it.id == id }?.name ?: "None" }, fallbacks.indexOf(r.fallbackId).coerceAtLeast(0),
                                { onFallback(r.role, fallbacks[it]) }, tag = "role_${r.role.name}_fallback")
                        }
                        if (r.endpointId != null) SecondaryAction("Unset", { onClear(r.role) }, tag = "role_${r.role.name}_clear")
                    }
                    r.error?.let { NoticeText(it, error = true, tag = "role_${r.role.name}_error") }
                }
            }
        }
    }
}
