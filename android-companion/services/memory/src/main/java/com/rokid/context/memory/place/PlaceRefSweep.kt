package com.rokid.context.memory.place

import com.rokid.context.memory.db.ContextDatabase

/** C1: the start-up sweep that ties the two databases together: a `PLACE_REF` model-attribution row (context.db) lives only while its `place_ref` caption (places.db) does. Best effort; the caller ignores a failure. */
object PlaceRefSweep {
    suspend fun run(db: ContextDatabase, pack: PlacePack): Int = db.modelAttributionDao().sweepPlaceRefs(pack.refPlaceIds())
}
