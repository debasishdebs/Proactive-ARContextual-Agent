package com.rokid.context.phone.ui.frames

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.phone.ui.activity.ActivityModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ViewerFrame(val id: String, val path: String, val time: String)

data class FrameViewerUiState(
    val loading: Boolean = true,
    val found: Boolean = false,
    val title: String = "",
    val frames: List<ViewerFrame> = emptyList(),
    val startIndex: Int = 0,
)

/** `frames/{date}?start={frameId}` — the day's frames oldest first; unknown start → first frame; bad date → not found. */
class FrameViewerViewModel(dateArg: String?, startId: String?, timeline: TimelineRepository, zone: () -> ZoneId, today: () -> LocalDate) : ViewModel() {
    private val date: LocalDate? = dateArg?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    val state: StateFlow<FrameViewerUiState> = (
        if (date == null) {
            flowOf(FrameViewerUiState(loading = false, found = false))
        } else {
            val z = zone()
            // Guarded like the Day screen: an extreme date (e.g. +999999999-12-31) has no bounds → not found, never a crash.
            val d: LocalDate = date
            val r = runCatching { DayBucket.bounds(d, z) }.getOrNull()
            if (r == null) flowOf(FrameViewerUiState(loading = false, found = false)) else timeline.framesIn(r).map { frames ->
                FrameViewerUiState(
                    loading = false, found = true, title = ActivityModel.dayLabel(d, today()),
                    frames = frames.map { ViewerFrame(it.id, it.path, time.format(Instant.ofEpochMilli(it.ts).atZone(z))) },
                    startIndex = frames.indexOfFirst { it.id == startId }.coerceAtLeast(0),
                )
            }
        }
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FrameViewerUiState())
}
