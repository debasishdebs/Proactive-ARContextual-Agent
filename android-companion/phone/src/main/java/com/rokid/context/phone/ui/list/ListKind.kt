package com.rokid.context.phone.ui.list

import com.rokid.context.phone.ui.preview.ActivityData
import com.rokid.context.phone.ui.preview.PreviewCtx
import com.rokid.context.phone.ui.preview.PreviewRef
import com.rokid.context.phone.ui.preview.Previewables
import com.rokid.context.phone.ui.preview.RefKind
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

/** THE one list entry point: the route of the common list of [kind] over [ids] (raw ids of that kind). Rows call previewMoment on tap. An empty [ids] shows the kind's empty state. */
fun listView(kind: ListKind, ids: List<String>): String = Routes.list(kind, ids)

/** The common list's rows for a kind. One source for every kind: its rows are the kind's [com.rokid.context.phone.ui.preview.Previewable] summaries, newest first; a tap calls [onPreview] with the item's [PreviewRef] string. */
class RecordListSource(
    private val kind: ListKind,
    private val ids: List<String>,
    private val data: ActivityData,
    private val zone: () -> ZoneId,
    private val today: () -> LocalDate,
    private val onPreview: (String) -> Unit,
) : ListSource {
    override val title = kind.title
    override val searchHint = kind.searchHint
    override val emptyText = kind.empty
    override val sortable = false

    override suspend fun load(): List<ListItem> =
        if (ids.isEmpty()) emptyList() else Previewables.factory(kind).load(ids, PreviewCtx(data, zone(), today())).map { it.summary() }
    override fun actionFor(item: ListItem) = ItemAction { onPreview(PreviewRef.of(kind.ref, item.id)); ActionResult.Silent }
}
