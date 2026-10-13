package com.rokid.context.phone.ui.settings.prompts

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.SaveGates
import com.rokid.context.ai.run.TestBedRequest
import com.rokid.context.ai.run.TestBedResult
import com.rokid.context.core.ui.components.CodeBox
import com.rokid.context.orchestration.TestBedInputs
import com.rokid.context.orchestration.TestInput
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.TextFieldRow
import com.rokid.context.phone.ui.settings.ai.EndpointChoice
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.TemplateVariables
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class InputKind(val label: String) { FRAME("Recent frame"), TEXT("Free text"), QUESTION("Question") }
data class FrameChoice(val id: String, val label: String)

data class PromptEditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val key: PromptKey? = null,
    val label: String = "",
    val activeVersion: Int = 0,
    val keepVersions: Boolean = true,
    val template: String = "",
    val schema: String = "",
    val activeDraft: PromptDraft? = null,
    val compatProblems: List<String> = emptyList(),
    /** The draft [compatProblems] were computed for; gate 1 counts only while it equals [draft] (preflight F19). */
    val compatFor: PromptDraft? = null,
    val inputKind: InputKind = InputKind.QUESTION,
    val inputText: String = "",
    val frames: List<FrameChoice> = emptyList(),
    val frameId: String? = null,
    val endpoints: List<EndpointChoice> = emptyList(),
    val endpointId: String? = null,
    val model: String = "",
    val running: Boolean = false,
    val result: TestBedResult? = null,
    val showWire: Boolean = false,
    val gates: SaveGates = SaveGates(false, false, false, false, false, false),
    val notice: String? = null,
    /** A Save is in progress: editing and Save are off until it ends. */
    val saving: Boolean = false,
) {
    val hasSchema: Boolean get() = key != null && key != PromptKey.SYSTEM
    val draft: PromptDraft get() = PromptDraft(template, schema.takeIf { hasSchema && it.isNotBlank() })
    val dirty: Boolean get() = activeDraft != null && draft != activeDraft
    val canSave: Boolean get() = dirty && gates.all && !running && !saving
    val unknownVariables: List<String> get() = TemplateVariables.unknown(template)
    val missingVariables: Set<String> get() = key?.let { TemplateVariables.missing(it, template) }.orEmpty()
    /** SYSTEM has no schema and no run, so only the Variables gate applies and only it is shown. */
    val gateLines: List<String> get() = (if (key == PromptKey.SYSTEM) listOf(gates.variables to "Variables") else listOf(
        gates.compatible to "Schema compatible", gates.variables to "Variables", gates.ran to "Ran",
        gates.validated to "Matches schema", gates.decoded to "Fits the contract", gates.semantic to "Checks passed",
    )).map { (ok, label) -> "${if (ok) "✓" else "✕"} $label" }
    /** Shown beside a run result: TestBed runs an unparseable draft schema on the default, so a run alone can look OK. */
    val runCompatLine: String? get() = when {
        result == null -> null
        gates.compatible -> "✓ Schema compatible"
        compatFor != draft -> "… Checking the schema"
        else -> "✕ Schema not compatible — fix it before saving"
    }
    val saveLabel: String get() = if (keepVersions) "Save as v${activeVersion + 1} & activate" else "Save over v$activeVersion"
}

/** spec §4.3–§4.5: edit the draft, run it on a chosen model with real inputs, Save only when every gate holds for it. */
@OptIn(FlowPreview::class)
class PromptEditorViewModel(
    private val keyId: String,
    private val ai: AiGraph,
    private val inputs: TestBedInputs,
    /** Gate (1). TestBed runs the checker off Main; injectable so tests can count checks. */
    private val compatibility: suspend (PromptKey, PromptDraft) -> List<String> = { k, d -> ai.testBed.compatibility(k, d) },
) : ViewModel() {
    private val _state = MutableStateFlow(PromptEditorUiState())
    val state: StateFlow<PromptEditorUiState> = _state.asStateFlow()
    private var lastRun: Pair<PromptDraft, TestBedResult>? = null
    private data class CheckRequest(val seq: Long, val debounceMs: Long)
    private val checkRequests = MutableStateFlow<CheckRequest?>(null)
    private var checkSeq = 0L
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    init {
        // The debounce waits on Default (real time, off Main); the check itself hops to Default inside TestBed.
        viewModelScope.launch {
            checkRequests.filterNotNull().debounce { it.debounceMs }.flowOn(Dispatchers.Default).collectLatest { check() }
        }
        viewModelScope.launch {
            val key = PromptKey.parse(keyId) ?: run { _state.update { it.copy(loading = false, notFound = true) }; return@launch }
            val active = ai.prompts.active(key)
            val endpoints = key.role?.let { ai.bindings.eligibleEndpoints(it) }.orEmpty().map { EndpointChoice(it.id, it.name, it.models) }
            val binding = key.role?.let { ai.bindings.get(it) }
            val endpointId = binding?.endpointId?.takeIf { id -> endpoints.any { it.id == id } } ?: endpoints.firstOrNull()?.id
            val model = binding?.model?.takeIf { binding.endpointId == endpointId } ?: endpoints.firstOrNull { it.id == endpointId }?.models?.firstOrNull().orEmpty()
            val frames = inputs.recentFrames().map { FrameChoice(it.id, "${time.format(Instant.ofEpochMilli(it.ts).atZone(ZoneId.systemDefault()))} · ${it.payload.take(40)}") }
            val kind = when (key) { PromptKey.VISION_EXTRACT -> if (frames.isNotEmpty()) InputKind.FRAME else InputKind.TEXT; PromptKey.ANSWER, PromptKey.NUDGE -> InputKind.QUESTION; else -> InputKind.TEXT }
            _state.update {
                it.copy(loading = false, key = key, label = key.label, activeVersion = active.version, keepVersions = ai.prompts.settings.keepVersions.value,
                    template = active.template, schema = active.outputSchema ?: DefaultSchemas.text(key).orEmpty(),
                    activeDraft = stored(key, active.template, active.outputSchema),
                    endpoints = endpoints, endpointId = endpointId, model = model, frames = frames, frameId = frames.firstOrNull()?.id, inputKind = kind,
                    inputText = if (kind == InputKind.QUESTION) "What did I see today?" else "").let(::regated)
            }
            recheck(debounceMs = 0)
        }
        viewModelScope.launch { ai.prompts.settings.keepVersions.collect { k -> _state.update { it.copy(keepVersions = k) } } }
    }

    /** The checker reads only the schema, so a template edit re-gates (variables) without a new schema check. */
    fun onTemplate(t: String) = edit { it.copy(template = t) }
    fun onSchema(s: String) { if (edit { it.copy(schema = s) }) recheck() }
    fun resetSchema() { if (edit { it.copy(schema = it.key?.let(DefaultSchemas::text).orEmpty()) }) recheck() }
    fun onInputKind(k: InputKind) = _state.update { it.copy(inputKind = k) }
    fun onInputText(t: String) = _state.update { it.copy(inputText = t.take(2_000)) }
    fun onFrame(id: String) = _state.update { it.copy(frameId = id) }
    fun onEndpoint(id: String) = _state.update { s -> s.copy(endpointId = id, model = s.endpoints.firstOrNull { it.id == id }?.models?.firstOrNull().orEmpty()) }
    fun onModel(m: String) = _state.update { it.copy(model = m) }
    fun toggleWire() = _state.update { it.copy(showWire = !it.showWire) }

    fun discard() {
        if (_state.value.saving) return
        lastRun = null
        if (edit { s -> s.activeDraft?.let { d -> s.copy(template = d.template, schema = d.outputSchema ?: s.key?.let(DefaultSchemas::text).orEmpty()) } ?: s }) recheck()
    }

    fun run() {
        val s = _state.value
        val key = s.key ?: return
        if (s.running) return
        if (key == PromptKey.SYSTEM) return
        val endpoint = s.endpointId ?: run { _state.update { it.copy(notice = "Add an endpoint for this role first") }; return }
        val draft = s.draft
        _state.update { it.copy(running = true, notice = null) }
        viewModelScope.launch {
            try {
                val input = when (s.inputKind) {
                    InputKind.FRAME -> s.frameId?.let { TestInput.Frame(it) } ?: TestInput.Text(s.inputText)
                    InputKind.TEXT -> TestInput.Text(s.inputText)
                    InputKind.QUESTION -> TestInput.Question(s.inputText)
                }
                val p = inputs.prepare(key, input)
                val result = ai.testBed.run(TestBedRequest(key, draft, endpoint, s.model, p.vars, p.images, p.inputIds))
                // Edited or discarded meanwhile: this run belongs to a draft that no longer exists, so it writes nothing.
                _state.update { if (it.draft == draft) { lastRun = draft to result; it.copy(result = result) } else it }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.i(TAG, "test run failed (${e.javaClass.simpleName})")
                _state.update { it.copy(notice = "Couldn't run the test") }
            } finally {
                _state.update { regated(it.copy(running = false)) } // one update: never "done" with stale gates
            }
        }
    }

    fun save() {
        val s = _state.value
        val key = s.key ?: return
        if (!s.canSave) return
        val draft = s.draft
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                // Save runs the checker itself on the exact draft it stores (it rejects $ref cycles, so it can't hang).
                val problems = compatibility(key, draft)
                if (problems.isNotEmpty()) {
                    _state.update { regated(it.copy(compatFor = draft, compatProblems = problems, notice = "Fix the schema before saving")) }
                    return@launch
                }
                val v = ai.prompts.save(key, draft, note = "", testedOk = key != PromptKey.SYSTEM)
                val active = ai.prompts.active(key)
                lastRun = null
                _state.update {
                    val saved = it.copy(activeVersion = active.version, activeDraft = stored(key, active.template, active.outputSchema),
                        notice = if (s.keepVersions) "Saved as v$v" else "Saved over v$v")
                    // Edits are off while saving; the guard still never replaces text that isn't what was saved.
                    if (it.draft != draft) saved else saved.copy(template = active.template, schema = active.outputSchema ?: DefaultSchemas.text(key).orEmpty(), result = null)
                }
                recheck(debounceMs = 0)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.i(TAG, "save failed (${e.javaClass.simpleName})")
                _state.update { it.copy(notice = "Couldn't save") }
            } finally {
                _state.update { regated(it.copy(saving = false)) }
            }
        }
    }

    /** What the screen shows for a stored version: a null schema opens as the default, so it isn't a change (preflight F20). */
    private fun stored(key: PromptKey, template: String, schema: String?) =
        PromptDraft(template, if (key == PromptKey.SYSTEM) null else schema?.takeIf { it.isNotBlank() } ?: DefaultSchemas.text(key))

    /**
     * One draft edit: ignored while saving (returns false). Clears the result panel. If the last check covered this
     * schema it still holds for the edited draft (the checker reads only the schema), so [PromptEditorUiState.compatFor] follows.
     */
    private fun edit(f: (PromptEditorUiState) -> PromptEditorUiState): Boolean {
        if (_state.value.saving) return false
        _state.update { cur ->
            if (cur.saving) return@update cur
            val n = f(cur).copy(notice = null, result = null, showWire = false)
            regated(if (n.compatFor?.outputSchema == n.draft.outputSchema) n.copy(compatFor = n.draft) else n)
        }
        return true
    }

    private fun regated(s: PromptEditorUiState): PromptEditorUiState = s.key?.let { k -> s.copy(gates = SaveGates.of(k, s.draft, s.compatFor?.let { it to s.compatProblems }, lastRun)) } ?: s
    /** Asks for a schema check; a burst of requests within [DEBOUNCE_MS] makes one check (see [checks]). */
    private fun recheck(debounceMs: Long = DEBOUNCE_MS) { checkRequests.value = CheckRequest(++checkSeq, debounceMs) }

    /** The check of the draft as it stands once the burst ends; a newer request cancels a check still running. */
    private suspend fun check() {
        val s = _state.value
        val key = s.key ?: return
        val draft = s.draft
        val problems = compatibility(key, draft)
        _state.update { regated(it.copy(compatFor = if (it.draft.outputSchema == draft.outputSchema) it.draft else draft, compatProblems = problems)) }
    }

    private companion object { const val TAG = "PromptEditor"; const val DEBOUNCE_MS = 300L }
}

@Composable
fun PromptEditorScreen(
    state: PromptEditorUiState,
    onBack: () -> Unit,
    onTemplate: (String) -> Unit,
    onSchema: (String) -> Unit,
    onResetSchema: () -> Unit,
    onInputKind: (InputKind) -> Unit,
    onInputText: (String) -> Unit,
    onFrame: (String) -> Unit,
    onEndpoint: (String) -> Unit,
    onModel: (String) -> Unit,
    onRun: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onToggleWire: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    SettingsPage(state.label.ifEmpty { "Prompt" }, onBack, tag = "prompt_editor", actions = { SecondaryAction("History", onOpenHistory, tag = "prompt_history") }) {
        if (state.notFound) { item { SettingsCard { NoticeText("This prompt doesn't exist") } }; return@SettingsPage }
        item {
            SettingsCard {
                Text("Template · active v${state.activeVersion}", style = MaterialTheme.typography.titleMedium)
                CodeBox(state.template, onTemplate.takeUnless { state.saving }, tag = "prompt_template", minLines = 8)
                NoticeText("Variables: " + TemplateVariables.KNOWN.joinToString(" ") { "{{$it}}" })
                if (state.unknownVariables.isNotEmpty()) NoticeText("Unknown: " + state.unknownVariables.joinToString(" ") { "{{$it}}" }, error = true, tag = "prompt_unknown")
                if (state.missingVariables.isNotEmpty()) NoticeText("Required: " + state.missingVariables.joinToString(" ") { "{{$it}}" }, error = true, tag = "prompt_missing")
            }
        }
        if (state.hasSchema) item {
            SettingsCard {
                Text("Output schema", style = MaterialTheme.typography.titleMedium)
                CodeBox(state.schema, onSchema.takeUnless { state.saving }, tag = "prompt_schema", minLines = 8, isError = state.compatProblems.isNotEmpty())
                state.compatProblems.forEach { NoticeText(it, error = true, tag = "prompt_compat") }
                SecondaryAction("Reset to default", onResetSchema, tag = "prompt_reset_schema", enabled = !state.saving)
            }
        }
        if (state.hasSchema) item {
            SettingsCard {
                Text("Test bed", style = MaterialTheme.typography.titleMedium)
                val kinds = InputKind.entries.filter { it != InputKind.FRAME || state.frames.isNotEmpty() }
                ChoiceRow("Input", kinds.map { it.label }, kinds.indexOf(state.inputKind), { onInputKind(kinds[it]) }, tag = "prompt_input_kind")
                if (state.inputKind == InputKind.FRAME) {
                    val ids = state.frames.map { it.id }
                    ChoiceRow("Frame", state.frames.map { it.label }, ids.indexOf(state.frameId), { onFrame(ids[it]) }, tag = "prompt_frame")
                } else {
                    TextFieldRow(if (state.inputKind == InputKind.QUESTION) "Question" else "Text", state.inputText, onInputText, tag = "prompt_input", singleLine = false)
                }
                val ids = state.endpoints.map { it.id }
                ChoiceRow("Endpoint", state.endpoints.map { it.name }, ids.indexOf(state.endpointId), { onEndpoint(ids[it]) }, tag = "prompt_endpoint")
                val models = state.endpoints.firstOrNull { it.id == state.endpointId }?.models.orEmpty()
                if (models.isNotEmpty()) ChoiceRow("Model", models, models.indexOf(state.model), { onModel(models[it]) }, tag = "prompt_model")
                PrimaryAction(if (state.running) "Running…" else "Run", onRun, tag = "prompt_run", enabled = !state.running && state.endpointId != null)
                state.runCompatLine?.let { NoticeText(it, error = !state.gates.compatible, tag = "prompt_run_compat") }
                state.result?.let { r ->
                    val failure = r.failure
                    if (failure != null) NoticeText(failure, error = true, tag = "prompt_failure") else {
                        NoticeText("${r.latencyMs} ms · ${r.promptTokens ?: "?"} in / ${r.completionTokens ?: "?"} out tokens", tag = "prompt_metrics")
                        CodeBox(r.raw, null, tag = "prompt_raw", minLines = 4)
                        r.evaluation?.problems()?.forEach { NoticeText(it, error = true, tag = "prompt_problem") }
                        val wire = r.wireSchema
                        if (wire != null) {
                            SecondaryAction(if (state.showWire) "Hide wire schema" else "Show wire schema", onToggleWire, tag = "prompt_wire_toggle")
                            if (state.showWire) CodeBox(wire, null, tag = "prompt_wire", minLines = 4)
                        }
                    }
                }
            }
        }
        item {
            SettingsCard {
                state.gateLines.forEach { NoticeText(it, error = it.startsWith("✕"), tag = "gate") }
                PrimaryAction(if (state.saving) "Saving…" else state.saveLabel, onSave, tag = "prompt_save", enabled = state.canSave)
                SecondaryAction("Discard changes", onDiscard, tag = "prompt_discard", enabled = state.dirty && !state.saving)
                state.notice?.let { NoticeText(it, tag = "prompt_notice") }
            }
        }
    }
}
