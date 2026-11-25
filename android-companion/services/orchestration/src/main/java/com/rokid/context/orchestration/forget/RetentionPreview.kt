package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.memory.forget.ForgetService

/**
 * Retention P2: the read-only retention preview (counts only, never an id or a text). It asks the real [Retention.plan] what a clean-up pass WOULD do if it ran in [HOURS_NEXT_DAY] and [HOURS_NEXT_WEEK] hours, by kind, so what the preview
 * shows is what the pass selects (with the Developer flag `retentionP2` ON the photo counts already leave out the exempt photos). It writes nothing, deletes nothing and does not depend on the flag. [paused] says whether "Pause clean-up"
 * is on, in which case nothing is removed at all until it is turned off.
 */
data class RetentionPreview(val paused: Boolean, val exemptActive: Boolean, val day: Counts, val week: Counts) {
    /** [facts] = details that leave Quick recall (they are kept, only their search vector goes); the rest are files and vectors that are removed. */
    data class Counts(val facts: Int, val photos: Int, val skippedPhotos: Int, val imageVectors: Int) {
        val total: Int get() = facts + photos + skippedPhotos + imageVectors
    }

    /** One short sentence per window plus a note, counts only. Literal templates (LogContentScan: this is UI text, never logged). */
    fun lines(): List<String> {
        fun one(label: String, c: Counts) =
            if (c.total == 0) "$label: nothing is due." else "$label: ${c.photos} photos, ${c.skippedPhotos} skipped photos, ${c.imageVectors} image vectors removed; ${c.facts} details leave Quick recall (kept)."
        val out = arrayListOf(one("Next 24 hours", day), one("Next 7 days", week))
        if (exemptActive) out += "Photos a place card, a mark or a pin points to are not removed."
        if (paused) out += "Pause clean-up is on, so none of this happens yet."
        return out
    }

    companion object {
        const val HOURS_NEXT_DAY = 24L
        const val HOURS_NEXT_WEEK = 7 * 24L
        private const val HOUR = 3_600_000L

        /** Pure and read-only. A failed read returns null (the screen then shows nothing). */
        suspend fun compute(context: Context, nowMs: Long = System.currentTimeMillis()): RetentionPreview? = runCatching {
            val app = context.applicationContext
            val graph = MemoryGraph.get(app)
            val settings = MemorySettingsStore.get(app).settings.value
            val exempt = DeveloperFlags.retentionP2(app)
            val r = Retention(graph, ForgetService(graph.db, graph.proposals) { nowMs }, protectedFrames = { QuotaProtection.frames(app) }, exemptPhotos = exempt)
            RetentionPreview(DeveloperFlags.pauseRetention(app), exempt, counts(r, settings, nowMs + HOURS_NEXT_DAY * HOUR), counts(r, settings, nowMs + HOURS_NEXT_WEEK * HOUR))
        }.getOrNull()

        private suspend fun counts(r: Retention, s: MemorySettings, at: Long): Counts {
            val p = r.plan(s, at)
            return Counts(facts = p.demoteDue, photos = p.photosDue + p.legacyDue, skippedPhotos = p.skippedPhotosDue, imageVectors = p.vectorsDue)
        }
    }
}
