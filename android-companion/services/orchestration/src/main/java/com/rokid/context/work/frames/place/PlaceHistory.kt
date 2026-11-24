package com.rokid.context.work.frames.place

/** What kind of card (or near-card) a history row records. */
enum class HistoryKind { PHOTO, NEARBY, PIN, PIN_CLUSTER, TEXT, POSSIBLE }

/**
 * One card-history event handed from the sampler to the card history store (F6, track B). It holds ids, a title and counts only: no coordinates, no card line.
 */
data class PlaceHistoryEvent(
    val kind: HistoryKind, val placeId: String, val title: String, val members: List<String>, val frameId: String?, val cardId: String?, val confPct: Int?,
)
