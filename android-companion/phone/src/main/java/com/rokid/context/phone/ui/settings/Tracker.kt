package com.rokid.context.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.ui.tracker.SummarizerTrackerScreen
import com.rokid.context.ui.tracker.TrackerItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Live from Room (summary_trace Flow). Notes are shown verbatim: this is a Diagnostics screen (spec §6.3). */
class TrackerViewModel(timeline: TimelineRepository, zone: () -> ZoneId) : ViewModel() {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)

    val state: StateFlow<List<TrackerItem>> = timeline.recentTraces(40).map { rows ->
        rows.map { TrackerItem(it.summaryId, it.state, time.format(Instant.ofEpochMilli(it.updatedAt).atZone(zone())), it.note) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun TrackerScreen(items: List<TrackerItem>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface)) {
        ScreenHeader("Summarizer tracker", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SummarizerTrackerScreen.Render(items, onBack)
        }
    }
}
