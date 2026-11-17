package com.rokid.context.phone.ui.list

import com.rokid.context.memory.Cursor
import com.rokid.context.phone.ui.preview.ActivityData
import com.rokid.context.phone.ui.preview.PreviewCtx
import com.rokid.context.phone.ui.preview.PreviewRef
import com.rokid.context.phone.ui.preview.Previewables
import com.rokid.context.phone.ui.preview.RefKind
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.activity.PeriodWindow
import com.rokid.context.phone.ui.shell.Routes
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

/**
 * The common list's rows for a kind. One source for every kind: its rows are the kind's [com.rokid.context.phone.ui.preview.Previewable] summaries, newest first; a tap calls [onPreview] with the item's [PreviewRef] string.
 * Its items are [ids] (a small set) or, with a [scope], every item of the kind in that period, read newest first, [PAGE] rows a keyset page ("Show more").
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
) : ListSource {
    override val title = kind.title
    override val searchHint = kind.searchHint
    override val emptyText = kind.empty
    override val sortable = false
    /** Codex r21: re-read when a Forget, a retention delete, Delete all or a redaction changes what the rows show. */
    override val changes = data.listChanges(kind)

    private var shown = pageSize
    private var more = false
    override val hasMore get() = more
    override fun showMore() { shown += pageSize }

    override suspend fun load(): List<ListItem> {
        if (scope == null) {
            more = ids.size > shown
            return if (ids.isEmpty()) emptyList() else Previewables.factory(kind).load(ids.take(shown), PreviewCtx(data, zone(), today())).map { it.summary() }
        }
        // A period: keyset pages of at most [pageSize] rows until one row more than [shown] is read (that row only says "there is more"); no read of every id of the period.
        val range = scope.millis(zone()); val out = mutableListOf<ListItem>(); var after = Cursor.head(range)
        while (out.size <= shown) {
            val page = data.page(kind, range, after, minOf(pageSize, shown + 1 - out.size))
            if (page.ids.isEmpty()) break
            out += Previewables.factory(kind).load(page.ids, PreviewCtx(page.data, zone(), today())).map { it.summary() }
            after = page.cursors.last()
        }
        more = out.size > shown
        return out.take(shown)
    }
    override fun actionFor(item: ListItem) = ItemAction { onPreview(PreviewRef.of(kind.ref, item.id)); ActionResult.Silent }

    companion object { const val PAGE = 100 }
}
