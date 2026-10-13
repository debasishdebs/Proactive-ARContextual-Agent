package com.rokid.context.phone.ui.settings.prompts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PromptRowUi(val key: PromptKey, val title: String, val subtitle: String)
data class PromptsListUiState(val rows: List<PromptRowUi> = emptyList(), val keepVersions: Boolean = true)

class PromptsListViewModel(private val ai: AiGraph) : ViewModel() {
    init { viewModelScope.launch { ai.prompts.ensureSeeded() } }

    val state: StateFlow<PromptsListUiState> = combine(ai.prompts.observeAllActive(), ai.prompts.settings.keepVersions) { active, keep ->
        PromptsListUiState(
            PromptKey.entries.mapNotNull { k ->
                active.firstOrNull { it.promptId == k.name }?.let { v -> PromptRowUi(k, k.label, "v${v.version}" + if (v.testedOk) " · tested ✓" else "") }
            },
            keep,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PromptsListUiState(keepVersions = ai.prompts.settings.keepVersions.value))

    fun setKeepVersions(on: Boolean) = ai.prompts.settings.setKeepVersions(on)
}

/** spec §2.4 Prompts: the "Keep prompt versions" toggle lives in this screen's header (default on). */
@Composable
fun PromptsListScreen(state: PromptsListUiState, onBack: () -> Unit, onKeepVersions: (Boolean) -> Unit, onOpen: (String) -> Unit) {
    SettingsPage("Prompts", onBack, tag = "prompts_list", actions = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep versions", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
            Switch(checked = state.keepVersions, onCheckedChange = onKeepVersions, modifier = Modifier.testTag("keep_versions"))
        }
    }) {
        item {
            SettingsCard {
                NoticeText(if (state.keepVersions) "Saving makes a new version and activates it." else "Saving overwrites the active version; older versions stay read-only.")
                Column { state.rows.forEach { r -> ChipRow(Icons.Outlined.Edit, PcColors.ChipRose, r.title, r.subtitle, onClick = { onOpen(r.key.name) }) } }
            }
        }
    }
}
