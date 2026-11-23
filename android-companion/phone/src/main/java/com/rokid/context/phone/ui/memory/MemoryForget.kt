package com.rokid.context.phone.ui.memory

/** FACT and MOMENT run at the Forget scope; MARK is a single-object Forget (the scope does not apply) and takes its id. */
enum class ForgetKind { FACT, MOMENT, MARK;
    val single: Boolean get() = this == MARK
}

/** Memory detail's Forget port (production: `ForgetActions`, which runs on IO). False or a throw = failed; [com.rokid.context.orchestration.DataOpGate.ExportInProgress] = a running export blocked it. */
interface MemoryForget {
    suspend fun forgetFact(memoryId: String): Boolean
    suspend fun forgetMoment(frameId: String): Boolean

    /** The Forget scope the next Forget will run at (the setting). PHOTO unless a scoped port says otherwise. */
    fun activeScope(): com.rokid.context.memory.forget.ForgetScope = com.rokid.context.memory.forget.ForgetScope.PHOTO
    /** v0.9.7 slice C: what the Forget of [kind] would remove at the active scope (the dialog's counts). Null = this port has no scope (the dialog is the old one); a throw = it could not count. */
    suspend fun preview(kind: ForgetKind, memoryId: String, frameId: String?): ForgetPreview? = null
    /** Runs the Forget at the scope the dialog SHOWED (read once when it opened). The default is the old unscoped Forget. */
    suspend fun forgetScoped(kind: ForgetKind, memoryId: String, frameId: String?, scope: com.rokid.context.memory.forget.ForgetScope, planHash: String? = null): Boolean =
        when (kind) { ForgetKind.FACT -> forgetFact(memoryId); ForgetKind.MOMENT -> frameId != null && forgetMoment(frameId); else -> forgetObject(kind, memoryId) }

    /**
     * [forgetScoped] bound to the pins the dialog named ([confirmedPins], the ids of the preview): only those may be deleted with the Moment. The default ignores them (a port with no pins, e.g. a fake).
     * A Forget that was NOT confirmed against a successful preview ([forgetMoment], [forgetFact]) deletes no pin.
     */
    suspend fun forgetScopedPins(kind: ForgetKind, memoryId: String, frameId: String?, scope: com.rokid.context.memory.forget.ForgetScope, planHash: String?, confirmedPins: Set<String>): Boolean = forgetScoped(kind, memoryId, frameId, scope, planHash)

    /** The Forget of one photo-less mark by [id] (kind MARK): same typed throws as the others. */
    suspend fun forgetObject(kind: ForgetKind, id: String): Boolean = false

    companion object {
        val None = object : MemoryForget {
            override suspend fun forgetFact(memoryId: String) = false
            override suspend fun forgetMoment(frameId: String) = false
        }
    }
}

/** What the dialog shows: the scope asked for, the scope it really runs at (an entry never forgets less than itself; a missing Moment/Scene falls back), and the counts. */
data class ForgetPreview(
    val requested: com.rokid.context.memory.forget.ForgetScope, val effective: com.rokid.context.memory.forget.ForgetScope,
    val photos: Int, val memories: Int, val marks: Int, val moments: Int, val scenesRemoved: Int, val scenesShrunk: Int,
    /** Identifies exactly the set counted: the delete refuses ([ContentChanged]) if what it would remove is not that set any more. */
    val planHash: String = "",
    /** Items outside the deleted window that are affected but NOT deleted (an outside photo an in-window fact cites is locked; derived facts elsewhere become invalid). */
    val outside: Int = 0,
    /** Too much is connected to forget in one step: the dialog explains and Forget stays disabled. */
    val tooLarge: Boolean = false,
    /** Regenerable run results of other photos that share a provider call with an outside photo whose outputs are scrubbed: DELETED too (the next run recreates them). */
    val peerResults: Int = 0,
    /** A large rebuild (more than [com.rokid.context.memory.forget.MomentForget.MAX_UNACKED_RESULTS] regenerable results): the dialog warns and the delete needs [ackHash], sent only after the user ticks the acknowledgement. */
    val needsAck: Boolean = false, val ackHash: String = "",
    /** Slice 1.4.2: pinned places that reference a Moment this Forget removes: they are deleted with it ("also deletes N pins"). */
    val pins: Int = 0, val pinNames: List<String> = emptyList(),
    /** The ids of exactly the pins named above; the confirmed Forget may delete only these. */
    val pinIds: Set<String> = emptySet(),
    /** The pin lookup failed: the pins are unknown and the Forget runs without deleting any pin. */
    val pinsUnknown: Boolean = false,
)

/** The Forget was refused because too much is connected to it: nothing was deleted. */
class TooLarge : Exception("too large")

/** A scoped Forget was confirmed against a different set than the one previewed: nothing was deleted; the dialog says so and counts again. */
class ContentChanged : Exception("content changed since the preview")

/** The Forget went through but, in a production build, a cached memory export that still holds text could not be deleted (it is retried at the next start and the next Forget). */
class CacheCleanupFailed : Exception("cached export not deleted")

/** Slice 1.4.2: the removal of the pins that go with this Moment could not be prepared: nothing was forgotten. */
class PinPrepFailed : Exception("pin removal not prepared")

/** Slice 1.4.2: the pins this Forget would delete changed since they were confirmed: nothing was forgotten. */
class PinsNotConfirmed : Exception("pins not confirmed")

/** Slice 1.4.2: the Forget went through; only the removal of its pins did not finish (it completes at the next start). */
class PinNotice(val pending: Boolean, val cacheLeft: Boolean) : Exception("pin notice")

/** Fixed copy (spec §2.4 Memory detail; never exception text). */
object ForgetCopy {
    const val FACT_TITLE = "Forget this fact?"
    const val FACT_BODY = "It's removed everywhere, and Proactive Context won't learn it from this photo again. Answers that used it will show as removed."
    const val MOMENT_TITLE = "Forget this moment?"
    const val MOMENT_BODY = "The photo and every fact from it are deleted. Answers that used them will show as removed."
    const val MARK_TITLE = "Forget this mark?"
    const val MARK_BODY = "The mark is deleted, and the Moment made from it goes with it. Any photo taken for this mark is forgotten too."
    /** Said in the dialog of every single-object Forget: the Forget scope setting (Photo, Moment, Scene) does not apply. */
    const val SINGLE_SCOPE = "This forgets just this one item. The Forget scope setting does not apply."
    const val CONFIRM = "Forget"
    /** Slice 1.4.2: the confirm button when pinned places go with the Moment. */
    const val CONFIRM_WITH_PINS = "Forget Moment and pin(s)"
    const val CANCEL = "Cancel"
    const val DONE = "Forgotten"
    /** A running export holds the delete gate for up to 30 s: the Forget did not run. */
    const val EXPORT_RUNNING = "An export is running. Try again in a minute, or when it finishes."
    /** A Canvas Save holds the delete gate (a save is short): the Forget did not run. */
    const val SAVE_RUNNING = "A save is running. Try again in a minute, or when it finishes."
    /** The Forget committed; a saved export file still holds the text and could not be deleted. */
    const val CACHE_LEFT = "Forgotten, but a saved copy of your memories (the export file in this app's cache) could not be deleted and may still contain it. It is retried when the app starts and at your next Forget; Delete all removes it too."
    /** The set changed between the counts and the confirm (new photos or marks, a changed Scene): nothing was deleted. */
    const val CONTENT_CHANGED = "Content changed since the preview. Review again."
    /** Slice 1.4.2: the pin removal could not be prepared, so nothing was forgotten. */
    const val PIN_PREP_FAILED = "Could not prepare the pin removal; nothing was forgotten."
    /** Slice 1.4.2: the Forget committed but the removal of its pins could not be recorded: they stay. */
    const val PIN_NOT_REMOVED = "Forgotten. The pinned places that used it could not be removed; they stay and will show Needs a Moment."
    fun pinNotice(pending: Boolean, cacheLeft: Boolean): String = (if (cacheLeft) CACHE_LEFT + " " else "") + (if (pending) PIN_CLEANUP_PENDING else PIN_NOT_REMOVED)
    /** Slice 1.4.2: the Forget committed; the pin removal finishes at the next start. */
    const val PIN_CLEANUP_PENDING = "Forgotten. The pinned places that used it will be removed when the app next starts."
    /** Slice 1.4.2: the pins this Forget would delete are not the ones that were confirmed. */
    const val PINS_CHANGED = "The pinned places affected changed. Review and try again; nothing was forgotten."
    const val FAILED = "Couldn't forget that \u2014 try again"
}

/**
 * The words of the Forget scope (Settings > Capture and the Forget confirm dialog). One line per option says what is deleted; the dialog states the active scope in plain words. Fixed copy, never data.
 */
object ForgetScopeCopy {
    fun label(s: com.rokid.context.memory.forget.ForgetScope) = when (s) {
        com.rokid.context.memory.forget.ForgetScope.PHOTO -> "Photo"
        com.rokid.context.memory.forget.ForgetScope.MOMENT -> "Moment"
        com.rokid.context.memory.forget.ForgetScope.SCENE -> "Scene"
    }

    /** Settings: what each option deletes. */
    fun settingLine(s: com.rokid.context.memory.forget.ForgetScope) = when (s) {
        com.rokid.context.memory.forget.ForgetScope.PHOTO -> "Photo: only that photo and what came from it, plus the marks and Moments within the Forget radius. Neighbouring photos stay."
        com.rokid.context.memory.forget.ForgetScope.MOMENT -> "Moment: everything of the Moment around it, every photo, fact, mark and sensor reading, extended by the Forget radius."
        com.rokid.context.memory.forget.ForgetScope.SCENE -> "Scene: the whole Scene it belongs to, every Moment in it, and the Scene itself. The most destructive choice."
    }

    /** Slice 1.4.2: a pin whose reference Moment is forgotten is forgotten too; the confirmation says how many. Empty when none. */
    fun pinsLine(pins: Int, names: List<String> = emptyList()): String {
        if (pins <= 0) return ""
        val shown = names.take(5).joinToString(", ") { "\u201C$it\u201D" }
        val more = if (pins > names.take(5).size && names.isNotEmpty()) " and ${pins - names.take(5).size} more" else ""
        val list = if (shown.isEmpty()) "" else ": $shown$more"
        return "Also deletes $pins pinned ${if (pins == 1) "place" else "places"} that ${if (pins == 1) "uses" else "use"} ${if (pins == 1) "this Moment" else "these Moments"} as a reference$list. This cannot be undone, and place cards may stop appearing there."
    }

    /** The confirm dialog: the scope that will really run, in plain words, then the numbers. */
    fun dialogScope(s: com.rokid.context.memory.forget.ForgetScope) = when (s) {
        com.rokid.context.memory.forget.ForgetScope.PHOTO -> "Forget scope: Photo. Only this photo and what came from it go; neighbouring photos stay."
        com.rokid.context.memory.forget.ForgetScope.MOMENT -> "Forget scope: Moment. The whole Moment goes: every photo, fact, mark and sensor reading in it and around it."
        com.rokid.context.memory.forget.ForgetScope.SCENE -> "Forget scope: Scene. The whole Scene goes: every Moment in it, with all its photos, facts and marks."
    }

    /** "This deletes 4 photos, 5 facts, 1 mark, 3 Moments. 1 Scene removed, 2 Scenes shrink." Scenes that only lose some Moments are NOT counted as deleted. */
    fun counts(photos: Int, memories: Int, marks: Int, moments: Int, scenesRemoved: Int, scenesShrunk: Int): String {
        fun n(c: Int, one: String, many: String) = "$c ${if (c == 1) one else many}"
        val scenes = listOfNotNull(
            if (scenesRemoved > 0) n(scenesRemoved, "Scene", "Scenes") + " removed" else null,
            if (scenesShrunk > 0) n(scenesShrunk, "Scene", "Scenes") + (if (scenesShrunk == 1) " shrinks" else " shrink") else null,
        )
        return "This deletes " + listOf(n(photos, "photo", "photos"), n(memories, "fact", "facts"), n(marks, "mark", "marks"), n(moments, "Moment", "Moments")).joinToString(", ") + "." + if (scenes.isEmpty()) "" else " " + scenes.joinToString(", ") + "."
    }
    /** The extra line when the Forget touches things it does not delete: counts only. */
    fun outsideLine(n: Int) = "$n ${if (n == 1) "item outside this window is" else "items outside this window are"} affected (not deleted)."
    fun peerResultsLine(n: Int) = "Up to $n other generated ${if (n == 1) "result" else "results"} from the same run will also be deleted (the next run recreates ${if (n == 1) "it" else "them"})."
    fun rebuildWarning(n: Int) = "This will rebuild more than ${com.rokid.context.memory.forget.MomentForget.MAX_UNACKED_RESULTS} generated results ($n)."
    const val ACK_LABEL = "I understand, forget anyway"
    /** Why Moment and Scene are greyed out in Settings. */
    const val EXPERIMENTAL = "Moment and Scene are experimental until a worn test: turn on Developer mode (Settings > Diagnostics) to use them. Photo is used until then."
    const val TOO_LARGE = "Too much is connected to this to forget in one step; use a smaller scope or Delete all"
    const val COUNTING = "Counting what will be deleted\u2026"
    const val COUNTS_FAILED = "Couldn't count what would be deleted, so nothing was forgotten. Try again."
}
