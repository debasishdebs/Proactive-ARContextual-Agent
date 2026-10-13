package com.rokid.context.phone.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.IconChip
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import kotlinx.coroutines.launch

private enum class SortOrder { AZ, ZA }

/** Saves a selection as a list (a Set is not Bundle-able). */
private val SelectionSaver = listSaver<MutableState<Set<String>>, String>(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })

/**
 * Reusable list screen (ported from LiveFit `ui/list`): search, an optional status filter, A-Z sort, row actions and,
 * when [ListSource.selectable], a checkbox per row with a "Show selected (N)" bar that hands [onOpenSelected] the ids
 * in the source's order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    source: ListSource,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenSelected: (List<String>) -> Unit = {},
    initiallySelected: Set<String> = emptySet(),
    onSelectionChange: (Set<String>) -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    var items by remember(source) { mutableStateOf<List<ListItem>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOrder.AZ) }
    var statusFilter by remember { mutableStateOf<ItemStatus?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Pair<ListItem, ItemAction>?>(null) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable(saver = SelectionSaver) { mutableStateOf(initiallySelected) }

    LaunchedEffect(source, reloadKey) { items = source.load() }
    // Reload when returning to the screen (another screen may have changed what the rows show).
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        var first = true
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { if (first) first = false else reloadKey++ } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val grouped = source.statusLabels.isNotEmpty()
    val runAction: (ListItem, ItemAction) -> Unit = { item, action ->
        if (action.blocking) progress = Progress(item.title, null)
        scope.launch {
            val result = action.run { p -> if (action.blocking) progress = Progress(item.title, p) }
            progress = null
            when (result) {
                ActionResult.Done -> onMessage("${item.title}: done")
                ActionResult.Silent -> Unit
                is ActionResult.Message -> onMessage(result.text)
                is ActionResult.Scheduled -> onMessage(result.message)
                is ActionResult.Failed -> onMessage(result.message)
            }
            reloadKey++
        }
    }
    val onRow: (ListItem) -> Unit = { row ->
        source.actionFor(row)?.let { action -> if (action.confirmTitle == null) runAction(row, action) else confirming = row to action }
    }
    val toggle: (ListItem) -> Unit = { row ->
        selected = if (row.id in selected) selected - row.id else selected + row.id
        onSelectionChange(selected)
    }

    val visible = items.orEmpty()
        .filter { query.isBlank() || it.title.contains(query, true) || (it.subtitle?.contains(query, true) == true) }
        .filter { statusFilter == null || it.status == statusFilter }
        .let { list -> if (!source.sortable) list else if (sort == SortOrder.AZ) list.sortedBy { it.title.lowercase() } else list.sortedByDescending { it.title.lowercase() } }
    val installed = if (grouped) visible.filter { it.status == ItemStatus.Done || it.status == ItemStatus.InProgress } else emptyList()
    val others = visible - installed.toSet()
    val rowSelection = if (source.selectable) RowSelection(selected, toggle) else null

    Box(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).testTag("list_screen")) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(source.title, onBack) {
                if (grouped) BadgedBox(badge = { if (statusFilter != null) Badge(containerColor = PcColors.Mint) }) {
                    IconButton(onClick = { showFilters = true }) { Icon(Icons.Rounded.FilterList, contentDescription = "Filter") }
                }
                if (source.sortable) IconButton(onClick = { sort = if (sort == SortOrder.AZ) SortOrder.ZA else SortOrder.AZ }) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Icon(Icons.Rounded.SortByAlpha, contentDescription = if (sort == SortOrder.AZ) "Sort, A to Z" else "Sort, Z to A")
                        Text(if (sort == SortOrder.AZ) "\u2193" else "\u2191", style = MaterialTheme.typography.labelMedium, color = PcColors.MintDeep)
                    }
                }
            }
            SearchBar(query, source.searchHint) { query = it }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PcColors.Mint) }
                    visible.isEmpty() -> EmptyState(if (items!!.isEmpty()) "Nothing here yet" else "No matches")
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        if (installed.isNotEmpty()) {
                            item { GroupLabel(source.doneSection, installed.size) }
                            item { RowsCard(installed, source, rowSelection, onRow) }
                        }
                        if (others.isNotEmpty()) {
                            item { if (grouped) GroupLabel(source.actionSection, others.size) else Spacer(Modifier.height(12.dp)) }
                            item { RowsCard(others, source, rowSelection, onRow) }
                        }
                    }
                }
            }

            if (source.selectable) {
                val n = selected.size
                Box(Modifier.fillMaxWidth().background(PcColors.Surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    GradientButton(
                        source.selectLabel(n),
                        onClick = { onOpenSelected(items.orEmpty().map { it.id }.filter { it in selected }) },
                        modifier = Modifier.fillMaxWidth().testTag("list_show_selected"),
                        enabled = n >= 1,
                    )
                }
            }
        }

        confirming?.let { (item, action) ->
            AlertDialog(
                onDismissRequest = { confirming = null },
                icon = { item.icon?.let { IconChip(it, PcColors.ChipSky, size = 48.dp) } },
                title = { Text(action.confirmTitle.orEmpty()) },
                text = { Text(action.confirmMessage) },
                confirmButton = {
                    TextButton(onClick = {
                        confirming = null
                        runAction(item, action)
                    }) { Text(action.confirmLabel, fontWeight = FontWeight.SemiBold, color = PcColors.MintDeep) }
                },
                dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel", color = PcColors.InkSoft) } },
                containerColor = PcColors.Surface,
            )
        }

        if (showFilters) {
            ModalBottomSheet(onDismissRequest = { showFilters = false }, containerColor = PcColors.Surface) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                    Text("Filter", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip("All", statusFilter == null) { statusFilter = null }
                        source.statusLabels.forEach { (status, label) -> StatusChip(label, statusFilter == status) { statusFilter = status } }
                    }
                }
            }
        }

        progress?.let { ProgressOverlay(it) }
    }
}

private data class Progress(val title: String, val fraction: Float?)

private class RowSelection(val selected: Set<String>, val toggle: (ListItem) -> Unit)

@Composable
private fun SearchBar(value: String, hint: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = PcColors.Surface,
            unfocusedContainerColor = PcColors.Surface,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).border(1.dp, PcColors.Line, RoundedCornerShape(28.dp)).testTag("list_search"),
    )
}

@Composable
private fun GroupLabel(text: String, count: Int) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
    }
}

@Composable
private fun RowsCard(rows: List<ListItem>, source: ListSource, selection: RowSelection?, onClick: (ListItem) -> Unit) {
    SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column {
            rows.forEachIndexed { i, item ->
                ItemRow(item, source, selection) { onClick(item) }
                if (i < rows.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(PcColors.Line))
            }
        }
    }
}

@Composable
private fun ItemRow(item: ListItem, source: ListSource, selection: RowSelection?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (selection != null) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection != null) {
            Checkbox(
                checked = item.id in selection.selected,
                onCheckedChange = { selection.toggle(item) },
                colors = CheckboxDefaults.colors(checkedColor = PcColors.Mint),
                modifier = Modifier.testTag("list_select_${item.id}"),
            )
        }
        Row(
            Modifier.weight(1f).clickable(onClick = onClick).padding(horizontal = if (selection != null) 4.dp else 16.dp, vertical = 12.dp)
                .padding(end = if (selection != null) 12.dp else 0.dp).testTag("list_row_${item.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item.icon?.let { IconChip(it, if (item.status == ItemStatus.Done) PcColors.ChipMint else PcColors.ChipSlate) }
            Column(Modifier.weight(1f).padding(horizontal = if (item.icon != null) 14.dp else 0.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft) }
            }
            item.trailingText?.let {
                Text(
                    it, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp),
                )
            }
            item.toggle?.let { Switch(it, onCheckedChange = null, enabled = source.actionFor(item) != null) }
            StatusBadge(item.status, source)
        }
    }
}

@Composable
private fun StatusBadge(status: ItemStatus, source: ListSource) {
    val label = source.statusLabels[status]
    when (status) {
        ItemStatus.Done -> Icon(Icons.Rounded.CheckCircle, contentDescription = label, tint = PcColors.Mint)
        ItemStatus.InProgress -> Icon(Icons.Rounded.Downloading, contentDescription = label, tint = PcColors.Sky)
        ItemStatus.ActionNeeded -> Box(
            Modifier.size(36.dp).clip(CircleShape).background(PcColors.ChipSky.first),
            contentAlignment = Alignment.Center,
        ) { Icon(source.actionIcon, contentDescription = label, tint = PcColors.ChipSky.second, modifier = Modifier.size(20.dp)) }
        ItemStatus.None -> Unit
    }
}

@Composable
private fun StatusChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PcColors.ChipMint.first, selectedLabelColor = PcColors.MintDeep),
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = PcColors.InkSoft, modifier = Modifier.testTag("list_empty")) }
}

/** Full-screen blocking overlay: swallows all touches until the action finishes. */
@Composable
private fun ProgressOverlay(p: Progress) {
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(PcColors.Surface).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (p.fraction == null) {
                    CircularProgressIndicator(Modifier.size(96.dp), color = PcColors.Mint, strokeWidth = 8.dp, trackColor = PcColors.ChipMint.first)
                } else {
                    CircularProgressIndicator(progress = { p.fraction }, modifier = Modifier.size(96.dp), color = PcColors.Mint, strokeWidth = 8.dp, trackColor = PcColors.ChipMint.first)
                    Text("${(p.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            Text("Keep the app open", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
        }
    }
}
