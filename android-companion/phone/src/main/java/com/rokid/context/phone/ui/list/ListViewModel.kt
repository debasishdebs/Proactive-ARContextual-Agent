package com.rokid.context.phone.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.phone.ui.preview.ActivityData
import java.time.LocalDate
import java.time.ZoneId

/**
 * Holds the paged list of one Activity route (activity pagination A4). It is scoped to the navigation back-stack entry, so opening a preview and coming back keeps the loaded pages and the scroll position
 * (a plain `remember` lost both); the first visible row and the window top live in the [SavedStateHandle], so a process death restores the position with one read.
 */
class ListViewModel(
    kind: ListKind, ids: List<String>, scope: ListScope?, data: ActivityData, zone: () -> ZoneId, today: () -> LocalDate,
    saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    /** Set by the route on every composition: the navigation that a row's tap runs (a NavController is never held here). */
    @Volatile var openPreview: (String) -> Unit = {}
    val source = RecordListSource(kind, ids, data, zone, today, onPreview = { openPreview(it) }, scope = scope)
    init { source.startIn(viewModelScope, saved) }
}
