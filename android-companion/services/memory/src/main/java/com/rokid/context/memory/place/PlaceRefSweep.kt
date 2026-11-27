package com.rokid.context.memory.place

import com.rokid.context.memory.db.ContextDatabase

/** C1: the start-up sweep that ties the two databases together: a `PLACE_REF` model-attribution row (context.db) lives only while its `place_ref` caption (places.db) does. Best effort; the caller ignores a failure. */
object PlaceRefSweep {
    suspend fun run(db: ContextDatabase, pack: PlacePack): Int = db.modelAttributionDao().sweepPlaceRefs(pack.refPlaceIds()) + links(db, pack)

    /** B3: a loop-guard REF `place_link` (context.db) of a seeded or fetched place lives only while the place (places.db) does. Removes the REF rows of places that are gone; returns how many ids were swept. A user pin's rows are not touched here. */
    suspend fun links(db: ContextDatabase, pack: PlacePack): Int {
        val dao = db.placeLinkDao()
        val ids = dao.refPlaceIdsOfPack()
        if (ids.isEmpty()) return 0
        val live = pack.existingIds(ids)
        val dead = ids.filter { it !in live }
        var n = 0
        dead.chunked(500).forEach { n += dao.deleteRefLinksOf(it) }
        return n
    }
}
