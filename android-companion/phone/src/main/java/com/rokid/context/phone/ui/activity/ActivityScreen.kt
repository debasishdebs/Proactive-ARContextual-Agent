package com.rokid.context.phone.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SwapVert
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
import androidx.compose.material.icons.outlined.Psychology
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
import java.time.LocalDate

/** Activity tab (spec §2.4, mock activity-tab.html option A). Stateless. */
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onSelectPeriod: (Period) -> Unit,
    onTogglePause: () -> Unit,
    onBannerAction: (BannerAction) -> Unit,
    onOpenMemory: (String) -> Unit,
    onOpenFrames: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onSelectSort: (ActivitySort) -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize().background(PcColors.Surface).testTag("activity_list")) {
        item { GradientHeader("Activity", "What your glasses noticed") { SortMenu(state.sort, onSelectSort) } }
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
        item { Text(state.header, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp)) }
        item {
            val framesClick = if (state.period == Period.TODAY && state.today != null) ({ onOpenFrames(state.today) }) else null
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Icons.Outlined.Image, PcColors.ChipMint, "${state.tiles.frames}", "Frames", Modifier.weight(1f), onClick = framesClick)
                MetricTile(Icons.Outlined.Psychology, PcColors.ChipViolet, "${state.tiles.memories}", "Memories", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Icons.Outlined.ChatBubbleOutline, PcColors.ChipSky, "${state.tiles.answers}", "Answers", Modifier.weight(1f))
                MetricTile(Icons.Outlined.Lightbulb, PcColors.ChipAmber, "${state.tiles.nudges}", "Nudges", Modifier.weight(1f))
            }
        }
        if (state.period == Period.TODAY) {
            if (state.sections.isNotEmpty()) timelineItems(state.sections, onOpenMemory)
            else if (state.today != null) item { Hint("Nothing captured yet today") }
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

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}
