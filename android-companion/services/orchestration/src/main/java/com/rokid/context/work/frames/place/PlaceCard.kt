package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceKinds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How sure the card is: [PHOTO] = the vision model picked this place from the photo; [NEARBY] = only the nearest place, not checked against the photo. */
enum class PlaceConfidence(val wire: String) { PHOTO("photo"), NEARBY("nearby") }

/**
 * What the wearer sees (spec 2026-11-16 §B4). Every field is built from the place pack by [PlaceCards]; the model never writes card text (it only picks a candidate number).
 * [line] is at most [PlaceCards.LINE_MAX] characters; [sourceLabel] is like "Wikipedia · fetched 12 Nov" or "nearby · not checked".
 */
data class PlaceCard(val placeId: String, val title: String, val line: String, val sourceLabel: String, val confidence: PlaceConfidence, val frameId: String)

/**
 * Delivery seam (PG2): the phone side holds the newest card here; the overlay mapping onto the wire (an additive protocol field) and the glasses rendering are PG3. The hub reads [card]
 * where `pickOverlay` takes `place ?: nudge`.
 */
interface PlacePort {
    val card: StateFlow<PlaceCard?>
    /** Drops the card (the photo was forgotten, a Delete all ran, or the glasses dismissed it). */
    fun clear()
}

/** The in-memory holder behind [PlacePort]. A card is never stored. */
class PlaceCardHolder : PlacePort {
    private val _card = MutableStateFlow<PlaceCard?>(null)
    override val card: StateFlow<PlaceCard?> = _card
    fun publish(card: PlaceCard) { _card.value = card }
    override fun clear() { _card.value = null }
}

object PlaceCards {
    const val LINE_MAX = 140
    const val NEARBY_LABEL = "nearby · not checked"

    /** A photo-confirmed card: the first sentence of the pack summary and where the pack got it. */
    fun photo(p: PlaceEntity, frameId: String): PlaceCard =
        PlaceCard(p.id, p.name, firstSentence(p.summary).ifEmpty { kindLabel(p.kind) }, sourceLabel(p), PlaceConfidence.PHOTO, frameId)

    /** The fallback: name and distance only, never a summary (the place was not checked against the photo). */
    fun nearby(p: PlaceEntity, distanceM: Double, frameId: String): PlaceCard =
        PlaceCard(p.id, p.name, distanceText(distanceM), NEARBY_LABEL, PlaceConfidence.NEARBY, frameId)

    fun distanceText(m: Double): String {
        val r = (Math.round(m / 10.0) * 10).toInt()
        return if (r <= 0) "here" else "$r m away"
    }

    /** The text up to the first sentence end, at most [LINE_MAX] characters (cut at a word end with an ellipsis). */
    fun firstSentence(summary: String): String {
        val one = summary.replace(Regex("\\s+"), " ").trim()
        if (one.isEmpty()) return ""
        val end = Regex("[.!?。](?=\\s|$)").find(one)?.range?.last
        val s = if (end != null) one.substring(0, end + 1) else one
        if (s.length <= LINE_MAX) return s
        val cut = s.substring(0, LINE_MAX - 1)
        val at = cut.lastIndexOf(' ')
        return (if (at > LINE_MAX / 2) cut.substring(0, at) else cut).trimEnd(',', ';', ':', '.', ' ') + "…"
    }

    /** "Wikipedia · fetched 12 Nov" (the source and the day the pack fetched it). */
    fun sourceLabel(p: PlaceEntity, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val name = when {
            p.source.startsWith("wikipedia") -> "Wikipedia"
            p.source == "wikidata" -> "Wikidata"
            p.source == "osm" -> "OpenStreetMap"
            p.source == "user" -> "Your pin"
            else -> "Place pack"
        }
        if (p.fetchedAt <= 0) return name
        val f = SimpleDateFormat("d MMM", Locale.ENGLISH).apply { timeZone = zone }
        return "$name · fetched ${f.format(Date(p.fetchedAt))}"
    }

    fun kindLabel(kind: String): String = kind.replace('_', ' ').replaceFirstChar { it.uppercase() }.takeIf { kind in PlaceKinds.ALL } ?: "Place"
}
