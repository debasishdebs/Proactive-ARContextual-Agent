package com.rokid.context.work.frames.place

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.work.frames.FramePhotos

/** Where Layer 2 reads its reference photos from: the app's own stores (pin Moments, glance references, seeded thumbnails). Never logged; counts only leave. */
object PlaceL2Refs {
    /** The reference photos of ONE place, up to [n], in the order: the pin's own Moment, the glance references (place_link REF/CARD_REF), then the seeded public thumbnail. [cameraRef] is the live photo ref a public thumbnail travels under (it is not user content). */
    suspend fun refsFor(app: Context, place: PlaceEntity, n: Int, cameraRef: String): List<L2Image> {
        val out = ArrayList<L2Image>()
        val db = MemoryGraph.get(app).db
        val gate = com.rokid.context.provenance.ProvenanceGate(db)
        suspend fun frame(id: String) {
            if (out.size >= n || gate.goneFrames(setOf(id), requirePresent = false).isNotEmpty()) return
            FramePhotos.load(app, listOf(id)).photos.singleOrNull()?.let { out += L2Image(it.image.base64Data, id) }
        }
        val own = PlaceGraph.pins(app).pins().firstOrNull { it.id == place.id }?.momentId?.let { m -> db.momentDao().photoFrameOf(m)?.id }
        if (own != null) frame(own)
        for (f in PlaceGraph.history(app).refFrames(listOf(place.id))[place.id].orEmpty().reversed()) { if (out.size >= n) break; frame(f) }
        if (out.size < n && !UserPlace.isUserId(place.id)) {
            val img = PlaceGraph.pack(app).imagesOf(listOf(place.id)).firstOrNull { it.status == com.rokid.context.memory.place.ImageState.OK && it.jpeg != null }
            if (img != null) out += L2Image(android.util.Base64.encodeToString(img.jpeg, android.util.Base64.NO_WRAP), cameraRef)
        }
        return out
    }

    /** Of [places], the ids that have ANY reference photo (a seeded thumbnail, a pin Moment, a glance reference). A row check only: nothing is decoded or loaded. */
    suspend fun placesWithRefs(app: Context, places: List<PlaceEntity>): Set<String> {
        val ids = places.map { it.id }
        val db = MemoryGraph.get(app).db
        val web = PlaceGraph.pack(app).imagesOf(ids.filter { !UserPlace.isUserId(it) }).filter { it.status == com.rokid.context.memory.place.ImageState.OK && it.jpeg != null }.map { it.placeId }
        val pinned = PlaceGraph.pins(app).pins().filter { p -> p.id in ids && p.momentId != null && db.momentDao().photoFrameOf(p.momentId!!) != null }.map { it.id }
        val glance = PlaceGraph.history(app).refFrames(ids).filterValues { it.isNotEmpty() }.keys
        return (web + pinned + glance).toSet()
    }
}
