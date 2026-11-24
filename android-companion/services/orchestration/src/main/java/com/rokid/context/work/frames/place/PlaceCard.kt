package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceKinds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How sure the card is: [PHOTO] = the vision model picked this place from the photo; [NEARBY] = only the nearest place, not checked against the photo. */
enum class PlaceConfidence(val wire: String) { PHOTO("photo"), NEARBY("nearby"),
    /** The user's own pin(s) at this position: no photo was analysed (F1). The glasses render it like [PHOTO]: the wire label is the card text. */
    PIN("pin") }

/**
 * What the wearer sees (spec 2026-11-16 §B4). Every field is built from the place pack by [PlaceCards]; the model never writes card text (it only picks a candidate number).
 * [line] is at most [PlaceCards.LINE_MAX] characters; [sourceLabel] is like "Wikipedia · fetched 12 Nov" or "nearby · not checked".
 */
data class PlaceCard(val placeId: String, val title: String, val line: String, val sourceLabel: String, val confidence: PlaceConfidence, val frameId: String,
    /** This card instance, stamped by [PlaceCardHolder] when it is published ("" before); a dismissal names it so a later card with the same title is never cleared by it. */
    val cardId: String = "",
)

/**
 * Delivery seam (PG2): the phone side holds the newest card here; the overlay mapping onto the wire (an additive protocol field) and the glasses rendering are PG3. The hub reads [card]
 * where `pickOverlay` takes `place ?: nudge`.
 */
interface PlacePort {
    val card: StateFlow<PlaceCard?>
    /** Drops the card (the photo was forgotten, a Delete all ran, or the glasses dismissed it). */
    fun clear()
}

/**
 * The in-memory holder behind [PlacePort]. A card is never stored. Publication and withdrawal are fenced by an [epoch]: every withdrawal ([clear], [withdrawFrames]) advances it, and a card is
 * published only if the epoch is still the one the attempt started with, so a Forget or Delete all that ran while the card was being made can never be followed by its publication.
 */
class PlaceCardHolder : PlacePort {
    private val _card = MutableStateFlow<PlaceCard?>(null)
    private val lock = Any()
    private var epochValue = 0L
    override val card: StateFlow<PlaceCard?> = _card

    /** The withdrawal count: read at the start of an attempt, handed back to [publishIf]. */
    fun epoch(): Long = synchronized(lock) { epochValue }

    private var seq = 0L

    fun publish(card: PlaceCard) { synchronized(lock) { _card.value = card.copy(cardId = "pc${++seq}") } }

    /** Publishes [card] only when no withdrawal happened since [epoch]. Synchronous: the caller makes the zone/switch/wipe checks in the same step (see PlaceSampler.publish). False = nothing was published. */
    fun publishIf(card: PlaceCard, epoch: Long): Boolean =
        synchronized(lock) { if (epochValue != epoch) false else { _card.value = card.copy(cardId = "pc${++seq}"); true } }

    /** [publishIf] that returns the new card's id (null = nothing published): the history row of a "Show on glasses again" names it. */
    fun publishIfId(card: PlaceCard, epoch: Long): String? =
        synchronized(lock) { if (epochValue != epoch) null else "pc${++seq}".also { _card.value = card.copy(cardId = it) } }

    /** Clears the card only if it is still the instance [cardId] (a newer card with the same title stays). Withdrawals fence attempts in flight only when something was cleared. */
    fun clearIf(cardId: String): Boolean = synchronized(lock) {
        if (_card.value?.cardId == cardId) { epochValue++; _card.value = null; true } else false
    }

    /** Withdraws the card when it belongs to one of [frameIds] (a Forget), and fences attempts in flight. */
    fun withdrawFrames(frameIds: Collection<String>) { synchronized(lock) { epochValue++; if (_card.value?.frameId in frameIds) _card.value = null } }

    /** Withdraws the card when it is for [frameId] or [placeId], and fences attempts in flight. */
    fun withdraw(frameId: String?, placeId: String? = null) {
        synchronized(lock) { epochValue++; _card.value?.let { if (it.frameId == frameId || (placeId != null && it.placeId == placeId)) _card.value = null } }
    }

    override fun clear() { synchronized(lock) { epochValue++; _card.value = null } }
}

object PlaceCards {
    const val LINE_MAX = 140
    const val NEARBY_LABEL = "nearby · not checked"

    /** A photo-confirmed card: the first sentence of the pack summary and where the pack got it. */
    fun photo(p: PlaceEntity, frameId: String): PlaceCard =
        PlaceCard(p.id, p.name, (if (p.source == USER_SOURCE) userLine(p.summary) else firstSentence(p.summary)).ifEmpty { kindLabel(p.kind) }, sourceLabel(p), PlaceConfidence.PHOTO, frameId)

    const val PIN_SOURCE = "Your pin · here"
    const val CLUSTER_SOURCE = "Your pins · here"

    /** F1: the card of the user's own pin, from the pin alone: its name, its note (or "Your pin"). No model, no photo. */
    fun pin(p: PlaceEntity, frameId: String): PlaceCard =
        PlaceCard(p.id, p.name, userLine(p.summary).ifEmpty { "Your pin" }, PIN_SOURCE, PlaceConfidence.PIN, frameId)

    /** F1: close pins give one honest "several pins" card, never a guessed room. [members] nearest first; [parentNames] the site of each member (null = none). */
    fun cluster(members: List<PlaceEntity>, parentNames: List<String?>, frameId: String): PlaceCard {
        val label = ClusterLabel.of(members.map { it.name }, parentNames)
        val title = if (label == ClusterLabel.FALLBACK) "Several pins" else "$label · several pins"
        return PlaceCard(members.first().id, title.take(80), members.take(3).joinToString(" · ") { it.name }.take(LINE_MAX), CLUSTER_SOURCE, PlaceConfidence.PIN, frameId)
    }

    /** The text of a user place: the user's own note, whole (it is at most 140 characters), on one line. It is never summarised or rewritten. */
    fun userLine(note: String): String = note.replace(Regex("\\s+"), " ").trim().take(LINE_MAX)

    const val USER_SOURCE = "user"
    const val USER_LABEL = "Your note"

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
        if (p.source == USER_SOURCE) return USER_LABEL
        val name = when {
            p.source.startsWith("wikipedia") -> "Wikipedia"
            p.source == "wikidata" -> "Wikidata"
            p.source == "osm" -> "OpenStreetMap"
            else -> "Place pack"
        }
        if (p.fetchedAt <= 0) return name
        val f = SimpleDateFormat("d MMM", Locale.ENGLISH).apply { timeZone = zone }
        return "$name · fetched ${f.format(Date(p.fetchedAt))}"
    }

    fun kindLabel(kind: String): String = kind.replace('_', ' ').replaceFirstChar { it.uppercase() }.takeIf { kind in PlaceKinds.ALL } ?: "Place"
}
