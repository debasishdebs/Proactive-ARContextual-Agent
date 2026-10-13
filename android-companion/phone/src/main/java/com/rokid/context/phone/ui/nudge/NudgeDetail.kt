package com.rokid.context.phone.ui.nudge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameThumb
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.AskSources
import com.rokid.context.phone.ui.actions.ProposalCard
import com.rokid.context.phone.ui.ask.SourceCard
import com.rokid.context.phone.ui.home.REDACTED_NUDGE
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NudgeDetailUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val id: String = "",
    val text: String = "",
    val why: String = "",
    val timeLabel: String = "",
    val redacted: Boolean = false,
    val sources: List<SourceCard> = emptyList(),
) {
    companion object { val GONE = NudgeDetailUiState(loading = false, found = false) }
}

object NudgeDetailModel {
    private val TIME = DateTimeFormatter.ofPattern("EEE d MMM \u00b7 HH:mm", Locale.ENGLISH)

    /** Cited memories that are INVALIDATED or missing are hidden; a redacted nudge shows none of its text or sources. */
    fun build(n: NudgeEntity, memories: List<MemoryEntity>, frames: List<FrameEntity>, zone: ZoneId): NudgeDetailUiState {
        val byId = memories.associateBy { it.id }
        val paths = frames.associate { it.id to it.path }
        val cards = if (n.redacted) emptyList() else AskSources.parse(n.sourceIdsJson).mapNotNull { id ->
            byId[id]?.takeIf { it.reviewState != ReviewState.INVALIDATED }?.let { m -> SourceCard(m.id, m.text, m.confidence, m.frameId?.let(paths::get)) }
        }
        return NudgeDetailUiState(
            loading = false, found = true, id = n.id, text = if (n.redacted) "" else n.text, why = if (n.redacted) "" else n.why,
            timeLabel = TIME.format(Instant.ofEpochMilli(n.ts).atZone(zone)), redacted = n.redacted, sources = cards,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NudgeDetailViewModel(nudge: Flow<NudgeEntity?>, timeline: TimelineRepository, zone: () -> ZoneId) : ViewModel() {
    val state: StateFlow<NudgeDetailUiState> = nudge.map { n ->
        if (n == null) NudgeDetailUiState.GONE else {
            val memories = if (n.redacted) emptyList() else timeline.memoriesByIds(AskSources.parse(n.sourceIdsJson))
            NudgeDetailModel.build(n, memories, timeline.framesByIds(memories.mapNotNull { it.frameId }), zone())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NudgeDetailUiState())
}

/** Nudge detail (`nudge/{id}`, spec §2.4 "nudge detail"). Stateless. */
@Composable
fun NudgeDetailScreen(state: NudgeDetailUiState, onBack: () -> Unit, onOpenMemory: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface).verticalScroll(rememberScrollState())) {
        ScreenHeader("Heads up", onBack)
        if (state.loading) return@Column
        if (!state.found) {
            Text("This nudge is gone", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp))
            return@Column
        }
        Text(state.timeLabel, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp))
        if (state.redacted) {
            Text(REDACTED_NUDGE, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp))
            return@Column
        }
        Text(state.text, style = MaterialTheme.typography.titleLarge, color = PcColors.Ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (state.why.isNotBlank()) {
            SectionLabel("Why")
            Text(state.why, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (state.sources.isNotEmpty()) {
            SectionLabel("Based on")
            state.sources.forEach { s ->
                SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onClick = { onOpenMemory(s.memoryId) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FrameThumb(s.framePath, size = 52.dp)
                        Text(s.text, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        ConfidenceChip(s.confidence)
                    }
                }
            }
        }
        ProposalCard(state.id, Modifier.padding(16.dp))
        Spacer(Modifier.height(24.dp))
    }
}

/** Nudge detail's wiring (Phase 7 C4). */
@Composable
fun NudgeDetailRoute(id: String, deps: PhoneDeps, nav: NavController) {
    // Never pop the root (same rule as Destinations.kt).
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val vm = viewModel(key = "nudge_$id") { NudgeDetailViewModel(deps.memory.db.nudgeGateDao().observe(id), deps.memory.timeline, deps.zone) }
    val state by vm.state.collectAsStateWithLifecycle()
    NudgeDetailScreen(state, onBack = back, onOpenMemory = { nav.navigate(Routes.memory(it)) })
}
