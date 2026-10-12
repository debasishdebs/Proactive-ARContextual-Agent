package com.rokid.context.phone.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.orchestration.AskSources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
            proposals.forOrigin(ask.id).map { props ->
                val memories = timeline.memoriesByIds(if (ask.redacted) emptyList() else AskSources.parse(ask.sourcesJson))
                val frames = timeline.framesByIds(memories.mapNotNull { it.frameId })
                AskDetailModel.build(ask, memories, frames, props, zone())
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskDetailUiState())

    fun rate(up: Boolean) {
        viewModelScope.launch { rater(askId, up) }
    }
}
