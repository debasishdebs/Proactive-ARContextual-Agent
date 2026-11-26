package com.rokid.context.work.frames.place

import android.content.Context

/**
 * Place object S3: THE ONE place that computes a Place's Moments (design section 1). A Place is exactly one user pin; its Moments are its reference Moment (`UserPlace.momentId`, read live from the pin, never copied)
 * UNION the Moments of its card links (`PlaceCardHistory.linkedMomentIds`, the `place_link` rows; empty when none are written). Pointers only: Moments saved near a pin are NOT its Moments (Q3). Ids only; never logged.
 * Pins are not children of Moments: the pointer goes Place -> Moment and nothing else reads it.
 */
object PlaceMoments {
    /** The union as a pure function (tests, and callers that already hold both halves): distinct, reference first. */
    fun union(reference: String?, linked: Collection<String>): List<String> = (listOfNotNull(reference) + linked).distinct()

    /** The Moment ids of [pinId]; empty for an unknown pin. A corrupt or unreadable pin list throws: the caller then offers no cascade. */
    suspend fun of(context: Context, pinId: String): List<String> {
        val app = context.applicationContext
        val store = PlaceGraph.pins(app)
        val ref = store.pins().firstOrNull { it.id == pinId }?.momentId
        check(!store.corrupt) { "pin preferences unreadable" }
        return union(ref, PlaceGraph.history(app).linkedMomentIds(pinId))
    }
}
