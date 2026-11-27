package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How sure the card is: [PHOTO] = the vision model picked this place from the photo; [NEARBY] = only the nearest place, not checked against the photo. */
enum class PlaceConfidence(val wire: String) { PHOTO("photo"), NEARBY("nearby"),
    /** V2b: an offline guess ("Maybe <place>"): the photo was captioned on the phone and the caption ranked against the places around. Never checked by a vision model; the glasses render it like [NEARBY]. */
    MAYBE("maybe"),
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
        PlaceCard(p.id, p.name, (if (UserPlace.isUserSource(p.source)) userLine(p.summary) else firstSentence(p.summary)).ifEmpty { kindLabel(p.kind) }, sourceLabel(p), PlaceConfidence.PHOTO, frameId)

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

    const val USER_SOURCE = UserPlace.SOURCE
    const val USER_LABEL = "Your note"

    /**
     * The no-network fallback (V2a): the stored intro of the place (the user's own note for a pin), then the distance, in the card's one [LINE_MAX]-character line; "not checked" stays in the source label. The place
     * was not matched to the photo, so the card only says what is stored about the nearest place. No stored intro = the distance alone, as before.
     */
    fun nearby(p: PlaceEntity, distanceM: Double, frameId: String): PlaceCard {
        val dist = distanceText(distanceM)
        val intro = introOf(p, LINE_MAX - DIST_SEP.length - dist.length)
        return PlaceCard(p.id, p.name, if (intro.isEmpty()) dist else intro + DIST_SEP + dist, NEARBY_LABEL, PlaceConfidence.NEARBY, frameId)
    }

    const val DIST_SEP = " \u00b7 "
    const val MAYBE_PREFIX = "Maybe: "
    const val MAYBE_LABEL = "offline guess · not checked"

    /** V2b: the offline guess. The title says "Maybe: <place>" in plain words (no number), the line is the stored intro, the source label says it is an offline guess that was not checked. */
    fun maybe(p: PlaceEntity, frameId: String): PlaceCard =
        PlaceCard(p.id, (MAYBE_PREFIX + p.name).take(TITLE_MAX), introOf(p, LINE_MAX).ifEmpty { kindLabel(p.kind) }, MAYBE_LABEL, PlaceConfidence.MAYBE, frameId)

    const val TITLE_MAX = 80

    /** The stored intro of [p] fitted into [max] characters: a user pin gives its note, a pack place whole sentences from the start of its summary (the card's one-line length rule, see [intro]). */
    fun introOf(p: PlaceEntity, max: Int): String = if (UserPlace.isUserSource(p.source)) userLine(p.summary).take(max.coerceAtLeast(0)) else intro(p.summary, max)

    /** Whole sentences from the start of [summary] while they fit in [max] characters; when even the first does not fit it is cut at a word end with an ellipsis. Empty for an empty summary or no room. */
    fun intro(summary: String, max: Int = LINE_MAX): String {
        val one = summary.replace(Regex("\\s+"), " ").trim()
        if (one.isEmpty() || max < MIN_INTRO) return ""
        val parts = one.split(Regex("(?<=[.!?。])\\s+"))
        var out = ""
        for (s in parts) { val next = if (out.isEmpty()) s else "$out $s"; if (next.length <= max) out = next else break }
        return out.ifEmpty { cut(parts.first(), max) }
    }

    private const val MIN_INTRO = 20

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
        return if (s.length <= LINE_MAX) s else cut(s, LINE_MAX)
    }

    /** [s] cut to at most [max] characters at a word end, with an ellipsis. */
    private fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        val c = s.substring(0, max - 1)
        val at = c.lastIndexOf(' ')
        return (if (at > max / 2) c.substring(0, at) else c).trimEnd(',', ';', ':', '.', ' ') + "…"
    }

    /** "Wikipedia · fetched 12 Nov" (the source and the day the pack fetched it). */
    fun sourceLabel(p: PlaceEntity, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        if (UserPlace.isUserSource(p.source)) return USER_LABEL
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
