package com.rokid.context.phone.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.list.paging.DayNode
import com.rokid.context.phone.ui.list.paging.HourNode
import com.rokid.context.phone.ui.list.paging.PauseChip
import com.rokid.context.phone.ui.list.paging.QuietRun
import com.rokid.context.phone.ui.list.paging.TreePane
import java.time.LocalDate

/** What the broken-down pane calls (the ViewModel's functions). */
class BreakdownActions(
    val toggleDay: (LocalDate) -> Unit, val toggleHour: (LocalDate, Long) -> Unit,
    val previousPage: (LocalDate, Long) -> Unit, val nextPage: (LocalDate, Long) -> Unit,
)

private val ROW_SKELETON = 56.dp

/** One lazy item of the pane; its key is stable, so an open hour keeps its place while counts change above it. */
private sealed interface Bd {
    val key: String
    data class Day(val node: DayNode) : Bd { override val key get() = "d:${node.date}" }
    data class Quiet(val day: LocalDate, val index: Int, val run: QuietRun) : Bd { override val key get() = "q:$day:$index" }
    data class Hour(val day: LocalDate, val node: HourNode) : Bd { override val key get() = "h:$day:${node.startMs}" }
    data class Row(val day: LocalDate, val hour: Long, val item: ListItem) : Bd { override val key get() = "r:$day:$hour:${item.id}" }
    data class Skeleton(val day: LocalDate, val hour: Long, val index: Int) : Bd { override val key get() = "s:$day:$hour:$index" }
    data class Pages(val day: LocalDate, val node: HourNode) : Bd { override val key get() = "p:$day:${node.startMs}" }
}

private fun flatten(tree: TreePane): List<Bd> = buildList {
    for (d in tree.days) {
        add(Bd.Day(d))
        var quiet = 0
        for (e in d.hours.orEmpty()) when (e) {
            is QuietRun -> add(Bd.Quiet(d.date, quiet++, e))
            is HourNode -> {
                add(Bd.Hour(d.date, e))
                if (e.expanded) {
                    val rows = e.rows
                    if (rows == null) repeat(minOf(e.count, SKELETON_ROWS)) { add(Bd.Skeleton(d.date, e.startMs, it)) }
                    else rows.forEach { add(Bd.Row(d.date, e.startMs, it)) }
                    if (e.pages > 1) add(Bd.Pages(d.date, e))
                }
            }
        }
    }
}
private const val SKELETON_ROWS = 25

/**
 * The broken-down view of a period (activity pagination A6): a day, then its hours, then pages of 25 moments ("1 of 4"). Everything is a lazy item. A day or an hour that is still reading shows fixed-height
 * skeleton rows of the known count, so the scroll does not jump when the rows arrive.
 */
@Composable
fun BreakdownPane(tree: TreePane, actions: BreakdownActions, onRow: (ListItem) -> Unit, emptyText: String, modifier: Modifier = Modifier) {
    val entries = flatten(tree)
    if (!tree.loaded) { Box(modifier.fillMaxSize()) { Text("Loading", color = PcColors.InkSoft, modifier = Modifier.padding(20.dp).testTag("bd_loading")) }; return }
    if (tree.days.isEmpty()) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emptyText, color = PcColors.InkSoft, modifier = Modifier.testTag("bd_empty")) }; return }
    LazyColumn(modifier.fillMaxSize().testTag("bd_lazy"), contentPadding = PaddingValues(bottom = 32.dp)) {
        items(entries, key = { it.key }) { e ->
            when (e) {
                is Bd.Day -> DayRow(e.node) { actions.toggleDay(e.node.date) }
                is Bd.Quiet -> Text(e.run.label, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 4.dp).testTag("bd_quiet"))
                is Bd.Hour -> HourRow(e.node) { actions.toggleHour(e.day, e.node.startMs) }
                is Bd.Row -> MomentRow(e.item) { onRow(e.item) }
                is Bd.Skeleton -> Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().height(ROW_SKELETON).clip(RoundedCornerShape(14.dp)).background(PcColors.Line).testTag("bd_skeleton"))
                is Bd.Pages -> PageControl(e.node, { actions.previousPage(e.day, e.node.startMs) }, { actions.nextPage(e.day, e.node.startMs) })
            }
        }
    }
}

@Composable
private fun DayRow(d: DayNode, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp).testTag("bd_day_${d.date}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(d.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(if (d.expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (d.expanded) "Collapse day" else "Expand day", tint = PcColors.InkSoft)
    }
}

@Composable
private fun HourRow(h: HourNode, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 32.dp, end = 20.dp, top = 10.dp, bottom = 10.dp).testTag("bd_hour_${h.startMs}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            // An hour that has only a pause shows its chip and no count.
            if (h.count > 0 || h.pauses.isEmpty()) Text("${h.count}", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
            Icon(if (h.expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (h.expanded) "Collapse hour" else "Expand hour", tint = PcColors.InkSoft)
        }
        if (h.pauses.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { h.pauses.forEach { Chip(it) } }
    }
}

@Composable
private fun Chip(c: PauseChip) {
    Text(c.label, style = MaterialTheme.typography.labelSmall, color = PcColors.MintDeep, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(PcColors.ChipMint.first).padding(horizontal = 8.dp, vertical = 2.dp).testTag("bd_chip"))
}

@Composable
private fun MomentRow(item: ListItem, onClick: () -> Unit) {
    SoftCard(Modifier.padding(horizontal = 24.dp, vertical = 3.dp).fillMaxWidth().testTag("bd_row_${item.id}"), onClick = onClick) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** "‹ 1 of 4 ›": the pages of an hour replace each other. */
@Composable
private fun PageControl(h: HourNode, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = h.page > 0, modifier = Modifier.testTag("bd_prev_${h.startMs}")) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Previous page", modifier = Modifier.rotate(180f))
        }
        Text("${h.page + 1} of ${h.pages}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("bd_page_${h.startMs}"))
        IconButton(onClick = onNext, enabled = h.page + 1 < h.pages, modifier = Modifier.testTag("bd_next_${h.startMs}")) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next page")
        }
    }
}
