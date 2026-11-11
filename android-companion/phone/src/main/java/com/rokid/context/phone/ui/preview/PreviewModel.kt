package com.rokid.context.phone.ui.preview

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.phone.ui.activity.MarkRowUi
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.LocalDate

/** What an id string names. The id handed to [previewMoment] is `<prefix>:<id>`, so one entry point can open any kind of item. */
enum class RefKind(val prefix: String, val title: String) { MEMORY("memory", "Memory"), FRAME("frame", "Photos"), MARK("mark", "Mark"), ASK("ask", "Answer"), NUDGE("nudge", "Heads up") }

data class PreviewRef(val kind: RefKind, val id: String) {
    /** The string [previewMoment] takes. */
    val raw: String get() = "${kind.prefix}:$id"

    companion object {
        fun of(kind: RefKind, id: String): String = PreviewRef(kind, id).raw
        /** Null for a string that is not `<known prefix>:<non-blank id>`. */
        fun parse(raw: String): PreviewRef? {
            val at = raw.indexOf(':')
            if (at <= 0 || at == raw.lastIndex) return null
            val kind = RefKind.entries.firstOrNull { it.prefix == raw.substring(0, at) } ?: return null
            return PreviewRef(kind, raw.substring(at + 1))
        }
    }
}

/** OPEN: one item opens its previewer (several open the list). LIST: always the list, even for one item. */
enum class PreviewMode { OPEN, LIST }

/** [listKind] is the list a several-item preview falls back to when the items are not all one kind. */
data class PreviewConfig(val listKind: ListKind = ListKind.MOMENTS)

/** The photo to show after [forgotten] was forgotten from a previewer paging [frameIds]: the one now at its place (or the last), null when none remain (then the previewer closes). */
fun photoAfterForget(frameIds: List<String>, forgotten: String): String? {
    val at = frameIds.indexOf(forgotten)
    val rest = frameIds - forgotten
    return if (at < 0) rest.firstOrNull() else rest.getOrNull(at.coerceAtMost(rest.lastIndex))
}

/** Where [previewMoment] sends the user; [route] is the navigation route. */
sealed interface PreviewDestination {
    /** The composite previewer ([PreviewScreen]) on one item. */
    data class Screen(val ref: PreviewRef) : PreviewDestination
    /** The common list of [kind] over [ids] (raw ids, no prefix); an empty list shows the empty state. */
    data class Items(val kind: ListKind, val ids: List<String>) : PreviewDestination

    val route: String get() = when (this) {
        is Screen -> Routes.preview(ref.raw)
        is Items -> Routes.list(kind, ids)
    }
}

/**
 * THE one entry point for opening an item anywhere: a Mark row, a Moment row, a Day row, a photo, a search result, a cited source, a Home card, a row in any list. Pure: it only decides; the caller navigates to `.route`.
 * One item: its previewer (any kind, an answer or a nudge too). Several, or [PreviewMode.LIST]: the common list. A string that names nothing opens the empty list, never a dead tap.
 */
fun previewMoment(ids: List<String>, how: PreviewMode = PreviewMode.OPEN, config: PreviewConfig = PreviewConfig()): PreviewDestination {
    val refs = ids.mapNotNull(PreviewRef::parse).distinct()
    if (refs.isEmpty()) return PreviewDestination.Items(config.listKind, emptyList())
    if (how == PreviewMode.OPEN && refs.size == 1) {
        return PreviewDestination.Screen(refs.single())
    }
    val kind = ListKind.forRef(refs.first().kind, config.listKind)
    return PreviewDestination.Items(kind, refs.filter { it.kind == kind.ref }.map { it.id })
}

/** What the Activity page's items are read from (production: [RepoActivityData]). Each call takes ids of its own kind; an unknown id is simply missing from the result. Reads ids and rows only: a photo's bytes are never read here. */
interface ActivityData {
    suspend fun memories(ids: List<String>): List<MemoryEntity>
    suspend fun frames(ids: List<String>): List<FrameEntity>
    suspend fun marks(ids: List<String>): List<MarkRowUi>
    suspend fun asks(ids: List<String>): List<AskEntity>
    suspend fun nudges(ids: List<String>): List<NudgeEntity>
    /** The photos of [date], oldest first. */
    suspend fun dayFrames(date: LocalDate): List<FrameEntity>
    /** The ids of every item of [kind] in [range] (epoch ms), newest first: what a period's list opens over, so its route never carries them. */
    suspend fun idsIn(kind: ListKind, range: LongRange): List<String> = emptyList()
    /** Emits when the item [ref] names, or something it cites, changes (a Forget, a redaction, a Trust): the previewer then reads it again. The first emission is the current state. Nothing by default. */
    fun changes(ref: PreviewRef): Flow<Unit> = emptyFlow()
    /** Codex r21: emits after any write to the tables a [kind] list shows (a Forget, a retention delete, Delete all, a redaction), so the list reads again. Nothing by default. */
    fun listChanges(kind: ListKind): Flow<Unit> = emptyFlow()
}
