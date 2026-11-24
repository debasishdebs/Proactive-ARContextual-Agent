package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.work.frames.place.PlaceGraph

/**
 * The photos of explicit importance that live OUTSIDE `context.db` and that quota eviction (K6) must never take: the photos a place card of the history is about, and the photos of the Moment a pin is linked to. (The ones inside the
 * database, a place memory, a mark within the radius and an accepted proposal's source, are excluded by `ForgetDao.quotaPhotosDue` itself.) Ids only. Any read failure throws: the caller then evicts nothing.
 */
internal object QuotaProtection {
    suspend fun frames(context: Context): Set<String> {
        val app = context.applicationContext
        val out = LinkedHashSet<String>()
        PlaceGraph.existingHistory(app)?.let { out += it.photoIds() }
        val fd = MemoryGraph.get(app).db.forgetDao()
        val store = PlaceGraph.pins(app)
        val pins = store.pins()
        // Codex v3 r1 P1-7: an unreadable or corrupt pin list reads as EMPTY; that must never look like "nothing is pinned": the caller evicts nothing
        check(!store.corrupt) { "pin preferences unreadable" }
        pins.mapNotNull { it.momentId }.distinct().forEach { id ->
            val sec = MomentId.parse(id) ?: return@forEach
            out += fd.frameIdsBetween(sec * 1000, sec * 1000 + 999)
        }
        return out
    }
}
