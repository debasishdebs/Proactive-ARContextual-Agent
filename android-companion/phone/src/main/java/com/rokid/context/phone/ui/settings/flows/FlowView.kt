package com.rokid.context.phone.ui.settings.flows

import com.rokid.context.core.log.PcLog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.flow.AgentFlow
import com.rokid.context.ai.flow.AgentFlows
import com.rokid.context.ai.flow.Facet
import com.rokid.context.ai.flow.FlowLayout
import com.rokid.context.ai.flow.FlowLayouts
import com.rokid.context.ai.flow.FlowNode
import com.rokid.context.ai.flow.NodeKind
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.retrieval.FusionStrategy
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.prompt.TemplateVariables
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** One line on a card, read live: "Prompt v3", "gpt-4o \u00b7 t 0.2", "Not set", "Hybrid \u00b7 RRF". */
data class FacetLine(val facet: Facet, val text: String)
data class NodeUi(val node: FlowNode, val lines: List<FacetLine>)
data class FlowSectionUi(val flow: AgentFlow, val layout: FlowLayout, val nodes: Map<String, NodeUi>)
/** The active version of a prompt and the variables its template uses (the step sheet's Prompt tab). */
data class PromptUi(val version: Int, val variables: List<String>)
data class FlowViewUiState(
    val system: FacetLine?,
    val sections: List<FlowSectionUi>,
    val empty: Boolean,
    val prompts: Map<PromptKey, PromptUi> = emptyMap(),
    /** False until bindings, prompts and retrieval settings have been read once. */
    val loaded: Boolean = false,
)

/** The System preamble is not a flow node; its chip opens the sheet as this Prompt-only step. */
val SYSTEM_STEP = FlowNode("system", PromptKey.SYSTEM.label, NodeKind.STEP, listOf(Facet.Prompt(PromptKey.SYSTEM)))

object FacetText {
    const val CUSTOM = "Custom"
    const val NOT_SET = "Not set"
    fun prompt(version: Int?) = if (version == null) "Prompt missing \u2014 Reset to default" else "Prompt v$version"
    fun model(b: RoleBinding?) = b?.model?.takeIf { it.isNotBlank() }?.let { m -> m + (b.temperature?.let { " \u00b7 t $it" } ?: "") } ?: NOT_SET
    fun mode(m: RetrievalMode) = when (m) { RetrievalMode.LEXICAL -> "Lexical"; RetrievalMode.SEMANTIC -> "Semantic"; RetrievalMode.HYBRID -> "Hybrid" }
    fun fusion(f: FusionStrategy) = when (f) { is FusionStrategy.WeightedScore -> "Weighted"; is FusionStrategy.Rrf -> "RRF"; FusionStrategy.CombMnz -> "CombMNZ" }
    /** Fusion only applies to Hybrid search (Settings > Retrieval hides it otherwise), so only Hybrid names it. */
    fun retrieval(s: RetrievalSettings) = if (s.mode == RetrievalMode.HYBRID) "${mode(s.mode)} \u00b7 ${fusion(s.fusion)}" else mode(s.mode)
    fun system(version: Int?) = if (version == null) "${PromptKey.SYSTEM.label} \u00b7 missing" else "${PromptKey.SYSTEM.label} \u00b7 v$version"
}

/**
 * Settings > AI > Agent flows > view. Unknown ids are dropped. Every facet line is recomputed from the bindings,
 * active prompts and retrieval settings flows, so an edit in the step sheet shows on every card at once.
 */
class FlowViewModel(
    ids: List<String>,
    ai: AiGraph,
    retrieval: RetrievalSettingsStore,
    private val registry: (String) -> AgentFlow? = AgentFlows::byId,
    seed: suspend () -> Unit = { ai.prompts.ensureSeeded() },
) : ViewModel() {
    private val shown = MutableStateFlow(ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().mapNotNull(registry))
    private val layouts = ConcurrentHashMap<String, FlowLayout>()

    val state: StateFlow<FlowViewUiState> = flow {
        try {
            seed()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            PcLog.i(TAG, "seeding prompts failed (${e.javaClass.simpleName})") // a missing row reads "Prompt missing"
        }
        emitAll(combine(shown, ai.bindings.observeAll(), ai.prompts.observeAllActive(), retrieval.settings) { flows, bindings, active, settings ->
            val prompts = active.mapNotNull { v -> PromptKey.parse(v.promptId)?.let { it to PromptUi(v.version, TemplateVariables.used(v.template).toList()) } }.toMap()
            fun line(f: Facet) = FacetLine(f, when (f) {
                is Facet.Prompt -> FacetText.prompt(prompts[f.key]?.version)
                is Facet.Model -> FacetText.model(bindings.firstOrNull { it.role == f.role })
                Facet.Retrieval -> FacetText.retrieval(settings)
                is Facet.Custom -> FacetText.CUSTOM
            })
            FlowViewUiState(
                system = FacetLine(Facet.Prompt(PromptKey.SYSTEM), FacetText.system(prompts[PromptKey.SYSTEM]?.version)),
                sections = flows.map { f ->
                    FlowSectionUi(f, layouts.getOrPut(f.id) { FlowLayouts.layout(f) }, f.nodes.associate { n -> n.id to NodeUi(n, n.facets.map(::line)) })
                },
                empty = flows.isEmpty(),
                prompts = prompts,
                loaded = true,
            )
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlowViewUiState(null, emptyList(), empty = shown.value.isEmpty()))

    /** A LINK tap: adds the flow below the others; a flow already shown is not added twice. */
    fun addFlow(id: String) {
        val f = registry(id) ?: return
        shown.update { if (it.any { s -> s.id == f.id }) it else it + f }
    }

    private companion object { const val TAG = "FlowView" }
}

/** Items before the first section: the header and the System chip. */
private const val LEADING_ITEMS = 2

@Composable
fun FlowViewScreen(state: FlowViewUiState, onBack: () -> Unit, onNode: (FlowNode) -> Unit, onLink: (String) -> Unit) {
    val list = rememberLazyListState()
    var scrollTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scrollTo, state.sections) {
        val i = state.sections.indexOfFirst { it.flow.id == scrollTo }
        if (i >= 0) { list.animateScrollToItem(i + LEADING_ITEMS); scrollTo = null }
    }
    LazyColumn(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).testTag("flow_view"), state = list) {
        item { ScreenHeader("Agent flows", onBack) }
        if (state.empty) {
            item { Text("No flows selected", style = MaterialTheme.typography.bodyLarge, color = PcColors.InkSoft, modifier = Modifier.padding(16.dp).testTag("flow_empty")) }
            return@LazyColumn
        }
        item(key = "system") {
            state.system?.let { sys ->
                SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("flow_system"), onClick = { onNode(SYSTEM_STEP) }) {
                    Text(sys.text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        items(state.sections, key = { it.flow.id }) { s -> FlowSection(s, onNode, onLink = { id -> scrollTo = id; onLink(id) }) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

