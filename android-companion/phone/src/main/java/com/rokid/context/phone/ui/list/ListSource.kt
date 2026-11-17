package com.rokid.context.phone.ui.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** One row in the generic list screen (ported from LiveFit `ui/list`). Sources map their domain objects onto this. */
data class ListItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** Leading icon chip; null shows no chip. */
    val icon: ImageVector? = null,
    val status: ItemStatus = ItemStatus.None,
    /** Optional right-aligned value, e.g. "2 AI steps". */
    val trailingText: String? = null,
    /** Non-null shows an on/off switch (tap the row to flip it); greyed out when the row has no action. */
    val toggle: Boolean? = null,
)

/** Generic row state; each source names it (e.g. Done = "Downloaded" or "Granted"). */
enum class ItemStatus { None, Done, InProgress, ActionNeeded }

/**
 * Confirm-then-run action for a row. With [confirmTitle] null the action runs immediately;
 * with [blocking] the screen shows the progress overlay; [run] reports 0..1 or null (indeterminate).
 */
data class ItemAction(
    val confirmTitle: String? = null,
    val confirmMessage: String = "",
    val confirmLabel: String = "OK",
    val blocking: Boolean = false,
    /** Non-null adds a Cancel button to the blocking overlay; [run] is expected to return soon after. */
    val onCancel: (() -> Unit)? = null,
    val run: suspend (onProgress: (Float?) -> Unit) -> ActionResult,
)

/** A row's secondary action, shown in its overflow menu under [label]. */
data class NamedAction(val label: String, val action: ItemAction)

sealed interface ActionResult {
    data object Done : ActionResult
    data object Silent : ActionResult
    data class Message(val text: String) : ActionResult
    data class Scheduled(val message: String) : ActionResult
    data class Failed(val message: String) : ActionResult
}

/** Data provider behind [ListScreen]. Sources take their own constructor params; the call site wires them. */
interface ListSource {
    val title: String
    val searchHint: String
    /** Labels for the status filter chips and section headers. Empty = no grouping/filtering. */
    val statusLabels: Map<ItemStatus, String> get() = emptyMap()
    /** False keeps the source's own order and hides the A-Z toggle. */
    val sortable: Boolean get() = true
    /** What an empty source says (a clear empty state, never a dead screen). */
    val emptyText: String get() = "Nothing here yet"
    val doneSection: String get() = ""
    val actionSection: String get() = ""
    /** Trailing icon for [ItemStatus.ActionNeeded] rows. */
    val actionIcon: ImageVector get() = Icons.AutoMirrored.Rounded.KeyboardArrowRight
    /** True shows a checkbox per row and the "Show selected" bar. */
    val selectable: Boolean get() = false
    /** The bottom bar's label for n selected rows. */
    val selectLabel: (Int) -> String get() = { "Show selected ($it)" }
    /** Each emission reloads the rows (e.g. download progress). */
    val changes: Flow<Unit> get() = emptyFlow()
    /** More rows exist than [load] returned (a long list is read a page at a time). */
    val hasMore: Boolean get() = false
    /** Asks for the next page; the screen then calls [load] again. */
    fun showMore() {}
    suspend fun load(): List<ListItem>
    fun actionFor(item: ListItem): ItemAction?
    /** Extra actions in the row's overflow menu; empty shows no menu. */
    fun moreActionsFor(item: ListItem): List<NamedAction> = emptyList()
}

/** What a paged list shows right now (activity pagination A4). [rows] are the summaries of the loaded window; [error] is a short fixed text (never a user's content). */
data class ListPane(
    val rows: List<ListItem> = emptyList(),
    /** Pages above the first loaded row were let go: "Show newer" reads them back. */
    val headDropped: Boolean = false,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    /** Rows that arrived above the first row while the user was further down ("N new"). */
    val newCount: Int = 0,
    val loaded: Boolean = false,
    val error: String? = null,
)

/**
 * A [ListSource] whose rows are read a keyset page at a time and kept by its owner (the [ListViewModel]), so a preview and back, or a process death, keeps the pages and the position. The screen reads [pane]
 * instead of calling [load]; it asks for the next page near the end ([loadMore]) and tells the source what it looks at ([onFirstVisible]).
 */
interface PagedListSource : ListSource {
    val pane: StateFlow<ListPane>
    /** The row id to scroll to once, after a restore (null when none). The screen calls [consumeRestore] when it has scrolled there. */
    val restoreTarget: StateFlow<String?>
    fun consumeRestore()
    fun loadMore()
    fun showNewer()
    fun clearNew()
    fun retry()
    /** The id of the first row the user sees: the position that survives process death. */
    fun onFirstVisible(id: String?)
}
