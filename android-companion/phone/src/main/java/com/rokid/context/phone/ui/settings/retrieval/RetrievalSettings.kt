package com.rokid.context.phone.ui.settings.retrieval

import com.rokid.context.core.log.PcLog
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Role
import com.rokid.context.core.ui.components.SegmentedSwitch
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.db.SpaceState
import com.rokid.context.memory.retrieval.FusionStrategy
import com.rokid.context.memory.retrieval.MemoryRetriever
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import com.rokid.context.phone.ui.settings.TextFieldRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

data class RetrievalHitUi(val text: String, val scores: String)

data class RetrievalUiState(
    val mode: RetrievalMode = RetrievalMode.HYBRID,
    /** 0 = WeightedScore, 1 = RRF, 2 = CombMNZ. */
    val fusionIndex: Int = 0,
    val weight: Float = 0.6f,
    val rrfK: String = "60",
    val topK: Int = 8,
    val recency: Boolean = true,
    val halfLifeDays: Float = 3f,
    val minConfidence: Float = 0f,
    val embedSummary: String = "",
    val indexSummary: String = "",
    val query: String = "",
    val searching: Boolean = false,
    val results: List<RetrievalHitUi> = emptyList(),
    val fallbackNotice: String? = null,
    /** The RRF k field's validation; only [RetrievalViewModel.setRrfK] sets or clears it. */
    val rrfError: String? = null,
    /** Try it's status ("No matches", "Search failed"); never touches [rrfError]. */
    val tryError: String? = null,
)

/** spec §4.6 Settings → Retrieval. Every control writes [RetrievalSettingsStore]; Try it runs the real retriever. */
class RetrievalViewModel(
    private val store: RetrievalSettingsStore,
    private val retriever: MemoryRetriever,
    private val spaces: EmbeddingSpaces,
    ai: AiGraph,
    private val rebuildAction: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** Try it (retrieval, ranking and formatting) and Rebuild run here, never on Main. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(fromSettings(RetrievalUiState(), store.settings.value))
    val state: StateFlow<RetrievalUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { store.settings.collect { s -> _state.update { fromSettings(it, s) } } }
        viewModelScope.launch {
            combine(ai.bindings.observeAll(), ai.endpoints.observeAll()) { bindings, endpoints ->
                bindings.firstOrNull { it.role == Role.EMBED }?.let { b -> "${endpoints.firstOrNull { it.id == b.endpointId }?.name ?: "Missing endpoint"} · ${b.model}" }
                    ?: "Not set — semantic search is off"
            }.collect { summary -> _state.update { it.copy(embedSummary = summary) } }
        }
        viewModelScope.launch { spaces.health().collect { refreshStats() } }
    }

    private fun fromSettings(u: RetrievalUiState, s: RetrievalSettings) = u.copy(
        mode = s.mode,
        fusionIndex = when (s.fusion) { is FusionStrategy.WeightedScore -> 0; is FusionStrategy.Rrf -> 1; FusionStrategy.CombMnz -> 2 },
        weight = store.weightedConfig().semanticWeight.toFloat(), rrfK = if (u.rrfError == null) store.rrfConfig().k.toString() else u.rrfK,
        topK = s.topK, recency = s.recencyBoost, halfLifeDays = s.halfLifeDays.toFloat(), minConfidence = s.minConfidence.toFloat(),
    )

    fun setMode(m: RetrievalMode) = store.update { it.copy(mode = m) }
    fun setFusion(i: Int) = store.update { it.copy(fusion = when (i) { 1 -> store.rrfConfig(); 2 -> FusionStrategy.CombMnz; else -> store.weightedConfig() }) }
    fun setWeight(w: Float) = store.update { it.copy(fusion = FusionStrategy.WeightedScore(w.toDouble())) }
    fun setRrfK(text: String) {
        val k = text.trim().toIntOrNull()
        if (k == null || k !in 1..200) { _state.update { it.copy(rrfK = text, rrfError = "k is a whole number 1–200") }; return }
        _state.update { it.copy(rrfK = text, rrfError = null) }
        store.update { it.copy(fusion = FusionStrategy.Rrf(k)) }
    }
    fun setTopK(k: Int) = store.update { it.copy(topK = k) }
    fun setRecency(on: Boolean) = store.update { it.copy(recencyBoost = on) }
    fun setHalfLife(days: Float) = store.update { it.copy(halfLifeDays = days.toDouble()) }
    fun setMinConfidence(c: Float) = store.update { it.copy(minConfidence = c.toDouble()) }
    fun onQuery(q: String) = _state.update { it.copy(query = q.take(300)) }
    /** The Rebuild request commits prefs and enqueues work: on [work], never Main. */
    fun rebuild() { viewModelScope.launch(work) { rebuildAction(); refreshStats() } }

    fun tryIt() {
        val q = _state.value.query.trim()
        if (q.isEmpty() || _state.value.searching) return
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            try {
                val settings = store.settings.value
                // Only the user's own memory text and our scores reach the screen; a semantic failure is the fixed fallback notice.
                val (hits, semanticUnavailable) = withContext(work) {
                    val r = retriever.retrieve(q, settings, now())
                    r.hits.map { h ->
                        RetrievalHitUi(h.memory.text, listOfNotNull(
                            "score ${f(h.score)}", h.lexical?.let { v -> "lexical ${f(v)}" }, h.semantic?.let { v -> "semantic ${f(v)}" },
                            "confidence ${f(h.memory.confidence)}",
                        ).joinToString(" · "))
                    } to (r.semanticOff ?: com.rokid.context.memory.retrieval.SemanticOff.QUERY_FAILED).takeIf { r.semanticUnavailable }
                }
                _state.update {
                    it.copy(
                        results = hits,
                        fallbackNotice = semanticUnavailable?.let { off -> "Semantic search is off: ${off.text} — showing lexical results" },
                        tryError = if (hits.isEmpty()) "No matches" else null,
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                PcLog.i(TAG, "try it failed (${e.javaClass.simpleName})") // the type only: a message could carry provider text
                _state.update { it.copy(results = emptyList(), fallbackNotice = null, tryError = "Search failed") }
            } finally {
                _state.update { it.copy(searching = false) }
            }
            refreshStats()
        }
    }

    private suspend fun refreshStats() {
        val current = spaces.current(); val building = spaces.building()
        val text = buildList {
            current?.let { c ->
                val cov = spaces.coverage(c.id)
                add(if (c.state == SpaceState.STALE) "Index is out of date (${cov.have} / ${cov.eligible} memories)" else "${cov.have} / ${cov.eligible} memories indexed · ${c.dim} dimensions")
            }
            building?.let { b -> val cov = spaces.coverage(b.id); add("Rebuilding: ${cov.have} / ${cov.eligible}") }
        }.ifEmpty { listOf("No index yet") }.joinToString("\n")
        _state.update { it.copy(indexSummary = text) }
    }

    private fun f(d: Double) = String.format(Locale.US, "%.2f", d)
    private companion object { const val TAG = "Retrieval" }
}

@Composable
fun RetrievalSettingsScreen(
    state: RetrievalUiState,
    onBack: () -> Unit,
    onMode: (RetrievalMode) -> Unit,
    onFusion: (Int) -> Unit,
    onWeight: (Float) -> Unit,
    onRrfK: (String) -> Unit,
    onTopK: (Int) -> Unit,
    onRecency: (Boolean) -> Unit,
    onHalfLife: (Float) -> Unit,
    onMinConfidence: (Float) -> Unit,
    onQuery: (String) -> Unit,
    onTryIt: () -> Unit,
    onRebuild: () -> Unit,
    onOpenRoles: () -> Unit,
) {
    SettingsPage("Retrieval", onBack, tag = "retrieval_settings") {
        item {
            RetrievalControls(state, onMode, onFusion, onWeight, onRrfK, onTopK, onRecency, onHalfLife, onMinConfidence)
        }
        item {
            SettingsCard {
                Text("Embeddings", style = MaterialTheme.typography.titleMedium)
                NoticeText(state.embedSummary, tag = "embed_summary")
                SecondaryAction("Choose embedding model", onOpenRoles, tag = "open_roles")
                NoticeText(state.indexSummary, tag = "index_summary")
                SecondaryAction("Rebuild index", onRebuild, tag = "index_rebuild")
            }
        }
        item {
            SettingsCard {
                Text("Try it", style = MaterialTheme.typography.titleMedium)
                TextFieldRow("Search your memories", state.query, onQuery, tag = "try_query")
                PrimaryAction(if (state.searching) "Searching…" else "Try it", onTryIt, tag = "try_it", enabled = !state.searching)
                state.fallbackNotice?.let { NoticeText(it, tag = "try_fallback") }
                state.tryError?.let { NoticeText(it, error = true, tag = "try_error") }
                state.results.forEachIndexed { i, r ->
                    Text("${i + 1}. ${r.text}", style = MaterialTheme.typography.bodyMedium)
                    NoticeText(r.scores, tag = "try_scores_$i")
                }
            }
        }
    }
}

/** Search mode, fusion and tuning: the controls Settings > Retrieval and the flow step sheet share. */
@Composable
fun RetrievalControls(
    state: RetrievalUiState,
    onMode: (RetrievalMode) -> Unit,
    onFusion: (Int) -> Unit,
    onWeight: (Float) -> Unit,
    onRrfK: (String) -> Unit,
    onTopK: (Int) -> Unit,
    onRecency: (Boolean) -> Unit,
    onHalfLife: (Float) -> Unit,
    onMinConfidence: (Float) -> Unit,
) {
    Column {
        SettingsCard {
            Text("Search mode", style = MaterialTheme.typography.titleMedium)
            SegmentedSwitch(listOf("Lexical", "Semantic", "Hybrid"), state.mode.ordinal, { onMode(RetrievalMode.entries[it]) })
            if (state.mode == RetrievalMode.HYBRID) {
                ChoiceRow("Fusion", listOf("Weighted score", "Reciprocal rank (RRF)", "CombMNZ"), state.fusionIndex, onFusion, tag = "fusion")
                when (state.fusionIndex) {
                    0 -> {
                        Text("Semantic weight ${(state.weight * 100).toInt()} % · lexical ${100 - (state.weight * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
                        Slider(value = state.weight, onValueChange = onWeight, valueRange = 0f..1f, modifier = Modifier.testTag("fusion_weight"))
                    }
                    1 -> TextFieldRow("k", state.rrfK, onRrfK, tag = "fusion_rrf_k", number = true, supporting = state.rrfError ?: "Higher k flattens the rank bonus (default 60)", isError = state.rrfError != null)
                    else -> NoticeText("Sum of normalised scores × number of searches that found the memory.")
                }
            }
        }
        SettingsCard {
            Text("Tuning", style = MaterialTheme.typography.titleMedium)
            Text("Results: ${state.topK}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = state.topK.toFloat(), onValueChange = { onTopK(it.toInt()) }, valueRange = 1f..20f, steps = 18, modifier = Modifier.testTag("top_k"))
            SwitchRow("Prefer recent memories", "Half-life ${String.format(Locale.US, "%.1f", state.halfLifeDays)} days", state.recency, onRecency, tag = "recency")
            if (state.recency) Slider(value = state.halfLifeDays, onValueChange = onHalfLife, valueRange = 0.25f..30f, modifier = Modifier.testTag("half_life"))
            Text("Minimum confidence ${String.format(Locale.US, "%.2f", state.minConfidence)}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = state.minConfidence, onValueChange = onMinConfidence, valueRange = 0f..1f, modifier = Modifier.testTag("min_confidence"))
        }
    }
}
