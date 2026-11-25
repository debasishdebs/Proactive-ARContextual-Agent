package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionLimits
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.preview.ActivityData
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a list can select (multi-select Forget, activity pagination A7). A selection is a set of [Ref]s (`family:id`), never row positions, so it survives paging, collapsing, view switches and a process death.
 * Only the kinds a Forget can start from are selectable: photos, memories (Moments are memories) and marks. Answers and nudges have no single-item Forget (v1.1 backlog), so their lists offer no selection.
 */
object SelectionKinds {
    fun familyOf(kind: ListKind): Family? = when (kind) {
        ListKind.FRAMES -> Family.PHOTO
        ListKind.MEMORIES, ListKind.MOMENTS -> Family.MEMORY
        ListKind.MARKS -> Family.MARK
        ListKind.ANSWERS, ListKind.NUDGES, ListKind.SCENES -> null
    }
    fun supports(kind: ListKind): Boolean = familyOf(kind) != null
    /** The refs of [ids] of [kind]: one family, nothing else (kind scoping). Empty for an unsupported kind. */
    fun refs(kind: ListKind, ids: Collection<String>): Set<Ref> = familyOf(kind)?.let { f -> ids.mapTo(LinkedHashSet()) { Ref(f, it) } } ?: emptySet()
    fun ref(kind: ListKind, id: String): Ref? = familyOf(kind)?.let { Ref(it, id) }
}

/**
 * The selection of one list: a set of [refs] and whether select mode is [active] (tap toggles). Pure value; every operation returns a new one. Set semantics: adding a ref twice changes nothing, order never matters.
 * More than [SelectionLimits.REFUSE_ABOVE] refs can be held (a user may keep tapping), but [overCeiling] then disables Forget before anything is planned.
 */
data class Selection(val refs: Set<Ref> = emptySet(), val active: Boolean = false) {
    val size: Int get() = refs.size
    val overCeiling: Boolean get() = refs.size > SelectionLimits.REFUSE_ABOVE
    operator fun contains(r: Ref) = r in refs
    /** Tap-and-hold: enters select mode with [r] selected (selects it when already in the mode; never un-selects). */
    fun enter(r: Ref) = Selection(refs + r, true)
    fun toggle(r: Ref) = Selection(if (r in refs) refs - r else refs + r, true)
    fun addAll(more: Collection<Ref>) = Selection(refs + more, true)
    /** Drops [gone] (refs the planner found missing). Leaving select mode is the user's choice, so an empty selection stays active. */
    fun prune(gone: Collection<Ref>) = copy(refs = refs - gone.toSet())
    /** "Clear": nothing selected, select mode off. */
    fun clear() = Selection()

    /** Process-death form: one ref per line (a ref is `family:id`; ids hold no newline). */
    fun encode(): String = (if (active) "1" else "0") + "\n" + refs.sortedBy { it.toString() }.joinToString("\n")
    companion object {
        fun decode(raw: String?): Selection {
            if (raw.isNullOrEmpty()) return Selection()
            val lines = raw.split('\n')
            return Selection(lines.drop(1).mapNotNull { Ref.parse(it) }.toSet(), lines.first() == "1")
        }
    }
}

/** The ids of one hour or day, or "too many" (above the Forget ceiling: nothing is selected, the user selects fewer). */
sealed interface Resolved {
    data class Ids(val refs: Set<Ref>) : Resolved
    data object TooMany : Resolved
}

/**
 * Resolves "Select this hour" and "Select this day" (ruling P2): a KIND-SCOPED id set across the whole interval, read with an ids-only query (no row is loaded, so 5,000 ids are cheap), at most
 * [SelectionLimits.REFUSE_ABOVE] + 1 of them. It selects the ids of THIS kind only: never the photos, marks or traces of that time (the dependency preview shows what a Forget reaches).
 */
class SelectionResolver(private val kind: ListKind, private val data: ActivityData, private val zone: () -> ZoneId) {
    private val limit = SelectionLimits.REFUSE_ABOVE + 1

    private fun result(ids: List<String>): Resolved = if (ids.size > SelectionLimits.REFUSE_ABOVE) Resolved.TooMany else Resolved.Ids(SelectionKinds.refs(kind, ids))

    /** Everything of the kind created on [day] (the day counts' filter). */
    suspend fun day(day: LocalDate): Resolved = if (!SelectionKinds.supports(kind)) Resolved.Ids(emptySet()) else result(data.idsIn(kind, DayBucket.bounds(day, zone()), limit))

    /** The memories [hour] of [day] shows (display time: the photo's, else the creation time); the "Earlier photos" group runs from the beginning of time to its end. */
    suspend fun hour(day: LocalDate, hour: HourNode): Resolved {
        if (!SelectionKinds.supports(kind)) return Resolved.Ids(emptySet())
        val range = if (hour.earlier) Long.MIN_VALUE..(hour.endMs - 1) else hour.startMs..(hour.endMs - 1)
        return result(data.momentIdsOfHour(DayBucket.bounds(day, zone()), range, limit))
    }
}
