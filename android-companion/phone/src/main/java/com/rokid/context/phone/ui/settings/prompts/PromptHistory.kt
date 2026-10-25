package com.rokid.context.phone.ui.settings.prompts

import com.rokid.context.core.log.PcLog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.core.ui.components.CodeBox
import com.rokid.context.core.ui.components.VersionChip
import com.rokid.context.core.ui.components.VersionStrip
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.db.PromptVersionEntity
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.prompt.LineDiff
import com.rokid.context.prompt.PromptDraft
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PromptHistoryUiState(
    val label: String = "",
    val versions: List<VersionChip> = emptyList(),
    val selected: Int? = null,
    val template: String = "",
    val schema: String? = null,
    val note: String = "",
    val diff: List<LineDiff.Line> = emptyList(),
    val canRestore: Boolean = false,
    val notice: String? = null,
)

/** spec §4.3 history: view, diff against the previous version (template + schema), restore (= copy into the store's rule). */
class PromptHistoryViewModel(keyId: String, private val ai: AiGraph) : ViewModel() {
    private val key = PromptKey.parse(keyId)
    private val selected = MutableStateFlow<Int?>(null)
    private val notice = MutableStateFlow<String?>(null)

    val state: StateFlow<PromptHistoryUiState> = combine(key?.let { ai.prompts.history(it) } ?: flowOf(emptyList()), ai.prompts.observeAllActive(), selected, notice) { versions, actives, sel, n ->
        val active = actives.firstOrNull { it.promptId == key?.name }?.version
        val pick = versions.firstOrNull { it.version == sel } ?: versions.firstOrNull { it.version == active } ?: versions.firstOrNull()
        val previous = pick?.let { p -> versions.filter { it.version < p.version }.maxByOrNull { it.version } }
        PromptHistoryUiState(
            label = key?.label.orEmpty(),
            versions = versions.map { VersionChip(it.version, it.version == active, it.testedOk) },
            selected = pick?.version, template = pick?.template.orEmpty(), schema = pick?.outputSchema, note = pick?.note.orEmpty(),
            diff = if (pick == null) emptyList() else LineDiff.diff(text(previous), text(pick)),
            canRestore = pick != null && pick.version != active, notice = n,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PromptHistoryUiState())

    fun select(v: Int) { notice.value = null; selected.value = v }

    fun restore() {
        val k = key ?: return
        val v = state.value.selected ?: return
        viewModelScope.launch {
            try {
                // Gate (1) against the endpoints bound now: a version that fit an earlier binding may not fit this one.
                val source = ai.prompts.version(k, v) ?: run { notice.value = "That version no longer exists"; return@launch }
                if (ai.testBed.compatibility(k, PromptDraft(source.template, source.outputSchema)).isNotEmpty()) {
                    notice.value = "This version's schema doesn't fit the endpoints bound now"
                    return@launch
                }
                val keep = ai.prompts.settings.keepVersions.value
                val n = ai.prompts.restore(k, v) // a restored copy is untested until it is run
                selected.value = n
                notice.value = if (keep) "Restored as v$n" else "v$v copied over v$n"
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                PcLog.i("PromptHistory", "restore failed (${e.javaClass.simpleName})")
                notice.value = "Couldn't restore that version"
            }
        }
    }

    private fun text(v: PromptVersionEntity?): String = if (v == null) "" else v.template + (v.outputSchema?.let { "\n--- schema ---\n$it" } ?: "")
}

@Composable
fun PromptHistoryScreen(state: PromptHistoryUiState, onBack: () -> Unit, onSelect: (Int) -> Unit, onRestore: () -> Unit) {
    SettingsPage("${state.label} history", onBack, tag = "prompt_history_screen") {
        item { VersionStrip(state.versions, state.selected, onSelect, Modifier.testTag("prompt_versions")) }
        item {
            SettingsCard {
                if (state.note.isNotBlank()) NoticeText(state.note)
                Text("Changes from the previous version", style = MaterialTheme.typography.titleMedium)
                state.diff.filter { it.kind != LineDiff.Kind.SAME }.ifEmpty { listOf(LineDiff.Line(LineDiff.Kind.SAME, "No changes")) }.forEach { l ->
                    Text(
                        when (l.kind) { LineDiff.Kind.ADDED -> "+ "; LineDiff.Kind.REMOVED -> "- "; LineDiff.Kind.SAME -> "  " } + l.text,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                        color = when (l.kind) { LineDiff.Kind.ADDED -> PcColors.MintDeep; LineDiff.Kind.REMOVED -> PcColors.ChipCoral.second; LineDiff.Kind.SAME -> PcColors.InkSoft },
                    )
                }
                CodeBox(state.template, null, label = "Template", tag = "history_template")
                state.schema?.let { CodeBox(it, null, label = "Output schema", tag = "history_schema") }
                PrimaryAction("Restore this version", onRestore, tag = "prompt_restore", enabled = state.canRestore)
                state.notice?.let { NoticeText(it, tag = "history_notice") }
            }
        }
    }
}
