package com.rokid.context.phone.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.phone.ui.list.paging.BreakdownModel
import com.rokid.context.phone.ui.list.paging.SelectionController
import com.rokid.context.phone.ui.list.paging.SelectionForgetPort
import com.rokid.context.phone.ui.list.paging.SelectionResolver
import com.rokid.context.phone.ui.list.paging.StateStore
import com.rokid.context.phone.ui.preview.ActivityData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Which of the two views a period's list shows. */
enum class ListViewMode { LIST, BREAKDOWN }

/**
 * Holds the paged list of one Activity route (activity pagination A4). It is scoped to the navigation back-stack entry, so opening a preview and coming back keeps the loaded pages and the scroll position
 * (a plain `remember` lost both); the first visible row, the window top, the view and the expanded days and hours live in the [SavedStateHandle], so a process death restores the position with one read.
 * The broken-down view (A5) exists for the Moments and the Scenes of a period only (user ruling proposed by the design: Moments first).
 */
class ListViewModel(
    kind: ListKind, ids: List<String>, scope: ListScope?, data: ActivityData, private val zone: () -> ZoneId, private val today: () -> LocalDate,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    /** Set by the route on every composition: the navigation that a row's tap runs (a NavController is never held here). */
    @Volatile var openPreview: (String) -> Unit = {}
    /** The date and time filter (saved, so a rotation or a process death keeps it). Only a list over a period has one. */
    private val _filter = MutableStateFlow(if (scope != null) RangeFilter.decode(saved.get<String>(KEY_FILTER)) else null)
    val filter: StateFlow<DateTimeRange?> get() = _filter
    val canFilter: Boolean = scope != null
    val source = RecordListSource(kind, ids, data, zone, today, onPreview = { openPreview(it) }, scope = scope)

    /** The broken-down view of a period's Moments or Scenes; null for every other list. */
    val breakdown: BreakdownModel? = if ((kind == ListKind.MOMENTS || kind == ListKind.SCENES) && scope != null) {
        BreakdownModel(kind, data, scope.millis(zone()), zone, today, source::summarize, object : StateStore {
            override fun get(key: String) = saved.get<String>(key)
            override fun set(key: String, value: String) { saved[key] = value }
        })
    } else null

    /**
     * Set by the route on every composition: the Forget behind multi-select (null while `DeveloperFlags.forgetV2` is OFF, the default). With none, the selection does not exist: no long-press, no menu, no checkboxes.
     * The selection itself is a set of refs kept in [selection] (saved), so paging, collapsing and view switches never change it.
     */
    @Volatile var forgetPort: SelectionForgetPort? = null
    val selection = SelectionController(kind, SelectionResolver(kind, data, zone), { forgetPort }, object : StateStore {
        override fun get(key: String) = saved.get<String>(key)
        override fun set(key: String, value: String) { saved[key] = value }
    }, viewModelScope, onForgotten = { source.retry() })

    private val _view = MutableStateFlow(if (breakdown != null && saved.get<String>(KEY_VIEW) == ListViewMode.BREAKDOWN.name) ListViewMode.BREAKDOWN else ListViewMode.LIST)
    val view: StateFlow<ListViewMode> get() = _view

    init {
        _filter.value?.let { r -> val ms = RangeFilter.millis(r, zone()); source.setFilter(ms); breakdown?.setFilter(ms) }
        source.startIn(viewModelScope, saved)
        if (_view.value == ListViewMode.BREAKDOWN) breakdown?.start(viewModelScope)
    }

    /** Shows only the items from [start] to [end] (both included, to the minute). False, and nothing changes, when [start] is after [end] or this list has no period. */
    fun applyFilter(start: java.time.LocalDateTime, end: java.time.LocalDateTime): Boolean {
        if (!canFilter) return false
        val r = RangeFilter.of(start, end) ?: return false
        set(r); return true
    }

    fun clearFilter() { if (canFilter) set(null) }

    private fun set(r: DateTimeRange?) {
        _filter.value = r; saved[KEY_FILTER] = r?.let(RangeFilter::encode) ?: ""
        val ms = r?.let { RangeFilter.millis(it, zone()) }
        source.setFilter(ms); breakdown?.setFilter(ms)
    }

    fun setView(mode: ListViewMode) {
        if (breakdown == null) return
        _view.value = mode; saved[KEY_VIEW] = mode.name
        if (mode == ListViewMode.BREAKDOWN) breakdown.start(viewModelScope)
    }
    fun toggleDay(d: LocalDate) { breakdown?.let { viewModelScope.launch { it.toggleDay(d) } } }
    fun toggleHour(d: LocalDate, startMs: Long) { breakdown?.let { viewModelScope.launch { it.toggleHour(d, startMs) } } }
    fun nextPage(d: LocalDate, startMs: Long) { breakdown?.let { viewModelScope.launch { it.nextPage(d, startMs) } } }
    fun previousPage(d: LocalDate, startMs: Long) { breakdown?.let { viewModelScope.launch { it.previousPage(d, startMs) } } }

    companion object { private const val KEY_VIEW = "list.view"; internal const val KEY_FILTER = "list.filter" }
}
