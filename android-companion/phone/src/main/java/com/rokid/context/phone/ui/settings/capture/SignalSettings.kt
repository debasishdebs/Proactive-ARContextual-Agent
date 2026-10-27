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
import com.rokid.context.capture.plan.FusionSettings
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
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
    /** Effective value of every phone fusion threshold (defaults where not edited). */
    val fusion: Map<String, Double> = FusionSettings.RANGES.mapValues { it.value.first },
)

/**
 * The derived-signal editor (v0.9.7 B). [problem] is the FIRST problem of [DerivedSignals.validate] (the validator `PUT /api/signals/derived/<name>` runs) or a parse problem; null while valid or while both fields are empty.
 * [canSave] only when both fields are filled and nothing is wrong.
 */
data class DerivedEditorUi(val open: Boolean = false, val name: String = "", val text: String = "", val editing: Boolean = false, val problem: String? = null, val canSave: Boolean = false,
    /** The check of the latest text is still pending (debounced, off the main thread): Save stays disabled. */
    val checking: Boolean = false,
    /** Bumped by every edit/open/close; a check result lands only on the state it was made for (one atomic update). */
    val version: Int = 0)

/** The editor's callbacks, bundled for the screen. */
class DerivedEditorActions(val onNew: () -> Unit = {}, val onEdit: (String) -> Unit = {}, val onName: (String) -> Unit = {}, val onText: (String) -> Unit = {}, val onSave: () -> Unit = {}, val onClose: () -> Unit = {})

/** Settings > Capture > Signals (v0.9.7 A). Thresholds go to the glasses on `pc_settings` (clamped by [normalized]); signals and derived definitions live in [SignalConfigStore]. */
class SignalSettingsViewModel(
    private val hud: HudSettingsControl,
    private val store: SignalConfigStore,
    private val glassesHash: StateFlow<String?>,
    private val fusionStore: FusionSettingsStore = FusionSettingsStore(),
    /** Where the expression is checked and saved (never the main thread in production) and how long typing must pause first. Tests pass Unconfined and 0. */
    editorDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val editorDebounceMs: Long = EDITOR_DEBOUNCE_MS,
) : ViewModel() {
    private fun of(h: com.rokid.context.core.model.HudSettings, c: SignalConfig, reported: String?, f: Map<String, Double> = fusionStore.overrides.value): SignalSettingsUi {
        val clean = ClassifierThresholds.clean(h.classifier)
        val hash = ClassifierThresholds.hash(clean)
        return SignalSettingsUi(
            ClassifierThresholds.RANGES.mapValues { (k, d) -> clean[k] ?: d.first }, hash, GlassesClassifierStatus.verdict(hash, reported),
            SignalCatalog.all.map { it.name to (it.name !in c.disabled) }, c.derived.map { it.name },
            FusionSettings.RANGES.mapValues { (k, d) -> FusionSettings.clean(f)[k] ?: d.first },
        )
    }

    /** The state right now, read from the three sources (what [state] converges to). */
    fun snapshot(): SignalSettingsUi = of(hud.settings.value, store.config.value, glassesHash.value)

    val state: StateFlow<SignalSettingsUi> = combine(hud.settings, store.config, glassesHash, fusionStore.overrides, ::of)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), of(hud.settings.value, store.config.value, glassesHash.value))

    fun setThreshold(key: String, value: Double) {
        if (key !in ClassifierThresholds.RANGES) return
        hud.update { it.copy(classifier = it.classifier + (key to value)).normalized() }
        PcLog.i(TAG, "classifier thresholds saved")
    }

    private val editorState = MutableStateFlow(DerivedEditorUi())
    val editor: StateFlow<DerivedEditorUi> = editorState
    private val editorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + editorDispatcher)
    private class Req(val name: String, val text: String, val editing: Boolean, val version: Int)
    private val requests = kotlinx.coroutines.flow.MutableSharedFlow<Req>(replay = 1, extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    init {
        // typing is debounced (Flow.debounce: a pause of editorDebounceMs) and only the newest request is checked, off the main thread
        editorScope.launch {
            requests.let { if (editorDebounceMs > 0) it.debounce(editorDebounceMs) else it }.collectLatest { r ->
                val out = check(true, r.name, r.text, r.editing)
                editorState.update { if (it.open && it.version == r.version) out.copy(version = it.version) else it }
            }
        }
    }
    /** Test seams: how many checks ran and the thread of the last one. */
    internal val checks = java.util.concurrent.atomic.AtomicInteger()
    @Volatile internal var lastCheckThread: String? = null
    @Volatile internal var checkHook: (() -> Unit)? = null

    override fun onCleared() { editorScope.cancel(); super.onCleared() }

    /** The same limit the API has for a request body ([com.rokid.context.phone.canvas.server.CanvasServer.MAX_BODY]), applied to the UTF-8 bytes BEFORE anything is parsed. */
    private fun check(open: Boolean, name: String, text: String, editing: Boolean): DerivedEditorUi {
        checks.incrementAndGet(); lastCheckThread = Thread.currentThread().name; checkHook?.invoke()
        if (name.isBlank() && text.isBlank()) return DerivedEditorUi(open, name, text, editing)
        if (utf8Size(text) > SignalConfigStore.MAX_DEFINITION_BYTES) return DerivedEditorUi(open, name, text, editing, "the expression is larger than ${SignalConfigStore.MAX_DEFINITION_BYTES / 1024} KiB", false)
        val graph = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
        val problem = if (graph == null) (if (text.isBlank()) "write the expression" else "the expression is not valid JSON")
        else DerivedSignals.validate(name, graph, store.config.value).firstOrNull()
        return DerivedEditorUi(open, name, text, editing, problem, problem == null)
    }

    /** The text is shown at once; its check runs after a pause in typing, off the main thread, and only the newest one lands. */
    private fun edit(name: String? = null, text: String? = null) {
        var req: Req? = null
        editorState.update { cur ->
            val n = name ?: cur.name; val t = text ?: cur.text
            val v = cur.version + 1
            req = Req(n, t, cur.editing, v)
            DerivedEditorUi(true, n, t, cur.editing, null, false, checking = !(n.isBlank() && t.isBlank()), version = v)
        }
        requests.tryEmit(req!!)
    }
    fun newDerived() { editorState.update { DerivedEditorUi(open = true, version = it.version + 1) } }
    fun editDerived(name: String) {
        val d = store.config.value.derived.firstOrNull { it.name == name } ?: return
        var req: Req? = null
        editorState.update { cur -> val v = cur.version + 1; req = Req(d.name, d.graph.toString(), true, v); DerivedEditorUi(true, d.name, d.graph.toString(), true, null, false, checking = true, version = v) }
        requests.tryEmit(req!!)
    }
    fun setEditorName(name: String) = edit(name = name)
    fun setEditorText(text: String) = edit(text = text)
    fun closeEditor() { editorState.update { DerivedEditorUi(version = it.version + 1) } }
    /** Saves through [SignalConfigStore.saveDerived] (validated and size-checked again there; the 16-entry history and the logging are its), off the main thread. Closes the editor on success. */
    fun saveEditor() {
        val e = editorState.value
        if (!e.canSave) return
        editorScope.launch {
            val problems = store.saveDerived(e.name, runCatching { kotlinx.serialization.json.Json.parseToJsonElement(e.text) }.getOrNull())
            // the result lands only on the editor it was made for: typed over or closed meanwhile = left alone
            editorState.update { if (!it.open || it.version != e.version) it else if (problems.isEmpty()) DerivedEditorUi(version = it.version + 1) else it.copy(problem = problems.first(), canSave = false) }
        }
    }
    fun editorActions() = DerivedEditorActions(::newDerived, ::editDerived, ::setEditorName, ::setEditorText, ::saveEditor, ::closeEditor)

    fun resetThresholds() { hud.update { it.copy(classifier = emptyMap()) }; PcLog.i(TAG, "classifier thresholds saved") }
    fun setFusion(key: String, value: Double) = fusionStore.set(key, value)
    fun resetFusion() = fusionStore.reset()
    fun setSignalEnabled(name: String, on: Boolean) { store.setEnabled(name, on) }
    fun deleteDerived(name: String) { store.deleteDerived(name) }

    companion object {
        private const val TAG = "SignalSettings"
        const val EDITOR_DEBOUNCE_MS = 250L
        internal fun utf8Size(t: String): Int { var n = 0; var i = 0; while (i < t.length) { val c = t[i]; n += when { c.code < 0x80 -> 1; c.code < 0x800 -> 2; Character.isHighSurrogate(c) && i + 1 < t.length -> { i++; 4 }; else -> 3 }; i++ }; return n }

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
        private val FUSION_LABELS = mapOf(
            "fastKmh" to "Vehicle: speed above (km/h)", "fastTtlMs" to "Vehicle: a speed reading counts for (ms)", "fastConfirmGapMs" to "Vehicle: two readings at most apart (ms)",
            "vehicleHoldMs" to "Vehicle: kept after the signal stops (ms)", "enterVehicleMs" to "Vehicle: must last (ms)", "switchDriverMs" to "Driver/passenger switch must last (ms)",
            "enterRestMs" to "Rest: must last (ms)", "enterUnknownMs" to "Leaving walking or unknown: must last (ms)", "hintStillTtlMs" to "Rest: a glasses report counts for (ms)",
            "stillFrac" to "Rest: still share of the last 5 min", "stillFracWithImage" to "Rest with a photo hint: still share",
        )
        fun fusionLabel(key: String) = FUSION_LABELS[key] ?: key
        fun label(key: String) = LABELS[key] ?: key
        fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }
}

@Composable
fun SignalSettingsScreen(
    state: SignalSettingsUi, onBack: () -> Unit,
    editor: DerivedEditorUi = DerivedEditorUi(), editorActions: DerivedEditorActions = DerivedEditorActions(),
    onFusion: (String, Double) -> Unit = { _, _ -> }, onFusionReset: () -> Unit = {},
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
                Text("Phone fusion (rest, walking, vehicle)", style = MaterialTheme.typography.titleMedium)
                for ((key, range) in FusionSettings.RANGES) {
                    val cur = state.fusion.getValue(key)
                    val opts = FusionSettings.options(key, cur)
                    ChoiceRow(
                        SignalSettingsViewModel.fusionLabel(key), opts.map { SignalSettingsViewModel.fmt(it) + if (it == range.first) " (default)" else "" },
                        opts.indexOf(cur).coerceAtLeast(0), { onFusion(key, opts[it]) }, tag = "fusion_$key",
                    )
                }
                TextButton(onClick = onFusionReset, modifier = Modifier.testTag("fusion_reset")) { Text("Back to defaults") }
                NoticeText("How the phone decides rest, walking or vehicle from its sources. Applies at once; the states stay still, walking and vehicle.", tag = "fusion_note")
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
                        Button(onClick = editorActions.onSave, enabled = editor.canSave && !editor.checking, modifier = Modifier.testTag("derived_save")) { Text("Save") }
                        TextButton(onClick = editorActions.onClose, modifier = Modifier.testTag("derived_close")) { Text("Cancel") }
                    }
                }
                NoticeText("Up to 8 derived signals. Guards that use them run in shadow or trial only.", tag = "derived_note")
            }
        }
    }
}
