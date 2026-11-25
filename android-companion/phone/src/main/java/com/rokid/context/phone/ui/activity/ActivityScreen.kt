package com.rokid.context.phone.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.BarChart
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.GradientHeader
import com.rokid.context.core.ui.components.MetricTile
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SegmentedSwitch
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.components.StatusStrip
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerHost
import com.rokid.context.phone.ui.list.ListItemCard
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.preview.MarkPreview
import com.rokid.context.phone.ui.preview.PreviewRef
import com.rokid.context.phone.ui.preview.RefKind
import java.time.LocalDate

/** Activity tab (spec §2.4, mock activity-tab.html option A). Stateless. */
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onSelectPeriod: (Period) -> Unit,
    onTogglePause: () -> Unit,
    onBannerAction: (BannerAction) -> Unit,
    /** THE way an item opens: [com.rokid.context.phone.ui.preview.previewMoment] over these ref strings ([PreviewRef.of]). Every tap on a row calls it. */
    onPreview: (List<String>) -> Unit,
    /** THE way a list opens: [com.rokid.context.phone.ui.list.listView] over the kind and the period (a key, never the ids). */
    onOpenList: (ListKind, Period) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onSelectSort: (ActivitySort) -> Unit = {},
    /** The level chips (Photos, Moments, Scenes). */
    onSelectGranularity: (Granularity) -> Unit = {},
    /** Opens "Find a photo" (wave 3). */
    onSearchPhotos: () -> Unit = {},
) {
    /** The default level keeps the tag the page always had. */
    val seeAllTag = if (state.granularity.isDefault) "moments_all" else "level_all"
    LazyColumn(Modifier.fillMaxSize().background(PcColors.Surface).testTag("activity_list")) {
        item { GradientHeader("Activity", "What your glasses noticed") {
            IconButton(onClick = onSearchPhotos, modifier = Modifier.clip(CircleShape).background(PcColors.Surface).testTag("photo_search")) {
                Icon(Icons.Outlined.Search, contentDescription = "Find a photo", tint = PcColors.Ink)
            }
            SortMenu(state.sort, onSelectSort)
        } }
        item { BannerHost(state.status.banner, onBannerAction) }
        item {
            StatusStrip(
                state.status.captureText, live = state.status.live, batteryPct = state.status.batteryPct, paused = !state.status.pipelineEnabled,
                // ⏸/▶ follows only the phone pipeline; under a glasses pause the strip text says so and offers no toggle.
                onTogglePause = onTogglePause.takeUnless { state.status.pausedOnGlasses }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item {
            SegmentedSwitch(
                listOf("Today", "Week", "Month"), selectedIndex = state.period.ordinal, onSelect = { onSelectPeriod(Period.entries[it]) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            SegmentedSwitch(
                Granularity.entries.map { it.label }, selectedIndex = state.granularity.ordinal, onSelect = { onSelectGranularity(Granularity.entries[it]) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("granularity"),
            )
        }
        item { Text(state.header, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp)) }
        item {
            // Each card opens the list of its kind over that period's ids; an empty kind opens its empty state, never a dead tap.
            val open = { kind: ListKind -> onOpenList(kind, state.period) }
            // A level other than the default adds its own summary card above the tiles; the default page is unchanged.
            if (!state.granularity.isDefault) {
                val g = state.granularity
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    MetricTile(if (g == Granularity.SCENES) Icons.Outlined.Movie else Icons.Outlined.Image, PcColors.ChipAmber, "${g.count(state.tiles)}", g.label, Modifier.weight(1f).testTag("level_card"), onClick = { open(g.list) })
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Icons.Outlined.Image, PcColors.ChipMint, "${state.tiles.frames}", "Frames", Modifier.weight(1f), onClick = { open(ListKind.FRAMES) })
                MetricTile(Icons.Outlined.Psychology, PcColors.ChipViolet, "${state.tiles.memories}", "Memories", Modifier.weight(1f), onClick = { open(ListKind.MEMORIES) })
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Icons.Outlined.ChatBubbleOutline, PcColors.ChipSky, "${state.tiles.answers}", "Answers", Modifier.weight(1f), onClick = { open(ListKind.ANSWERS) })
                MetricTile(Icons.Outlined.Lightbulb, PcColors.ChipAmber, "${state.tiles.nudges}", "Nudges", Modifier.weight(1f), onClick = { open(ListKind.NUDGES) })
            }
        }
        item { SectionLabel("Marks") }
        if (state.marks.isEmpty()) item { Hint(MarkRows.EMPTY) }
        items(state.marks, key = { "mark_${it.labelId}" }) { row ->
            ListItemCard(MarkPreview.item(row), onClick = { onPreview(listOf(PreviewRef.of(RefKind.MARK, row.labelId))) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp).testTag("mark_row"))
        }
        if (state.marks.isNotEmpty()) item { SeeAll("See all marks", "marks_all") { onOpenList(ListKind.MARKS, state.period) } }
        if (state.period == Period.TODAY) {
            if (state.sections.isNotEmpty()) {
                timelineItems(state.sections) { onPreview(listOf(PreviewRef.of(RefKind.MEMORY, it))) }
                item { SeeAll(state.granularity.seeAll, seeAllTag) { onOpenList(state.granularity.list, state.period) } }
            }
            else if (state.today != null) {
                item { Hint("Nothing captured yet today") }
                if (!state.granularity.isDefault) item { SeeAll(state.granularity.seeAll, seeAllTag) { onOpenList(state.granularity.list, state.period) } }
            }
        } else {
            item {
                SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
                    BarChart(
                        values = state.bars.map { it.count }, labels = state.bars.map { it.label },
                        highlightIndex = state.bars.indexOfFirst { it.date == state.today }.takeIf { it >= 0 },
                        onBarClick = { i -> state.bars.getOrNull(i)?.let { onOpenDay(it.date) } },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item { SectionLabel("By day") }
            if (state.dayRows.isEmpty()) {
                item { Hint(if (state.period == Period.WEEK) "No activity this week" else "No activity this month") }
            }
            items(state.dayRows, key = { it.date.toString() }) { row ->
                ChipRow(Icons.Outlined.CalendarToday, PcColors.ChipSky, row.title, row.subtitle, onClick = { onOpenDay(row.date) })
            }
            if (!state.granularity.isDefault) item { SeeAll(state.granularity.seeAll, seeAllTag) { onOpenList(state.granularity.list, state.period) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The header's sort button: a small menu of the four orders, the current one checked. */
@Composable
private fun SortMenu(current: ActivitySort, onSelect: (ActivitySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.clip(CircleShape).background(PcColors.Surface)) {
            Icon(Icons.Outlined.SwapVert, contentDescription = "Sort", tint = PcColors.Ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActivitySort.entries.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label) },
                    onClick = { open = false; onSelect(o) },
                    trailingIcon = if (o == current) ({ Icon(Icons.Outlined.Check, contentDescription = "Selected") }) else null,
                )
            }
        }
    }
}

/** Opens the whole list of a section (the section shows its rows; the list is the same rows with search). */
@Composable
private fun SeeAll(text: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(start = 8.dp).testTag(tag)) { Text(text, color = PcColors.MintDeep) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}
