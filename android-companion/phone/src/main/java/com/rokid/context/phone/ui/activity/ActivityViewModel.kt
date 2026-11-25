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
    private val granularity: GranularityStore = InMemoryGranularity(),
    private val viewMode: ViewModeStore = InMemoryViewMode(),
    /** The read-only Scenes source (the Scenes card); null counts none. */
    private val scenes: com.rokid.context.memory.SceneRepository? = null,
) : ViewModel() {
    private val period = MutableStateFlow(initial)
    /** The custom date and time range (Range chip); null means the period. Kept for as long as the page lives, like the period. */
    private val range = MutableStateFlow<com.rokid.context.phone.ui.list.DateTimeRange?>(null)
    /** (local date, zone) at each clock tick; re-queries and regroups when either changes (midnight, or a zone change noticed on the next tick). */
    private val today = clock.map { ms -> zone().let { DayBucket.dayOf(ms, it) to it } }.distinctUntilChanged()

    private val content: Flow<ActivityUiState> = combine(period, range, today) { p, r, dz -> Triple(p, r, dz) }.distinctUntilChanged().flatMapLatest { (p, r, dz) ->
        sort.order.flatMapLatest { o -> if (r != null) rangeQuery(p, r, dz.first, dz.second, o) else query(p, dz.first, dz.second, o) }
    }

    /** A custom range: the counts and the marks of exactly that range, and its days as rows (and bars while it is short enough to read) like a Week or a Month. */
    private fun rangeQuery(p: Period, rg: com.rokid.context.phone.ui.list.DateTimeRange, d: java.time.LocalDate, z: ZoneId, o: ActivitySort): Flow<ActivityUiState> {
        val ms = com.rokid.context.phone.ui.list.RangeFilter.millis(rg, z)
        val first = rg.start.toLocalDate(); val last = rg.end.toLocalDate()
        val span = java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1
        val w = PeriodWindow(if (span <= 7) Period.WEEK else Period.MONTH, first, last.plusDays(1))
        return withScenes(ms, combine(timeline.framesIn(ms), timeline.visibleMemoriesIn(ms), asks.timesIn(ms), nudges.timesIn(ms), marks.marksIn(ms)) { frames, mems, a, n, k ->
            val fc = ActivityModel.countByDay(frames.map { it.ts }, w, z)
            val mc = ActivityModel.countByDay(mems.map { it.createdAt }, w, z)
            ActivityUiState(p, com.rokid.context.phone.ui.list.RangeFilter.label(rg), tiles = Tiles(frames.size, mems.size, a.size, n.size), bars = if (span <= MAX_BAR_DAYS) ActivityModel.bars(w, fc) else emptyList(),
                dayRows = ActivityModel.dayRows(w, fc, mc, d, o), today = d, sort = o, marks = MarkRows.rows(k, z, withDay = true), range = rg)
        })
    }

    private fun query(p: Period, d: java.time.LocalDate, z: ZoneId, o: ActivitySort): Flow<ActivityUiState> {
        val w = PeriodWindow.of(p, d)
        val r = w.millis(z)
        return withScenes(r, if (p == Period.TODAY) {
            combine(timeline.visibleMemoriesIn(r), timeline.framesIn(r), asks.timesIn(r), nudges.timesIn(r), marks.marksIn(r)) { mems0, frames, a, n, k ->
                val mems = Timeline.stillVisible(mems0, timeline::memoriesByIds) // never a forgotten memory's caption from the intermediate emission
                ActivityUiState(p, ActivityModel.header(w, d), tiles = Tiles(frames.size, mems.size, a.size, n.size), sections = Timeline.sections(mems, Timeline.framesFor(mems, frames, timeline::framesByIds), z, d, o), today = d, sort = o, marks = MarkRows.rows(k, z, withDay = false))
            }
        } else {
            combine(timeline.framesIn(r), timeline.visibleMemoriesIn(r), asks.timesIn(r), nudges.timesIn(r), marks.marksIn(r)) { frames, mems, a, n, k ->
                val fc = ActivityModel.countByDay(frames.map { it.ts }, w, z)
                val mc = ActivityModel.countByDay(mems.map { it.createdAt }, w, z)
                ActivityUiState(p, ActivityModel.header(w, d), tiles = Tiles(frames.size, mems.size, a.size, n.size), bars = ActivityModel.bars(w, fc), dayRows = ActivityModel.dayRows(w, fc, mc, d, o), today = d, sort = o, marks = MarkRows.rows(k, z, withDay = true))
            }
        })
    }

    /** Adds the number of Scenes of [r] to the tiles (live); nothing is read when no Scenes source is wired. */
    private fun withScenes(r: LongRange, base: Flow<ActivityUiState>): Flow<ActivityUiState> {
        val repo = scenes ?: return base
        return combine(base, repo.startsIn(r).map { it.size }.distinctUntilChanged()) { s, n -> s.copy(tiles = s.tiles.copy(scenes = n)) }
    }

    private companion object { const val MAX_BAR_DAYS = 62 }

    val state: StateFlow<ActivityUiState> = combine(content, StatusUi.flow(glasses, pipeline, clock, storagePct, index), granularity.level, viewMode.mode) { c, s, g, m -> c.copy(status = s, granularity = g, viewMode = m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(period = initial))

    /** A period chip: it also ends a custom range, so the chips are the way back. */
    fun selectPeriod(p: Period) { period.value = p; range.value = null }
    /** Shows the date and time range from [start] to [end] (to the minute, both ends included); false, and nothing changes, when [start] is after [end]. */
    fun selectRange(start: java.time.LocalDateTime, end: java.time.LocalDateTime): Boolean {
        val r = com.rokid.context.phone.ui.list.RangeFilter.of(start, end) ?: return false
        range.value = r; return true
    }
    fun clearRange() { range.value = null }
    fun selectSort(o: ActivitySort) = sort.set(o)
    fun selectGranularity(g: Granularity) = granularity.set(g)
    fun selectViewMode(m: ActivityViewMode) = viewMode.set(m)
    fun togglePause() = pipeline.toggle()
    fun onBannerAction(action: BannerAction) = BannerActions.apply(action, glasses, pipeline)
}
