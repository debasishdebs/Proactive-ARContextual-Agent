package com.rokid.context.phone.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.NudgeRepository
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.status.BannerActions
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.phone.ui.banner.IndexState
import com.rokid.context.phone.ui.status.StatusUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(
    initial: Period,
    private val glasses: GlassesStatusSource,
    private val pipeline: PipelineControl,
    private val timeline: TimelineRepository,
    private val asks: AskRepository,
    private val nudges: NudgeRepository,
    clock: Flow<Long>,
    storagePct: Flow<Int?>,
    private val zone: () -> ZoneId,
    index: Flow<IndexState> = flowOf(IndexState.NONE),
) : ViewModel() {
    private val period = MutableStateFlow(initial)
    /** (local date, zone) at each clock tick; re-queries and regroups when either changes (midnight, or a zone change noticed on the next tick). */
    private val today = clock.map { ms -> zone().let { DayBucket.dayOf(ms, it) to it } }.distinctUntilChanged()

    private val content: Flow<ActivityUiState> = combine(period, today) { p, dz -> Triple(p, dz.first, dz.second) }.distinctUntilChanged().flatMapLatest { (p, d, z) ->
        val w = PeriodWindow.of(p, d)
        val r = w.millis(z)
        if (p == Period.TODAY) {
            combine(timeline.visibleMemoriesIn(r), timeline.framesIn(r), asks.timesIn(r), nudges.timesIn(r)) { mems, frames, a, n ->
                ActivityUiState(p, ActivityModel.header(w, d), tiles = Tiles(frames.size, mems.size, a.size, n.size), sections = Timeline.sections(mems, Timeline.framesFor(mems, frames, timeline::framesByIds), z, d), today = d)
            }
        } else {
            combine(timeline.frameTimesIn(r), timeline.visibleMemoryTimesIn(r), asks.timesIn(r), nudges.timesIn(r)) { f, m, a, n ->
                val fc = ActivityModel.countByDay(f, w, z)
                val mc = ActivityModel.countByDay(m, w, z)
                ActivityUiState(p, ActivityModel.header(w, d), tiles = Tiles(f.size, m.size, a.size, n.size), bars = ActivityModel.bars(w, fc), dayRows = ActivityModel.dayRows(w, fc, mc, d), today = d)
            }
        }
    }

    val state: StateFlow<ActivityUiState> = combine(content, StatusUi.flow(glasses, pipeline, clock, storagePct, index)) { c, s -> c.copy(status = s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(period = initial))

    fun selectPeriod(p: Period) { period.value = p }
    fun togglePause() = pipeline.toggle()
    fun onBannerAction(action: BannerAction) = BannerActions.apply(action, glasses, pipeline)
}
