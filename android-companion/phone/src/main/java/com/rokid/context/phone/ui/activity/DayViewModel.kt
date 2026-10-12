package com.rokid.context.phone.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.TimelineRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

data class DayUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val date: LocalDate? = null,
    val title: String = "",
    val sections: List<TimelineSection> = emptyList(),
    val frameCount: Int = 0,
)

/** `day/{date}` (ISO yyyy-MM-dd). A malformed argument is "not found", never a crash. */
class DayViewModel(dateArg: String?, timeline: TimelineRepository, zone: () -> ZoneId, today: () -> LocalDate) : ViewModel() {
    private val date: LocalDate? = dateArg?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val state: StateFlow<DayUiState> = (
        if (date == null) {
            flowOf(DayUiState(loading = false, found = false))
        } else {
            val z = zone()
            val r = DayBucket.bounds(date, z)
            combine(timeline.visibleMemoriesIn(r), timeline.framesIn(r)) { m, f ->
                DayUiState(false, true, date, ActivityModel.dayLabel(date, today()), Timeline.sections(m, f, z), f.size)
            }
        }
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState())
}
