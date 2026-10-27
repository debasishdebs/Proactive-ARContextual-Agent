package com.rokid.context.phone.ui.memory

enum class ForgetKind { FACT, MOMENT }

/** Memory detail's Forget port (production: `ForgetActions`, which runs on IO). False or a throw = failed; [com.rokid.context.orchestration.DataOpGate.ExportInProgress] = a running export blocked it. */
interface MemoryForget {
    suspend fun forgetFact(memoryId: String): Boolean
    suspend fun forgetMoment(frameId: String): Boolean

    /** The Forget scope the next Forget will run at (the setting). PHOTO unless a scoped port says otherwise. */
    fun activeScope(): com.rokid.context.memory.forget.ForgetScope = com.rokid.context.memory.forget.ForgetScope.PHOTO
    /** v0.9.7 slice C: what the Forget of [kind] would remove at the active scope (the dialog's counts). Null = this port has no scope (the dialog is the old one); a throw = it could not count. */
    suspend fun preview(kind: ForgetKind, memoryId: String, frameId: String?): ForgetPreview? = null
    /** Runs the Forget at the scope the dialog SHOWED (read once when it opened). The default is the old unscoped Forget. */
    suspend fun forgetScoped(kind: ForgetKind, memoryId: String, frameId: String?, scope: com.rokid.context.memory.forget.ForgetScope): Boolean =
        when (kind) { ForgetKind.FACT -> forgetFact(memoryId); ForgetKind.MOMENT -> frameId != null && forgetMoment(frameId) }

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
    val photos: Int, val memories: Int, val marks: Int, val moments: Int, val scenes: Int,
)

/** The Forget went through but, in a production build, a cached memory export that still holds text could not be deleted (it is retried at the next start and the next Forget). */
class CacheCleanupFailed : Exception("cached export not deleted")

/** Fixed copy (spec §2.4 Memory detail; never exception text). */
object ForgetCopy {
    const val FACT_TITLE = "Forget this fact?"
    const val FACT_BODY = "It's removed everywhere, and Proactive Context won't learn it from this photo again. Answers that used it will show as removed."
    const val MOMENT_TITLE = "Forget this moment?"
    const val MOMENT_BODY = "The photo and every fact from it are deleted. Answers that used them will show as removed."
    const val CONFIRM = "Forget"
    const val CANCEL = "Cancel"
    const val DONE = "Forgotten"
    /** A running export holds the delete gate for up to 30 s: the Forget did not run. */
    const val EXPORT_RUNNING = "An export is running. Try again in a minute, or when it finishes."
    /** A Canvas Save holds the delete gate (a save is short): the Forget did not run. */
    const val SAVE_RUNNING = "A save is running. Try again in a minute, or when it finishes."
    /** The Forget committed; a saved export file still holds the text and could not be deleted. */
    const val CACHE_LEFT = "Forgotten, but a saved copy of your memories (the export file in this app's cache) could not be deleted and may still contain it. It is retried when the app starts and at your next Forget; Delete all removes it too."
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

    /** The confirm dialog: the scope that will really run, in plain words, then the numbers. */
    fun dialogScope(s: com.rokid.context.memory.forget.ForgetScope) = when (s) {
        com.rokid.context.memory.forget.ForgetScope.PHOTO -> "Forget scope: Photo. Only this photo and what came from it go; neighbouring photos stay."
        com.rokid.context.memory.forget.ForgetScope.MOMENT -> "Forget scope: Moment. The whole Moment goes: every photo, fact, mark and sensor reading in it and around it."
        com.rokid.context.memory.forget.ForgetScope.SCENE -> "Forget scope: Scene. The whole Scene goes: every Moment in it, with all its photos, facts and marks."
    }

    fun counts(photos: Int, memories: Int, marks: Int, moments: Int, scenes: Int): String {
        fun n(c: Int, one: String, many: String) = "$c ${if (c == 1) one else many}"
        return "This deletes " + listOf(n(photos, "photo", "photos"), n(memories, "fact", "facts"), n(marks, "mark", "marks"), n(moments, "Moment", "Moments")).joinToString(", ") + if (scenes > 0) " and " + n(scenes, "Scene", "Scenes") + "." else "."
    }
    const val COUNTING = "Counting what will be deleted\u2026"
    const val COUNTS_FAILED = "Couldn't count what would be deleted, so nothing was forgotten. Try again."
}
