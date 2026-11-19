package com.rokid.context.memory.forget.lifecycle

/**
 * STEP 1 of the Forget pipeline redesign (docs/superpowers/plans/2026-11-20-forget-pipeline-redesign.md): WHO asked for a Forget, as an immutable value. Pure data: nothing in the app builds or reads it yet,
 * so it changes no behaviour. The policy table ([ForgetPolicy]) is keyed by [ForgetSourceKind]; the plan view ([PolicyPlan]) is built from a [ForgetSource].
 */
enum class ForgetSourceKind(
    /** True when the USER asked for it (a tap, then a confirmation, or a confirmed Delete all being resumed after a crash). Only an explicit source may delete a user-authored object (a pin). */
    val explicit: Boolean,
) {
    /** One fact / photo / mark at scope Photo (previewer, mark undo). */
    SINGLE(true),
    /** Multi-select in the Activity lists. */
    MULTI(true),
    /** A time range (`ForgetActions.forgetRangeOutcome`; no screen today). */
    RANGE(true),
    /** Moment / Scene scoped Forget (Developer mode). */
    SCOPE(true),
    /** Settings > Memory > Delete all, and its crash resume. */
    DELETE_ALL(true),
    /** Retention expiry (memories by tier, photos, vectors, legacy frames). */
    SYSTEM_EXPIRY(false),
    /** The suspect-photo sweep (descriptions removed). */
    SYSTEM_SWEEP(false),
    /** The one-time invalid-Moment purge. */
    SYSTEM_PURGE(false),
}

/** The raw selection a source carries (ids, time bounds, scope). Metadata only: no content; [describe] gives counts and kinds, safe for a log line. */
sealed interface SourceSelection {
    /** Nothing selected: a source that names no target itself (Delete all, system jobs that discover their own targets). */
    data object None : SourceSelection
    /** Row ids chosen by the user or by a job (photo, memory, mark or Moment ids). */
    data class Ids(val ids: Set<String>) : SourceSelection
    /** A time range in epoch millis, inclusive (the range Forget). */
    data class TimeRange(val fromMs: Long, val toMs: Long) : SourceSelection
    /** A Moment or Scene scope over [ids] ([scope] is the `ForgetScope` name, e.g. "MOMENT" or "SCENE"). */
    data class Scoped(val scope: String, val ids: Set<String>) : SourceSelection

    fun describe(): String = when (this) {
        None -> "none"
        is Ids -> "ids=${ids.size}"
        is TimeRange -> "range"
        is Scoped -> "scope=$scope ids=${ids.size}"
    }
}

/**
 * Who asked, what they selected and where from. [explicit] must agree with [kind] (a system job cannot claim to be the user): checked at construction, so a wrong flag fails loudly instead of deleting a pin.
 */
data class ForgetSource(
    val kind: ForgetSourceKind,
    val explicit: Boolean = kind.explicit,
    val selection: SourceSelection = SourceSelection.None,
    /** The screen or job that built it (a literal such as "previewer", "activity-list", "retention-worker"); never user content. */
    val origin: String = "",
) {
    init { require(explicit == kind.explicit) { "${kind.name} is ${if (kind.explicit) "explicit" else "a system source"}" } }

    /** Counts and kinds only: never an id. */
    override fun toString() = "ForgetSource(${kind.name} explicit=$explicit ${selection.describe()} origin=$origin)"
}
