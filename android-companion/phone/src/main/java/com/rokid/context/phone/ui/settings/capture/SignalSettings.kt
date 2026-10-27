package com.rokid.context.phone.ui.settings.capture

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.canvas.plan.DerivedSignals
import com.rokid.context.ai.canvas.plan.SignalConfig
import com.rokid.context.capture.plan.SignalCatalog
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.normalized
import com.rokid.context.core.model.signals.ClassifierThresholds
import com.rokid.context.phone.context.GlassesClassifierStatus
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.HudSettingsControl
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SignalSettingsUi(
    /** Effective value of every classifier threshold (defaults where not edited). */
    val values: Map<String, Double> = ClassifierThresholds.RANGES.mapValues { it.value.first },
    val hash: String = "default",
    /** "match" | "pending" | "unknown": what the glasses report against [hash]. */
    val glasses: String = "unknown",
    /** Catalogue signal to whether guards may use it. */
    val signals: List<Pair<String, Boolean>> = SignalCatalog.all.map { it.name to true },
    val derived: List<String> = emptyList(),
)

/**
 * The derived-signal editor (v0.9.7 B). [problem] is the FIRST problem of [DerivedSignals.validate] (the validator `PUT /api/signals/derived/<name>` runs) or a parse problem; null while valid or while both fields are empty.
 * [canSave] only when both fields are filled and nothing is wrong.
 */
data class DerivedEditorUi(val open: Boolean = false, val name: String = "", val text: String = "", val editing: Boolean = false, val problem: String? = null, val canSave: Boolean = false)

/** The editor's callbacks, bundled for the screen. */
class DerivedEditorActions(val onNew: () -> Unit = {}, val onEdit: (String) -> Unit = {}, val onName: (String) -> Unit = {}, val onText: (String) -> Unit = {}, val onSave: () -> Unit = {}, val onClose: () -> Unit = {})

/** Settings > Capture > Signals (v0.9.7 A). Thresholds go to the glasses on `pc_settings` (clamped by [normalized]); signals and derived definitions live in [SignalConfigStore]. */
class SignalSettingsViewModel(
    private val hud: HudSettingsControl,
    private val store: SignalConfigStore,
    private val glassesHash: StateFlow<String?>,
) : ViewModel() {
    private fun of(h: com.rokid.context.core.model.HudSettings, c: SignalConfig, reported: String?): SignalSettingsUi {
        val clean = ClassifierThresholds.clean(h.classifier)
        val hash = ClassifierThresholds.hash(clean)
        return SignalSettingsUi(
            ClassifierThresholds.RANGES.mapValues { (k, d) -> clean[k] ?: d.first }, hash, GlassesClassifierStatus.verdict(hash, reported),
            SignalCatalog.all.map { it.name to (it.name !in c.disabled) }, c.derived.map { it.name },
        )
    }

    /** The state right now, read from the three sources (what [state] converges to). */
    fun snapshot(): SignalSettingsUi = of(hud.settings.value, store.config.value, glassesHash.value)

    val state: StateFlow<SignalSettingsUi> = combine(hud.settings, store.config, glassesHash, ::of)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), of(hud.settings.value, store.config.value, glassesHash.value))

    fun setThreshold(key: String, value: Double) {
        if (key !in ClassifierThresholds.RANGES) return
        hud.update { it.copy(classifier = it.classifier + (key to value)).normalized() }
        PcLog.i(TAG, "classifier thresholds saved")
    }

    private val editorState = MutableStateFlow(DerivedEditorUi())
    val editor: StateFlow<DerivedEditorUi> = editorState

    private fun check(open: Boolean, name: String, text: String, editing: Boolean): DerivedEditorUi {
        if (name.isBlank() && text.isBlank()) return DerivedEditorUi(open, name, text, editing)
        val graph = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
        val problem = if (graph == null) (if (text.isBlank()) "write the expression" else "the expression is not valid JSON")
        else DerivedSignals.validate(name, graph, store.config.value).firstOrNull()
        return DerivedEditorUi(open, name, text, editing, problem, problem == null)
    }
    private fun edit(name: String = editorState.value.name, text: String = editorState.value.text) { editorState.value = check(true, name, text, editorState.value.editing) }
    fun newDerived() { editorState.value = DerivedEditorUi(open = true) }
    fun editDerived(name: String) {
        val d = store.config.value.derived.firstOrNull { it.name == name } ?: return
        editorState.value = check(true, d.name, d.graph.toString(), true)
    }
    fun setEditorName(name: String) = edit(name = name)
    fun setEditorText(text: String) = edit(text = text)
    fun closeEditor() { editorState.value = DerivedEditorUi() }
    /** Saves through [SignalConfigStore.saveDerived] (validated again there; the 16-entry history and the logging are its). Closes the editor on success. */
    fun saveEditor() {
        val e = editorState.value
        if (!e.canSave) return
        val problems = store.saveDerived(e.name, runCatching { kotlinx.serialization.json.Json.parseToJsonElement(e.text) }.getOrNull())
        if (problems.isEmpty()) closeEditor() else editorState.value = e.copy(problem = problems.first(), canSave = false)
    }
    fun editorActions() = DerivedEditorActions(::newDerived, ::editDerived, ::setEditorName, ::setEditorText, ::saveEditor, ::closeEditor)

    fun resetThresholds() { hud.update { it.copy(classifier = emptyMap()) }; PcLog.i(TAG, "classifier thresholds saved") }
    fun setSignalEnabled(name: String, on: Boolean) { store.setEnabled(name, on) }
    fun deleteDerived(name: String) { store.deleteDerived(name) }

    companion object {
        private const val TAG = "SignalSettings"

        /** Five presets across the safe range (min, between, default, between, max), plus a stored custom value so it stays selectable. */
        fun options(key: String, current: Double): List<Double> {
            val (d, lo, hi) = ClassifierThresholds.RANGES.getValue(key)
            fun r(x: Double) = Math.round(x * 1000.0) / 1000.0
            return listOf(lo, r((lo + d) / 2), d, r((d + hi) / 2), hi, r(current)).distinct().sorted()
        }

        private val LABELS = mapOf(
            "headMovingRadS" to "Head moving above (rad/s)",
            "walkVariance" to "Walking, steady: movement above", "walkMinHz" to "Walking, steady: slowest step (Hz)", "walkMaxHz" to "Walking, steady: fastest step (Hz)",
            "relaxedVariance" to "Walking, soft: movement above", "relaxedMinHz" to "Walking, soft: slowest step (Hz)", "relaxedMaxHz" to "Walking, soft: fastest step (Hz)",
            "relaxedHoldMs" to "Walking, soft: must last (ms)",
            "heurAccVar" to "Shadow model: walking above", "heurMinHz" to "Shadow model: slowest step (Hz)", "heurMaxHz" to "Shadow model: fastest step (Hz)", "heurAboveOnFrac" to "Shadow model: head moving share",
        )
        fun label(key: String) = LABELS[key] ?: key
        fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }
}

@Composable
fun SignalSettingsScreen(
    state: SignalSettingsUi, onBack: () -> Unit,
    editor: DerivedEditorUi = DerivedEditorUi(), editorActions: DerivedEditorActions = DerivedEditorActions(),
    onThreshold: (String, Double) -> Unit = { _, _ -> }, onReset: () -> Unit = {}, onSignal: (String, Boolean) -> Unit = { _, _ -> }, onDeleteDerived: (String) -> Unit = {},
) {
    SettingsPage("Signals", onBack, tag = "signal_settings") {
        item {
            SettingsCard {
                Text("Motion classifier", style = MaterialTheme.typography.titleMedium)
                for ((key, range) in ClassifierThresholds.RANGES) {
                    val cur = state.values.getValue(key)
                    val opts = SignalSettingsViewModel.options(key, cur)
                    ChoiceRow(
                        SignalSettingsViewModel.label(key), opts.map { SignalSettingsViewModel.fmt(it) + if (it == range.first) " (default)" else "" },
                        opts.indexOf(cur).coerceAtLeast(0), { onThreshold(key, opts[it]) }, tag = "threshold_$key",
                    )
                }
                TextButton(onClick = onReset, modifier = Modifier.testTag("thresholds_reset")) { Text("Back to defaults") }
                NoticeText(
                    when (state.glasses) { "match" -> "The glasses run these values."; "pending" -> "Waiting for the glasses to apply them."; else -> "The glasses have not reported their values yet (older glasses never do)." },
                    tag = "classifier_status",
                )
            }
        }
        item {
            SettingsCard {
                Text("Signals guards may use", style = MaterialTheme.typography.titleMedium)
                for ((name, on) in state.signals) SwitchRow(name, null, on, { onSignal(name, it) }, tag = "signal_$name")
                NoticeText("A guard that names a switched-off signal does not compile. Guards run in shadow or trial only until the live gate passes.", tag = "signal_note")
            }
        }
        item {
            SettingsCard {
                Text("Derived signals", style = MaterialTheme.typography.titleMedium)
                for (n in state.derived) {
                    Text("d_$n", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { editorActions.onEdit(n) }, modifier = Modifier.testTag("derived_${n}_edit")) { Text("Edit d_$n") }
                        TextButton(onClick = { onDeleteDerived(n) }, modifier = Modifier.testTag("derived_${n}_delete")) { Text("Delete d_$n") }
                    }
                }
                if (!editor.open) TextButton(onClick = editorActions.onNew, modifier = Modifier.testTag("derived_new")) { Text("New derived signal") }
                else {
                    OutlinedTextField(editor.name, editorActions.onName, label = { Text("Name (a-z, 0-9, _; up to 12)") }, singleLine = true, enabled = !editor.editing, modifier = Modifier.fillMaxWidth().testTag("derived_name"))
                    OutlinedTextField(editor.text, editorActions.onText, label = { Text("Expression (graph JSON)") }, minLines = 4, modifier = Modifier.fillMaxWidth().testTag("derived_text"))
                    editor.problem?.let { NoticeText(it, error = true, tag = "derived_problem") }
                    Row {
                        Button(onClick = editorActions.onSave, enabled = editor.canSave, modifier = Modifier.testTag("derived_save")) { Text("Save") }
                        TextButton(onClick = editorActions.onClose, modifier = Modifier.testTag("derived_close")) { Text("Cancel") }
                    }
                }
                NoticeText("Up to 8 derived signals. Guards that use them run in shadow or trial only.", tag = "derived_note")
            }
        }
    }
}
