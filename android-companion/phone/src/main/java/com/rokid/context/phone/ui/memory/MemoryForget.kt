package com.rokid.context.phone.ui.memory

enum class ForgetKind { FACT, MOMENT }

/** Memory detail's Forget port (production: `ForgetActions`, which runs on IO). False or a throw = failed; [com.rokid.context.orchestration.DataOpGate.ExportInProgress] = a running export blocked it. */
interface MemoryForget {
    suspend fun forgetFact(memoryId: String): Boolean
    suspend fun forgetMoment(frameId: String): Boolean

    companion object {
        val None = object : MemoryForget {
            override suspend fun forgetFact(memoryId: String) = false
            override suspend fun forgetMoment(frameId: String) = false
        }
    }
}

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
