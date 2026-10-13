package com.rokid.context.phone.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.orchestration.AskSources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.rokid.context.memory.db.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AskDetailViewModel(
    private val askId: String,
    asks: AskRepository,
    proposals: ProposalRepository,
    timeline: TimelineRepository,
    private val rater: suspend (String, Boolean) -> Boolean,
    zone: () -> ZoneId,
) : ViewModel() {
    val state: StateFlow<AskDetailUiState> = asks.observe(askId).flatMapLatest { ask ->
        if (ask == null) {
            flowOf(AskDetailModel.NOT_FOUND)
        } else {
            // P6 T6 carry: each cited memory is observed, so a Forget (row gone or INVALIDATED) drops its card live.
            val ids = if (ask.redacted) emptyList() else AskSources.parse(ask.sourcesJson).distinct()
            val memories: Flow<List<MemoryEntity>> =
                if (ids.isEmpty()) flowOf(emptyList()) else combine(ids.map { timeline.memory(it) }) { rows -> rows.filterNotNull() }
            combine(memories, proposals.forOrigin(ask.id)) { mems, props ->
                val frames = timeline.framesByIds(mems.mapNotNull { it.frameId })
                AskDetailModel.build(ask, mems, frames, props, zone())
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskDetailUiState())

    fun rate(up: Boolean) {
        viewModelScope.launch { rater(askId, up) }
    }
}
