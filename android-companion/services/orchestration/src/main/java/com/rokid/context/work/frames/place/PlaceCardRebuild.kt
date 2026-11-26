package com.rokid.context.work.frames.place

import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.memory.place.PlaceEntity

/**
 * Rebuilds the card of a history row for "Show on glasses again" and the detail line (spec 2026-11-24 B2). The history stores no card line: the text is re-read from the pack or the pin by place id, so a
 * place that is gone gives null ("This place is no longer in your pack"). Nothing here calls a model or reads a position; the card is built from stored text exactly like the original.
 */
object PlaceCardRebuild {
    const val PIN_LABEL = "Your pin"
    const val PINS_LABEL = "Your pins"
    const val SIGN_LABEL = "Sign read · your pin"
    const val PIN_FALLBACK_LINE = "Your pin"

    /** The ids a row needs from the pack: its place, or every member of a cluster. */
    fun idsOf(row: PlaceCardLogEntity): List<String> = if (row.kind == KIND_CLUSTER) members(row) else listOf(row.placeId)

    fun members(row: PlaceCardLogEntity): List<String> = row.members?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    /** The card of [row] from the places the pack returned for [idsOf]; null when the place (or every member of a cluster) is gone. The card carries no frame id unless the row still has its photo. */
    fun card(row: PlaceCardLogEntity, places: List<PlaceEntity>): PlaceCard? {
        val frame = row.frameId.orEmpty()
        return when (row.kind) {
            KIND_CLUSTER -> {
                val named = members(row).mapNotNull { id -> places.firstOrNull { it.id == id }?.name }
                if (named.isEmpty()) null
                else PlaceCard(row.placeId, row.title, named.take(3).joinToString(" · ").take(PlaceCards.LINE_MAX), PINS_LABEL, PlaceConfidence.PIN, frame)
            }
            else -> {
                val p = places.firstOrNull { it.id == row.placeId } ?: return null
                when (row.kind) {
                    "NEARBY" -> PlaceCard(p.id, p.name, PlaceCards.introOf(p, PlaceCards.LINE_MAX).ifEmpty { PlaceCards.kindLabel(p.kind) }, PlaceCards.NEARBY_LABEL, PlaceConfidence.NEARBY, frame) // the stored intro (V2a); the distance is not stored
                    "PIN" -> PlaceCard(p.id, p.name, PlaceCards.userLine(p.summary).ifEmpty { PIN_FALLBACK_LINE }, PIN_LABEL, PlaceConfidence.PIN, frame)
                    "TEXT" -> PlaceCard(p.id, p.name, PlaceCards.userLine(p.summary).ifEmpty { PIN_FALLBACK_LINE }, SIGN_LABEL, PlaceConfidence.PIN, frame)
                    else -> PlaceCards.photo(p, frame) // PHOTO (POSSIBLE is never rebuilt as a card)
                }
            }
        }
    }

    private const val KIND_CLUSTER = "PIN_CLUSTER"
}
