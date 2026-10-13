package com.rokid.context.phone.ui.settings.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Role
import com.rokid.context.ai.TestResult
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

object AiLabels {
    fun kind(k: EndpointKind) = when (k) {
        EndpointKind.AZURE_OPENAI -> "Azure OpenAI"; EndpointKind.OPENAI_COMPAT -> "OpenAI-compatible"
        EndpointKind.OLLAMA -> "Ollama"; EndpointKind.LLAMACPP_LOCAL -> "On-device (llama.cpp)"
    }
    fun capability(c: Capability) = when (c) { Capability.CHAT -> "Chat"; Capability.VISION -> "Vision"; Capability.EMBEDDINGS -> "Embeddings"; Capability.JSON_SCHEMA -> "Structured output" }
    fun role(r: Role) = when (r) {
        Role.VISION_EXTRACT -> "Facts from frames"; Role.EXPLICIT_REMEMBER -> "Remember this"; Role.SUMMARIZE -> "Re-summarise"
        Role.CRITIC -> "Critic"; Role.ANSWER -> "Answer"; Role.NUDGE -> "Nudge"; Role.EMBED -> "Embeddings"
    }
}

/** Test result as shown: `EndpointTester`'s short message (its fixed humanize set) + latency — never provider text. */
object EndpointTestText {
    const val NOT_TESTED = "Not tested"
    const val TESTING = "Testing…"
    /** The tester's message already says OK or what failed. */
    fun of(r: TestResult): String = "${r.message} · ${r.latencyMs} ms"
}

data class EndpointRowUi(val id: String, val name: String, val subtitle: String)
data class EndpointsListUiState(val rows: List<EndpointRowUi> = emptyList())

class EndpointsListViewModel(ai: AiGraph) : ViewModel() {
    val state: StateFlow<EndpointsListUiState> = combine(ai.endpoints.observeAll(), ai.bindings.observeAll()) { endpoints, bindings ->
        EndpointsListUiState(endpoints.map { e ->
            val used = bindings.filter { it.endpointId == e.id || it.fallbackEndpointId == e.id }.map { AiLabels.role(it.role) }
            val models = if (e.kind == EndpointKind.LLAMACPP_LOCAL) "model file" else "${e.models.size} model${if (e.models.size == 1) "" else "s"}"
            EndpointRowUi(e.id, e.name, listOfNotNull(AiLabels.kind(e.kind), models, used.takeIf { it.isNotEmpty() }?.let { "used by ${it.joinToString(", ")}" }).joinToString(" · "))
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EndpointsListUiState())
}

@Composable
fun EndpointsListScreen(state: EndpointsListUiState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    SettingsPage("Model endpoints", onBack, tag = "endpoints_list") {
        item {
            SettingsCard {
                if (state.rows.isEmpty()) NoticeText("No endpoints yet")
                Column { state.rows.forEach { r -> ChipRow(Icons.Outlined.Cloud, PcColors.ChipSky, r.name, r.subtitle, onClick = { onOpen(r.id) }) } }
                PrimaryAction("Add endpoint", { onOpen(Routes.NEW_ENDPOINT) }, tag = "endpoint_add")
            }
        }
    }
}
