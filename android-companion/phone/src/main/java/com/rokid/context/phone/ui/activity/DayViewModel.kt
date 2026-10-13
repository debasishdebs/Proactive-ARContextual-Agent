package com.rokid.context.phone.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.TimelineRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

/**
 * `day/{date}` (ISO yyyy-MM-dd). A malformed or out-of-range argument is "not found", never a crash.
 * [clock] ticks let a mid-session zone change regroup the day; the default never re-reads the zone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(
    dateArg: String?, timeline: TimelineRepository, zone: () -> ZoneId, today: () -> LocalDate, clock: Flow<Long> = flowOf(0L),
) : ViewModel() {
    private val date: LocalDate? = dateArg?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private val notFound = DayUiState(loading = false, found = false)

    val state: StateFlow<DayUiState> = (
        date.let { d ->
        if (d == null) {
            flowOf(notFound)
        } else {
            clock.map { zone() }.distinctUntilChanged().flatMapLatest { z ->
                val r = runCatching { DayBucket.bounds(d, z) }.getOrNull()
                if (r == null) {
                    flowOf(notFound)
                } else {
                    combine(timeline.visibleMemoriesIn(r), timeline.framesIn(r)) { m, f ->
                        DayUiState(false, true, d, ActivityModel.dayLabel(d, today()), Timeline.sections(m, Timeline.framesFor(m, f, timeline::framesByIds), z, d), f.size)
                    }
                }
            }
        }
        }
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState())
}
