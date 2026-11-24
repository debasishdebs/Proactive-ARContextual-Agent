package com.rokid.context.phone.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.rokid.context.phone.ui.list.paging.ForgetStage
import com.rokid.context.phone.ui.list.paging.SelectionController
import com.rokid.context.phone.ui.list.paging.SelectionState
import com.rokid.context.phone.ui.list.paging.SelectionText
import com.rokid.context.phone.ui.list.paging.HourNode
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
import com.rokid.context.phone.ui.list.paging.TreePane
import com.rokid.context.memory.forget.lifecycle.Family
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val RANGE_EMPTY = "No items in this range"

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
    /** Shown between the title bar and the search field (notes, switches, banners). */
    header: @Composable () -> Unit = {},
    /** The List | By time toggle and the broken-down pane (a period's Moments only); null = the plain list. */
    breakdown: BreakdownUi? = null,
    /** Multi-select Forget (Activity pagination A7/A8). Null, or one whose [SelectionController.available] is false, hides all of it: no long-press, no checkbox, no menu. */
    selection: SelectionController? = null,
    /** The date and time filter (a list over a period only); null hides the Filter button. */
    filter: FilterUi? = null,
) {
    val scope = rememberCoroutineScope()

    val paged = source as? PagedListSource
    var items by remember(source) { mutableStateOf<List<ListItem>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOrder.AZ) }
    var statusFilter by remember { mutableStateOf<ItemStatus?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Pair<ListItem, ItemAction>?>(null) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable(saver = SelectionSaver) { mutableStateOf(initiallySelected) }
    val pane by (paged?.pane ?: remember { MutableStateFlow(ListPane()) }).collectAsState()
    val listState = rememberLazyListState()
    val mode by (breakdown?.view ?: remember { MutableStateFlow(ListViewMode.LIST) }).collectAsState()
    val showTree = breakdown != null && mode == ListViewMode.BREAKDOWN
    val tree by (breakdown?.tree ?: remember { MutableStateFlow(TreePane()) }).collectAsState()
    val selState by (selection?.state ?: remember { MutableStateFlow(SelectionState()) }).collectAsState()
    val range by (filter?.range ?: remember { MutableStateFlow<DateTimeRange?>(null) }).collectAsState()
    var pickingRange by rememberSaveable { mutableStateOf(false) }
    val canSelect = selection != null && selection.available
    val picking = canSelect && selState.selection.active
    LaunchedEffect(selState.message) { selState.message?.let { onMessage(it); selection?.consumeMessage() } }

    if (paged == null) {
        LaunchedEffect(source, reloadKey) { items = source.load() }
        LaunchedEffect(source) { source.changes.collect { reloadKey++ } }
        // Reload when returning to the screen (another screen may have changed what the rows show). A paged list needs no reload: its pager follows the change signal.
        val lifecycle = LocalLifecycleOwner.current
        DisposableEffect(lifecycle) {
            var first = true
            val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { if (first) first = false else reloadKey++ } }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer) }
        }
    }
    val itemsNow: List<ListItem>? = if (paged != null) (if (pane.loaded) pane.rows else null) else items
    val grouped = source.statusLabels.isNotEmpty()
    val runAction: (ListItem, ItemAction) -> Unit = { item, action ->
        if (action.blocking) progress = Progress(item.title, null, action.onCancel)
        scope.launch {
            val result = action.run { p -> if (action.blocking) progress = Progress(item.title, p, action.onCancel) }
            progress = null
            when (result) {
                ActionResult.Done -> onMessage("${item.title}: done")
                ActionResult.Silent -> Unit
                is ActionResult.Message -> onMessage(result.text)
                is ActionResult.Scheduled -> onMessage(result.message)
                is ActionResult.Failed -> onMessage(result.message)
            }
            if (paged == null) reloadKey++ else paged.retry()
        }
    }
    val start: (ListItem, ItemAction) -> Unit = { row, action -> if (action.confirmTitle == null) runAction(row, action) else confirming = row to action }
    // In select mode a tap toggles the row instead of opening it.
    val onRow: (ListItem) -> Unit = { row -> if (picking) selection?.toggle(row.id) else source.actionFor(row)?.let { start(row, it) } }
    val chosen = remember(selState.selection.refs) { selState.selection.refs.mapTo(HashSet()) { it.id } } // one family per list: the ids name the refs
    val pick: RowPick? = if (canSelect) RowPick(picking, { id -> id in chosen }, { id -> selection!!.enter(id) }, { id -> selection!!.toggle(id) }) else null
    val toggle: (ListItem) -> Unit = { row ->
        selected = if (row.id in selected) selected - row.id else selected + row.id
        onSelectionChange(selected)
    }

    val visible = itemsNow.orEmpty()
        .filter { query.isBlank() || it.title.contains(query, true) || (it.subtitle?.contains(query, true) == true) }
        .filter { statusFilter == null || it.status == statusFilter }
        .let { list -> if (!source.sortable) list else if (sort == SortOrder.AZ) list.sortedBy { it.title.lowercase() } else list.sortedByDescending { it.title.lowercase() } }
    val installed = if (grouped) visible.filter { it.status == ItemStatus.Done || it.status == ItemStatus.InProgress } else emptyList()
    val others = visible - installed.toSet()
    val rowSelection = if (source.selectable) RowSelection(selected, toggle) else null
    val rowActions = RowActions(onRow, start)
    val entries = buildEntries(installed, others, grouped, source.doneSection, source.actionSection, paged != null && pane.headDropped, paged != null && pane.loading && pane.hasMore, source.hasMore, query.isNotBlank() && paged != null)

    // Paged list: ask for the next page when the last 10 rows come into view; remember the first visible row (it survives process death); scroll to the restored row once.
    if (paged != null) {
        LaunchedEffect(paged, listState, entries) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (last >= entries.size - AUTO_LOAD_AHEAD) paged.loadMore() }
        }
        LaunchedEffect(paged, listState, entries) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith(ROW_KEY) == true }?.key as? String }
                .collect { key -> paged.onFirstVisible(key?.removePrefix(ROW_KEY)) }
        }
        val target by paged.restoreTarget.collectAsState()
        LaunchedEffect(target, entries) {
            val id = target ?: return@LaunchedEffect
            val at = entries.indexOfFirst { it.key == "$ROW_KEY$id" }
            if (at >= 0) { listState.scrollToItem(at); paged.consumeRestore() }
        }
        val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
        LaunchedEffect(atTop, pane.newCount) { if (atTop && pane.newCount > 0) paged.clearNew() }
    }

    Box(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).testTag("list_screen")) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(source.title, onBack) {
                if (grouped) BadgedBox(badge = { if (statusFilter != null) Badge(containerColor = PcColors.Mint) }) {
                    IconButton(onClick = { showFilters = true }) { Icon(Icons.Rounded.FilterList, contentDescription = "Filter") }
                }
                if (filter != null) TextButton(onClick = { pickingRange = true }, modifier = Modifier.testTag("list_filter_button")) { Text(if (range != null) "Filter (on)" else "Filter", color = PcColors.MintDeep) }
                if (canSelect) SelectionMenu(selection!!, selState, tree, showTree, itemsNow.orEmpty().map { it.id })
                if (source.sortable) IconButton(onClick = { sort = if (sort == SortOrder.AZ) SortOrder.ZA else SortOrder.AZ }) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Icon(Icons.Rounded.SortByAlpha, contentDescription = if (sort == SortOrder.AZ) "Sort, A to Z" else "Sort, Z to A")
                        Text(if (sort == SortOrder.AZ) "\u2193" else "\u2191", style = MaterialTheme.typography.labelMedium, color = PcColors.MintDeep)
                    }
                }
            }
            header()
            range?.let { r -> filter?.let { FilterChipRow(r, it.clear) } }
            if (picking) SelectionBar(selState.selection.size) { selection?.clear() }
            if (breakdown != null) ViewToggle(mode, breakdown.setView)
            if (!showTree) SearchBar(query, source.searchHint) { query = it }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    showTree -> BreakdownPane(tree, breakdown!!.actions, onRow, if (range != null) "${source.emptyText}\n$RANGE_EMPTY" else source.emptyText, pick = pick?.let { p -> BreakdownPick(p, { d -> selection!!.selectDay(d) }, { d, h -> selection!!.selectHour(d, h) }) })
                    itemsNow == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PcColors.Mint) }
                    visible.isEmpty() -> EmptyState(if (itemsNow!!.isEmpty()) (if (range != null) "${source.emptyText}\n$RANGE_EMPTY" else source.emptyText) else "No matches")
                    else -> Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.testTag("list_lazy")) {
                            items(entries, key = { it.key }) { e ->
                                when (e) {
                                    is Entry.Group -> if (e.label.isEmpty()) Spacer(Modifier.height(12.dp)) else GroupLabel(e.label, e.count)
                                    is Entry.Note -> Text(e.text, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("list_note"))
                                    is Entry.Row -> RowCard(e.index, e.count) { ItemRow(e.item, source, rowSelection, rowActions, pick) }
                                    Entry.ShowNewer -> TextButton(onClick = { paged?.showNewer() }, modifier = Modifier.padding(horizontal = 8.dp).testTag("list_show_newer")) { Text("Show newer") }
                                    Entry.Skeleton -> Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(SKELETON_HEIGHT).clip(RoundedCornerShape(16.dp)).background(PcColors.Line).testTag("list_skeleton"))
                                    Entry.ShowMore -> TextButton(onClick = { source.showMore(); if (paged == null) reloadKey++ }, modifier = Modifier.padding(horizontal = 8.dp).testTag("list_show_more")) { Text("Show more") }
                                }
                            }
                        }
                        if (paged != null && pane.newCount > 0 && listState.firstVisibleItemIndex > 0) {
                            TextButton(
                                onClick = { paged.clearNew(); scope.launch { listState.animateScrollToItem(0) } },
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).background(PcColors.Mint).testTag("list_new_pill"),
                            ) { Text("${pane.newCount} new", color = PcColors.Surface) }
                        }
                        if (paged != null && pane.error != null) {
                            TextButton(onClick = { paged.retry() }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).testTag("list_retry")) { Text("${pane.error}. Retry") }
                        }
                    }
                }
            }

            if (source.selectable) {
                val n = selected.size
                Box(Modifier.fillMaxWidth().background(PcColors.Surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    GradientButton(
                        source.selectLabel(n),
                        onClick = { onOpenSelected(itemsNow.orEmpty().map { it.id }.filter { it in selected }) },
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
                    }, modifier = Modifier.testTag("list_confirm")) { Text(action.confirmLabel, fontWeight = FontWeight.SemiBold, color = PcColors.MintDeep) }
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

        if (pickingRange && filter != null) RangeFilterDialog(range, filter.apply, filter.clear) { pickingRange = false }

        progress?.let { ProgressOverlay(it) }
        if (canSelect) SelectionDialogs(selState, selection!!)
    }
}

/** What the screen needs to show the broken-down view (built from a [ListViewModel]). */
class BreakdownUi(val tree: StateFlow<TreePane>, val view: StateFlow<ListViewMode>, val setView: (ListViewMode) -> Unit, val actions: BreakdownActions)

@Composable
private fun ViewToggle(mode: ListViewMode, onChange: (ListViewMode) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.testTag("list_view_list")) { StatusChip("List", mode == ListViewMode.LIST) { onChange(ListViewMode.LIST) } }
        Box(Modifier.testTag("list_view_tree")) { StatusChip("By time", mode == ListViewMode.BREAKDOWN) { onChange(ListViewMode.BREAKDOWN) } }
    }
}

private data class Progress(val title: String, val fraction: Float?, val onCancel: (() -> Unit)? = null)

private class RowActions(val onRow: (ListItem) -> Unit, val start: (ListItem, ItemAction) -> Unit)

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

private const val ROW_KEY = "r:"
private const val AUTO_LOAD_AHEAD = 10
private val SKELETON_HEIGHT = 56.dp

/** One lazy item of the list: its [key] is stable, so a row keeps its place while rows are inserted above it. */
private sealed interface Entry {
    val key: String
    data class Group(val label: String, val count: Int) : Entry { override val key get() = "g:$label" }
    data class Note(val text: String) : Entry { override val key get() = "note" }
    data class Row(val item: ListItem, val index: Int, val count: Int) : Entry { override val key get() = "$ROW_KEY${item.id}" }
    data object ShowNewer : Entry { override val key get() = "show_newer" }
    data object Skeleton : Entry { override val key get() = "skeleton" }
    data object ShowMore : Entry { override val key get() = "show_more" }
}

private fun buildEntries(
    installed: List<ListItem>, others: List<ListItem>, grouped: Boolean, doneSection: String, actionSection: String,
    showNewer: Boolean, skeleton: Boolean, showMore: Boolean, searching: Boolean,
): List<Entry> = buildList {
    if (showNewer) add(Entry.ShowNewer)
    if (searching) add(Entry.Note("Searching the loaded rows"))
    if (installed.isNotEmpty()) { add(Entry.Group(doneSection, installed.size)); installed.forEachIndexed { i, r -> add(Entry.Row(r, i, installed.size)) } }
    if (others.isNotEmpty()) {
        add(Entry.Group(if (grouped) actionSection else "", others.size))
        others.forEachIndexed { i, r -> add(Entry.Row(r, i, others.size)) }
    }
    if (skeleton) add(Entry.Skeleton)
    if (showMore) add(Entry.ShowMore)
}

/** One row of a lazy list, drawn as a slice of the old rows card: the first row rounds its top, the last its bottom. */
@Composable
private fun RowCard(index: Int, count: Int, content: @Composable () -> Unit) {
    val r = 20.dp
    val shape = RoundedCornerShape(topStart = if (index == 0) r else 0.dp, topEnd = if (index == 0) r else 0.dp, bottomStart = if (index == count - 1) r else 0.dp, bottomEnd = if (index == count - 1) r else 0.dp)
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(PcColors.Surface, shape).border(1.dp, PcColors.Line, shape)) { content() }
}

@Composable
private fun ItemRow(item: ListItem, source: ListSource, selection: RowSelection?, actions: RowActions, pick: RowPick? = null) {
    val picked = pick != null && pick.isSelected(item.id)
    Row(
        Modifier.fillMaxWidth().padding(start = if (selection != null || pick?.active == true) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pick?.active == true) {
            Checkbox(checked = picked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = PcColors.Mint), modifier = Modifier.padding(start = 8.dp).testTag("list_pick_${item.id}"))
        }
        if (selection != null) {
            Checkbox(
                checked = item.id in selection.selected,
                onCheckedChange = { selection.toggle(item) },
                colors = CheckboxDefaults.colors(checkedColor = PcColors.Mint),
                modifier = Modifier.testTag("list_select_${item.id}"),
            )
        }
        Row(
            Modifier.weight(1f).rowClick(item, actions, pick, picked).padding(horizontal = if (selection != null) 4.dp else 16.dp, vertical = 12.dp)
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
        val more = source.moreActionsFor(item)
        if (more.isNotEmpty()) MoreMenu(item, more, actions)
    }
}

/** One row as a card, drawn like a list row: used where a list's rows sit inside another screen (Activity's Marks) so they look the same as in the list. */
@Composable
fun ListItemCard(item: ListItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SoftCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            item.icon?.let { IconChip(it, PcColors.ChipSlate) }
            Column(Modifier.weight(1f).padding(horizontal = if (item.icon != null) 14.dp else 0.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft) }
            }
            item.trailingText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp)) }
        }
    }
}

@Composable
private fun MoreMenu(item: ListItem, more: List<NamedAction>, actions: RowActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("list_more_${item.id}")) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${item.title}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            more.forEachIndexed { i, a ->
                DropdownMenuItem(text = { Text(a.label) }, onClick = { open = false; actions.start(item, a.action) }, modifier = Modifier.testTag("list_more_${item.id}_$i"))
            }
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
            p.onCancel?.let { cancel ->
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = cancel, modifier = Modifier.testTag("list_progress_cancel")) { Text("Cancel", color = PcColors.MintDeep) }
            }
        }
    }
}

/** What a row needs of select mode: [active] (taps toggle), whether an id is chosen, tap-and-hold ([enter]) and [toggle]. */
class RowPick(val active: Boolean, val isSelected: (String) -> Boolean, val enter: (String) -> Unit, val toggle: (String) -> Unit)

/** A row's tap and tap-and-hold. Without select mode it is the plain click; with it, a long press enters select mode and, once in it, a tap toggles. TalkBack hears "Select" for the long press and the selected state. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.rowClick(item: ListItem, actions: RowActions, pick: RowPick?, picked: Boolean): Modifier =
    if (pick == null) clickable { actions.onRow(item) }
    else combinedClickable(onClick = { actions.onRow(item) }, onLongClickLabel = if (pick.active) "Toggle selection" else "Select", onLongClick = { if (pick.active) pick.toggle(item.id) else pick.enter(item.id) })
        .semantics(mergeDescendants = true) { if (pick.active) { selected = picked; stateDescription = if (picked) "Selected" else "Not selected" } }

/** "N selected" with a Done button: a polite live region, so a screen reader announces the count as it changes. */
@Composable
private fun SelectionBar(count: Int, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(SelectionText.selected(count), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag("sel_count"))
        TextButton(onClick = onDone, modifier = Modifier.testTag("sel_done")) { Text("Done", color = PcColors.MintDeep) }
    }
}

/** The top-right menu of select mode: Select this page / hour / day, Clear and Forget selected (N). It exists only when the feature is available. */
@Composable
private fun SelectionMenu(sel: SelectionController, st: SelectionState, tree: TreePane, showTree: Boolean, pageIds: List<String>) {
    var open by remember { mutableStateOf(false) }
    val n = st.selection.size
    val focusDay = tree.focusDay?.let { d -> tree.days.firstOrNull { it.date == d } }
    val focusHour = focusDay?.hours?.filterIsInstance<HourNode>()?.firstOrNull { it.startMs == tree.focusHourStart }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("sel_menu")) { Icon(Icons.Rounded.MoreVert, contentDescription = "Selection menu") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!showTree) DropdownMenuItem(text = { Text("Select this page") }, onClick = { open = false; sel.selectPage(pageIds) }, modifier = Modifier.testTag("sel_page"))
            if (showTree && focusHour != null) DropdownMenuItem(text = { Text("Select this hour (${focusHour.count})") }, onClick = { open = false; sel.selectHour(focusDay.date, focusHour) }, modifier = Modifier.testTag("sel_hour"))
            if (showTree && focusDay != null) DropdownMenuItem(text = { Text("Select this day (${focusDay.count})") }, onClick = { open = false; sel.selectDay(focusDay.date) }, modifier = Modifier.testTag("sel_day"))
            if (n > 0) DropdownMenuItem(text = { Text("Clear") }, onClick = { open = false; sel.clear() }, modifier = Modifier.testTag("sel_clear"))
            if (n > 0) DropdownMenuItem(
                text = { Text(if (st.selection.overCeiling) "Forget selected ($n): select fewer" else "Forget selected ($n)") },
                enabled = !st.selection.overCeiling, onClick = { open = false; sel.forgetSelected() }, modifier = Modifier.testTag("sel_forget"),
            )
        }
    }
}

/** The planning and deleting overlay, the confirmation (with the dependency preview and, for a large one, the acknowledgement) and the refusal. */
@Composable
private fun SelectionDialogs(st: SelectionState, sel: SelectionController) {
    when (val stage = st.stage) {
        ForgetStage.Idle -> Unit
        ForgetStage.Planning -> ProgressOverlay(Progress("Checking what this removes", null))
        ForgetStage.Running -> ProgressOverlay(Progress("Forgetting ${st.selection.size} selected", null))
        is ForgetStage.Refused -> AlertDialog(
            onDismissRequest = { sel.dismiss() },
            title = { Text("Select fewer") },
            text = { Text(if (stage.reason == ForgetStage.Reason.CEILING) SelectionText.refusedCeiling(stage.count) else SelectionText.REFUSED_TOO_LARGE, modifier = Modifier.testTag("sel_refused")) },
            confirmButton = { TextButton(onClick = { sel.dismiss() }, modifier = Modifier.testTag("sel_refused_ok")) { Text("OK", color = PcColors.MintDeep) } },
            containerColor = PcColors.Surface,
        )
        is ForgetStage.Confirm -> {
            val p = stage.preview
            AlertDialog(
                onDismissRequest = { sel.dismiss() },
                title = { Text("Forget ${st.selection.size} selected?") },
                text = {
                    Column(Modifier.testTag("sel_confirm_text")) {
                        if (stage.changed) Text(SelectionText.CHANGED, color = PcColors.MintDeep, modifier = Modifier.testTag("sel_changed"))
                        Text("This cannot be undone. Forgetting these also affects:", modifier = Modifier.padding(top = 4.dp))
                        previewLines(p).forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sel_line")) }
                        if (p.pins > 0) Text(com.rokid.context.phone.ui.memory.ForgetScopeCopy.pinsLine(p.pins, p.pinNames), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp).testTag("sel_pins"))
                        if (p.needsAck) Row(Modifier.padding(top = 12.dp).clickable { sel.acknowledge(!stage.acknowledged) }.semantics(mergeDescendants = true) { stateDescription = if (stage.acknowledged) "Checked" else "Not checked" }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = stage.acknowledged, onCheckedChange = null, modifier = Modifier.testTag("sel_ack"))
                            Text(SelectionText.ack(p.nodes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { sel.confirm() }, enabled = stage.canConfirm, modifier = Modifier.testTag("sel_confirm")) { Text(if (p.pins > 0) com.rokid.context.phone.ui.memory.ForgetCopy.CONFIRM_WITH_PINS else "Forget", fontWeight = FontWeight.SemiBold, color = if (stage.canConfirm) PcColors.MintDeep else PcColors.InkSoft) } },
                dismissButton = { TextButton(onClick = { sel.dismiss() }, modifier = Modifier.testTag("sel_cancel")) { Text("Cancel", color = PcColors.InkSoft) } },
                containerColor = PcColors.Surface,
            )
        }
    }
}

/** The dependency preview as plain lines: what the plan erases, rebuilds and drops, by family (counts only). */
internal fun previewLines(p: com.rokid.context.memory.forget.lifecycle.SelectionPreview): List<String> {
    val names = mapOf(
        Family.PHOTO to "photos", Family.MEMORY to "memories", Family.MARK to "marks", Family.MOMENT to "moments", Family.SCENE to "scenes", Family.ANSWER to "answers", Family.NUDGE to "nudges",
        Family.PROPOSAL to "proposals", Family.TRACE to "traces", Family.RUN_RESULT to "generated results", Family.DERIVED_FIELD to "derived texts",
    )
    return p.counts.entries.filter { it.value.total > 0 }.sortedBy { it.key.ordinal }.map { (f, c) ->
        val parts = listOfNotNull(c.erase.takeIf { it > 0 }?.let { "$it removed" }, c.rebuild.takeIf { it > 0 }?.let { "$it rebuilt" }, c.drop.takeIf { it > 0 }?.let { "$it cleared" })
        "${c.total} ${names[f] ?: f.token} (${parts.joinToString(", ")})"
    }
}
