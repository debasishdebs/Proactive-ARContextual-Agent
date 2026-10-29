package com.rokid.context.phone.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.MarkRepository
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
    private val marks: MarkRepository,
    clock: Flow<Long>,
    storagePct: Flow<Int?>,
    private val zone: () -> ZoneId,
    index: Flow<IndexState> = flowOf(IndexState.NONE),
    private val sort: ActivitySortStore = InMemoryActivitySort(),
) : ViewModel() {
    private val period = MutableStateFlow(initial)
    /** (local date, zone) at each clock tick; re-queries and regroups when either changes (midnight, or a zone change noticed on the next tick). */
    private val today = clock.map { ms -> zone().let { DayBucket.dayOf(ms, it) to it } }.distinctUntilChanged()

    private val content: Flow<ActivityUiState> = combine(period, today) { p, dz -> Triple(p, dz.first, dz.second) }.distinctUntilChanged().flatMapLatest { (p, d, z) ->
        sort.order.flatMapLatest { o -> query(p, d, z, o) }
    }

    private fun query(p: Period, d: java.time.LocalDate, z: ZoneId, o: ActivitySort): Flow<ActivityUiState> {
        val w = PeriodWindow.of(p, d)
        val r = w.millis(z)
        // Ids behind the answer and nudge cards come from the recent rows (the repositories read only times by range); a card opens the list of its kind over these.
        val answerIds = asks.recent(RECENT).map { rows -> rows.filter { it.ts in r }.map { it.id } }
        val nudgeIds = nudges.recent(RECENT).map { rows -> rows.filter { it.ts in r }.map { it.id } }
        val base = if (p == Period.TODAY) {
            combine(timeline.visibleMemoriesIn(r), timeline.framesIn(r), asks.timesIn(r), nudges.timesIn(r), marks.marksIn(r)) { mems, frames, a, n, k ->
                ActivityUiState(p, ActivityModel.header(w, d), tiles = Tiles(frames.size, mems.size, a.size, n.size), tileIds = TileIds(frames = frames.map { it.id }, memories = mems.map { it.id }), sections = Timeline.sections(mems, Timeline.framesFor(mems, frames, timeline::framesByIds), z, d, o), today = d, sort = o, marks = MarkRows.rows(k, z, withDay = false))
            }
        } else {
            combine(timeline.framesIn(r), timeline.visibleMemoriesIn(r), asks.timesIn(r), nudges.timesIn(r), marks.marksIn(r)) { frames, mems, a, n, k ->
                val fc = ActivityModel.countByDay(frames.map { it.ts }, w, z)
                val mc = ActivityModel.countByDay(mems.map { it.createdAt }, w, z)
                ActivityUiState(p, ActivityModel.header(w, d), tiles = Tiles(frames.size, mems.size, a.size, n.size), tileIds = TileIds(frames = frames.map { it.id }, memories = mems.map { it.id }), bars = ActivityModel.bars(w, fc), dayRows = ActivityModel.dayRows(w, fc, mc, d, o), today = d, sort = o, marks = MarkRows.rows(k, z, withDay = true))
            }
        }
        return combine(base, answerIds, nudgeIds) { st, a, n -> st.copy(tileIds = st.tileIds.copy(answers = a, nudges = n)) }
    }

    val state: StateFlow<ActivityUiState> = combine(content, StatusUi.flow(glasses, pipeline, clock, storagePct, index)) { c, s -> c.copy(status = s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(period = initial))

    fun selectPeriod(p: Period) { period.value = p }
    fun selectSort(o: ActivitySort) = sort.set(o)
    fun togglePause() = pipeline.toggle()
    fun onBannerAction(action: BannerAction) = BannerActions.apply(action, glasses, pipeline)

    private companion object { const val RECENT = 1000 }
}
