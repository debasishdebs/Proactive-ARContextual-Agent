package com.rokid.context.phone.ui.list

import androidx.lifecycle.SavedStateHandle
import com.rokid.context.memory.Cursor
import com.rokid.context.phone.ui.list.paging.KeysetPager
import com.rokid.context.phone.ui.list.paging.PageSource
import com.rokid.context.phone.ui.list.paging.PagerAnchor
import com.rokid.context.phone.ui.preview.ActivityData
import com.rokid.context.phone.ui.preview.ActivityPage
import com.rokid.context.phone.ui.preview.PreviewCtx
import com.rokid.context.phone.ui.preview.PreviewRef
import com.rokid.context.phone.ui.preview.Previewables
import com.rokid.context.phone.ui.preview.RefKind
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.activity.PeriodWindow
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The kinds of list the Activity page opens. [ref] is what a row's tap previews (the one mapping from a kind to [com.rokid.context.phone.ui.preview.previewMoment]).
 * A new kind: one value here and one [com.rokid.context.phone.ui.preview.Previewable] registered in `Previewables` (its row and its previewer sections).
 */
enum class ListKind(val title: String, val searchHint: String, val empty: String, val ref: RefKind) {
    FRAMES("Frames", "Search frames", "No frames here yet", RefKind.FRAME),
    MEMORIES("Memories", "Search memories", "No memories here yet", RefKind.MEMORY),
    ANSWERS("Answers", "Search answers", "No answers here yet", RefKind.ASK),
    NUDGES("Nudges", "Search nudges", "No nudges here yet", RefKind.NUDGE),
    MARKS("Marks", "Search marks", "No marks yet. A tap or a voice mark on your glasses will show up here.", RefKind.MARK),
    MOMENTS("Moments", "Search moments", "No moments here yet", RefKind.MEMORY);

    companion object {
        fun parse(raw: String?): ListKind? = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
        /** The list that holds [kind] items; [hint] wins when it holds them too (a memory is in both Memories and Moments). */
        fun forRef(kind: RefKind, hint: ListKind): ListKind = if (hint.ref == kind) hint else entries.first { it.ref == kind }
    }
}

/** Which items a period's list holds, as a small key for the route: the period and the day it was opened on (so the window is the one the Activity page showed). */
data class ListScope(val period: Period, val anchor: LocalDate) {
    val key: String get() = "${period.name}:$anchor"
    fun millis(zone: ZoneId): LongRange = PeriodWindow.of(period, anchor).millis(zone)

    companion object {
        fun parse(raw: String?): ListScope? {
            val at = raw?.indexOf(':') ?: return null
            if (at <= 0) return null
            val period = Period.entries.firstOrNull { it.name == raw.substring(0, at) } ?: return null
            val day = runCatching { LocalDate.parse(raw.substring(at + 1)) }.getOrNull() ?: return null
            return ListScope(period, day)
        }
    }
}

/** The route of the list of [kind] over a period: the Activity cards and "See all" use it, so a Month of any size opens with a key, not its ids. */
fun listView(kind: ListKind, period: Period, anchor: LocalDate): String = Routes.listIn(kind, ListScope(period, anchor).key)

/** THE entry point for a small set: the route of the common list of [kind] over [ids] (raw ids of that kind). Rows call previewMoment on tap. An empty [ids] shows the kind's empty state. */
fun listView(kind: ListKind, ids: List<String>): String = Routes.list(kind, ids)

/** One loaded row: its summary ([item] is null when the row vanished between the page and its summary: it still keeps its place in the paging) and its keyset cursor. */
class PagedRow(val cursor: Cursor, val id: String, val item: ListItem?)

/**
 * The common list's rows for a kind. One source for every kind: its rows are the kind's [com.rokid.context.phone.ui.preview.Previewable] summaries, newest first; a tap calls [onPreview] with the item's [PreviewRef] string.
 * Its items are [ids] (a small set: read in one go, "Show more" per [PAGE]) or, with a [scope], every item of the kind in that period, read a keyset page of [PAGE] rows at a time by a
 * [KeysetPager] (activity pagination: no cap, a window of ten pages). A scoped source is a [PagedListSource]: start it with [startIn] and keep it in a ViewModel.
 */
class RecordListSource(
    private val kind: ListKind,
    private val ids: List<String>,
    private val data: ActivityData,
    private val zone: () -> ZoneId,
    private val today: () -> LocalDate,
    private val onPreview: (String) -> Unit,
    private val scope: ListScope? = null,
    private val pageSize: Int = PAGE,
) : PagedListSource {
    override val title = kind.title
    override val searchHint = kind.searchHint
    override val emptyText = kind.empty
    override val sortable = false
    /** Codex r21: re-read when a Forget, a retention delete, Delete all or a redaction changes what the rows show. */
    override val changes = data.listChanges(kind)

    // ---- A small set of ids -------------------------------------------------------------------------------------------------------------------------
    private var shown = pageSize
    private var more = false
    override val hasMore get() = if (scope == null) more else pane.value.hasMore
    override fun showMore() { if (scope == null) shown += pageSize else loadMore() }

    override suspend fun load(): List<ListItem> {
        more = ids.size > shown
        return if (ids.isEmpty()) emptyList() else Previewables.factory(kind).load(ids.take(shown), PreviewCtx(data, zone(), today())).map { it.summary() }
    }

    // ---- A period: keyset pages ---------------------------------------------------------------------------------------------------------------------
    private val rows = object : PageSource<PagedRow> {
        override suspend fun page(range: LongRange, after: Cursor, n: Int): List<PagedRow> = summarize(data.page(kind, range, after, n))
        override fun cursorOf(t: PagedRow) = t.cursor
        override fun compare(a: Cursor, b: Cursor) = if (kind == ListKind.MARKS) Cursor.MARKS.compare(a, b) else Cursor.NEWEST_FIRST.compare(a, b)
        override val changes get() = data.listChanges(kind)
    }
    /** The summaries of one page (its rows were read with it: no per-row read). A row that vanished meanwhile keeps its place with a null item. */
    suspend fun summarize(p: ActivityPage): List<PagedRow> {
        if (p.ids.isEmpty()) return emptyList()
        val byId = Previewables.factory(kind).load(p.ids, PreviewCtx(p.data, zone(), today())).map { it.summary() }.associateBy { it.id }
        return p.ids.mapIndexed { i, id -> PagedRow(p.cursors[i], id, byId[id]) }
    }
    private var pager: KeysetPager<PagedRow>? = null
    private var filter: LongRange? = null
    private val busy = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)
    private val restore = MutableStateFlow<String?>(null)
    private var saved: SavedStateHandle? = null
    private var job: CoroutineScope? = null
    private var running: Job? = null
    private var loading: Job? = null

    private val paneOut = MutableStateFlow(ListPane(loaded = scope == null))
    override val pane: StateFlow<ListPane> get() = paneOut
    override val restoreTarget: StateFlow<String?> get() = restore
    override fun consumeRestore() { restore.value = null }

    /** The period narrowed to the date and time filter, so the database reads only the rows inside it. */
    private fun build(): KeysetPager<PagedRow>? = scope?.let { KeysetPager(rows, RangeFilter.narrow(it.millis(zone()), filter), pageSize) }

    /**
     * Sets the date and time filter ([range] in epoch ms, both ends included; null clears it). Before [startIn] it only records it; after, the list starts again from the newest row inside the range
     * (a new bounded query, nothing is filtered in memory). A list of a small set of ids has no period and ignores it.
     */
    fun setFilter(range: LongRange?) {
        if (filter == range) return
        filter = range
        val host = job ?: return
        if (scope == null) return
        saved?.set(KEY_ANCHOR, "")
        pager = build()
        attach(host, null)
    }

    /** Starts the paging in [host] (a ViewModel's scope): the first page, or a restore from [saved] after a process death, then a refresh after every debounced change. */
    fun startIn(host: CoroutineScope, saved: SavedStateHandle? = null) {
        if (scope == null || job != null) return
        job = host; this.saved = saved
        pager = build()
        attach(host, saved?.get<String>(KEY_ANCHOR)?.let(::parseAnchor))
    }

    private fun attach(host: CoroutineScope, anchor: PagerAnchor?) {
        val p = pager ?: return
        running?.cancel(); loading?.cancel()
        running = host.launch {
            launch { combine(p.state, busy, failure) { s, b, e -> ListPane(s.rows.mapNotNull { it.item }, s.headDropped, s.hasMore, b, s.newCount, s.loaded, e) }.collect { paneOut.value = it } }
            launch { p.refreshSignals.collect { guarded(this) { p.refresh() } } }
        }
        loading = guarded(host) { if (anchor != null) { val i = p.restore(anchor); restore.value = p.state.value.rows.getOrNull(i)?.id } else p.loadFirst() }
    }

    private fun guarded(scope: CoroutineScope, block: suspend () -> Unit): Job =
        scope.launch {
            busy.value = true
            try { block(); failure.value = null } catch (e: CancellationException) { throw e } catch (e: Exception) { failure.value = "Couldn't load this list" } finally { busy.value = false }
        }
    override fun loadMore() { val s = job ?: return; val p = pager ?: return; if (!busy.value && p.state.value.hasMore) guarded(s) { p.loadMore() } }
    override fun showNewer() { val s = job ?: return; val p = pager ?: return; guarded(s) { p.showNewer(); restore.value = null } }
    override fun clearNew() { pager?.clearNew() }
    override fun retry() { val s = job ?: return; val p = pager ?: return; guarded(s) { if (p.state.value.loaded) p.refresh() else p.loadFirst() } }
    override fun onFirstVisible(id: String?) {
        val p = pager ?: return
        val cursor = id?.let { want -> p.state.value.rows.firstOrNull { it.item?.id == want }?.cursor }
        saved?.set(KEY_ANCHOR, formatAnchor(p.anchor(cursor)))
    }

    override fun actionFor(item: ListItem) = ItemAction { onPreview(PreviewRef.of(kind.ref, item.id)); ActionResult.Silent }

    companion object {
        const val PAGE = 100
        const val KEY_ANCHOR = "list.anchor"
        /** `windowKey|windowId|visibleKey|visibleId`; ids never hold `|`... a row id is a UUID or a number, and an empty id is the head. */
        fun formatAnchor(a: PagerAnchor) = "${a.windowAfter.key}|${a.windowAfter.id}|${a.firstVisible?.key ?: ""}|${a.firstVisible?.id ?: ""}"
        fun parseAnchor(raw: String): PagerAnchor? {
            val f = raw.split('|'); if (f.size != 4) return null
            val wk = f[0].toLongOrNull() ?: return null
            val vk = f[2].toLongOrNull()
            return PagerAnchor(Cursor(wk, f[1]), vk?.let { Cursor(it, f[3]) })
        }
    }
}
