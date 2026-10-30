package com.rokid.context.phone.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.activity.ActivityModel.plural
import com.rokid.context.phone.ui.preview.PreviewRef
import com.rokid.context.phone.ui.preview.RefKind

@Composable
/** The day's rows; a tap goes through previewMoment (the caller's [onPreview]), memory or photo alike. */
fun DayScreen(state: DayUiState, onBack: () -> Unit, onPreview: (List<String>) -> Unit, scrollToMs: Long? = null) {
    val list = rememberLazyListState()
    // "Show that time": once, when the day has loaded (the day is one query, never paged, so there is nothing to load more of).
    var scrolled by remember(scrollToMs) { mutableStateOf(false) }
    LaunchedEffect(state.found, state.sections, scrollToMs) {
        if (scrolled || scrollToMs == null || !state.found) return@LaunchedEffect
        DayScroll.indexFor(state.sections, scrollToMs, hasPhotosChip = state.frameIds.isNotEmpty())?.let { list.scrollToItem(it) }
        scrolled = true
    }
    LazyColumn(Modifier.fillMaxSize().background(PcColors.Surface), state = list) {
        item { ScreenHeader(if (state.found) state.title else "Day", onBack) }
        when {
            state.loading -> Unit
            !state.found -> item { Text("Day not found", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp)) }
            else -> {
                // The photos page by swipe from the day's first one.
                state.frameIds.firstOrNull()?.let { first ->
                    item { ChipRow(Icons.Outlined.PhotoLibrary, PcColors.ChipMint, "View ${plural(state.frameCount, "photo")}", onClick = { onPreview(listOf(PreviewRef.of(RefKind.FRAME, first))) }) }
                }
                if (state.sections.isEmpty()) {
                    item { Text("No memories saved this day", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp)) }
                } else {
                    timelineItems(state.sections) { onPreview(listOf(PreviewRef.of(RefKind.MEMORY, it))) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Where the Day list scrolls to for a time. Pure: the list is [header][photos chip?][per section: label, rows...]. */
object DayScroll {
    /** The LazyColumn item index of the row nearest [atMs] (the earlier row on a tie), or null when the day has no rows. */
    fun indexFor(sections: List<TimelineSection>, atMs: Long, hasPhotosChip: Boolean): Int? {
        var index = 1 + if (hasPhotosChip) 1 else 0
        var best: Int? = null
        var bestGap = Long.MAX_VALUE
        for (s in sections) {
            index++ // the section label
            for (r in s.rows) {
                val gap = kotlin.math.abs(r.atMs - atMs)
                if (gap < bestGap) { bestGap = gap; best = index }
                index++
            }
        }
        return best
    }
}
