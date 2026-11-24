package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.Geo
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.memory.place.ZoneTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.coroutineContext

/** The pure text rules of F5b: quoted spans of a caption, and which pin a span names. No model, no network. */
object TextMatch {
    private val QUOTED = Regex("\"([^\"]{2,80})\"|“([^”]{2,80})”")
    private val STOP = setOf("museum", "gallery", "room", "hall", "fort", "show", "light", "sound", "kitchen", "bar", "the", "and", "of", "restaurant", "shop", "store", "cafe", "hotel")

    /** The text between straight or curly double quotes, 2 to 80 characters each. */
    fun quotedSpans(caption: String): List<String> = QUOTED.findAll(caption).map { (it.groups[1] ?: it.groups[2])!!.value.trim() }.filter { it.length >= 2 }.toList()

    private fun compact(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** The compact pin name is inside the span, or a distinctive pin token (4 letters or more, not a stop word) equals a token of the span. */
    fun names(pinName: String, span: String): Boolean {
        val p = compact(pinName)
        if (p.length >= 3 && compact(span).contains(p)) return true
        val st = tokens(span).toSet()
        return tokens(pinName).any { it.length >= 4 && it !in STOP && it in st }
    }
}

/**
 * F5b: a quoted sign name in a fresh caption against the user's OWN pins near the last good position. Text only and pins only: no model call, no photo, no position stored or logged. Needs a known recent
 * position ([PlaceFlight.good], at most 30 min old): without one it never matches. Exactly one pin named, the frame fresh (at most 2 min old) and the wearer not moved since: a glasses card (source "Sign read ·
 * your pin"), subject to the same once-per-place and visit rules as every other card. A late caption becomes a phone-only history row. Two or more pins named: ambiguous, nothing shown. Fenced like the sampler:
 * a pause zone means nothing is read.
 */
class PlaceTextMatch(
    private val scope: CoroutineScope,
    private val flight: PlaceFlight,
    private val store: PlaceFrameStore,
    /** frameId to its capture time (phone clock), for the frames of a batch. */
    private val captured: suspend (List<String>) -> Map<String, Long>,
    /** The user's pins (active). */
    private val pins: suspend () -> List<PlaceEntity>,
    private val enabled: () -> Boolean,
    private val textOn: () -> Boolean,
    private val zoneBlocked: suspend () -> Boolean,
    private val clock: () -> Long,
    private val elapsed: () -> Long,
    private val generation: () -> Long = { 0L },
    private val pipelineOn: () -> Boolean = { true },
    private val cards: PlaceCardHolder? = null,
    private val onCard: (PlaceCard) -> Unit = {},
    private val history: suspend (PlaceHistoryEvent) -> Unit = {},
    private val historyOn: () -> Boolean = { true },
    private val fence: ZoneFence = ZoneFence(),
    private val log: (String) -> Unit = {},
) {
    /** The captions of a batch that was just saved. Cheap and non-blocking; the work runs on [scope] behind the fence. */
    fun onCaptions(captions: List<FrameCaption>) {
        if (captions.isEmpty() || !enabled()) return
        fence.launch(scope) { try { run(captions) } catch (c: CancellationException) { throw c } catch (_: Exception) { log("place text match failed") } }
    }

    private fun result(frames: Int, quoted: Int, hits: Int, r: String) = log("place text match frames=$frames quoted=$quoted hits=$hits result=$r")

    private suspend fun run(captions: List<FrameCaption>) {
        val ticket = coroutineContext[ZoneTicket] ?: fence.ticket()
        if (zoneBlocked()) return // a zone: nothing is read
        if (!textOn()) { result(0, 0, 0, "off"); return }
        val gen = generation(); val epoch = cards?.epoch() ?: 0L
        val now = clock()
        val times = captured(captions.map { it.frameId })
        val recent = captions.filter { c -> times[c.frameId]?.let { it in now - RECENT_MS..now + SKEW_MS } == true && !store.tombstoned(c.frameId) }
        val spans = recent.associate { it.frameId to TextMatch.quotedSpans(it.caption) }.filterValues { it.isNotEmpty() }
        val quoted = spans.values.sumOf { it.size }
        if (quoted == 0) { result(recent.size, 0, 0, "none"); return }
        val good = flight.good?.takeIf { it.generation == gen && elapsed() - it.atElapsed <= GOOD_MAX_MS }
        if (good == null) { result(recent.size, quoted, 0, "no_fix"); return }
        val near = pins().filter { UserPlace.isUserId(it.id) && Geo.distanceM(good.lat, good.lon, it.lat, it.lon) <= RADIUS_M }
        class Hit(val pin: PlaceEntity, var capAt: Long, var frameId: String)
        val hits = LinkedHashMap<String, Hit>()
        for ((fid, ss) in spans) for (pin in near) if (ss.any { TextMatch.names(pin.name, it) }) {
            val at = times[fid] ?: continue
            val h = hits[pin.id]
            if (h == null) hits[pin.id] = Hit(pin, at, fid) else if (at > h.capAt) { h.capAt = at; h.frameId = fid }
        }
        if (hits.isEmpty()) { result(recent.size, quoted, 0, "none"); return }
        if (hits.size > 1) { result(recent.size, quoted, hits.size, "ambiguous"); return }
        val h = hits.values.first()
        val fresh = now - h.capAt <= LIVE_MS && !store.movedSince(h.capAt)
        if (!fresh) {
            if (historyOn()) try { history(PlaceHistoryEvent(HistoryKind.TEXT, h.pin.id, h.pin.name, emptyList(), h.frameId, null, null, CardDelivery.NOT_SENT_LATE)) } catch (c: CancellationException) { throw c } catch (_: Exception) { }
            result(recent.size, quoted, 1, "history"); return
        }
        // the same rules as every card, claimed ATOMICALLY with the sampler's pin cards: once per place while staying, one card per visit of a pin, and no second attempt holding the same pin
        val ids = listOf(h.pin.id)
        val shownHere = { _: String, c: PlaceFlight.CardAt? -> c != null && now - c.at in 0..PlaceSampler.ONCE_PER_PLACE_MS && (c.lat.isNaN() || Geo.distanceM(good.lat, good.lon, c.lat, c.lon) <= PlaceSampler.DEDUPE_M + good.accM) }
        if (!flight.reserve(ids, now, shownHere)) { result(recent.size, quoted, 1, "none"); return }
        try {
            val card = PlaceCards.pin(h.pin, h.frameId).copy(sourceLabel = PlaceCardRebuild.SIGN_LABEL)
            var newId: String? = null
            val shown = fence.act(ticket) {
                if (!(enabled() && pipelineOn() && generation() == gen)) false
                else if (cards != null) cards.publishIfId(card, epoch)?.also { newId = it } != null else { onCard(card); true }
            } == true
            if (!shown) { result(recent.size, quoted, 1, "none"); return }
            flight.noteCard(h.pin.id, PlaceFlight.CardAt(good.lat, good.lon, now))
            flight.visits.begin(listOf(PinVisits.Spot(h.pin.id, h.pin.lat, h.pin.lon, h.pin.radiusM)), now)
            if (historyOn()) try { history(PlaceHistoryEvent(HistoryKind.TEXT, h.pin.id, h.pin.name, emptyList(), h.frameId, newId, null)) } catch (c2: CancellationException) { throw c2 } catch (_: Exception) { }
        } finally { flight.unreserve(ids) }
        result(recent.size, quoted, 1, "card")
    }

    companion object {
        const val RECENT_MS = 10 * 60_000L
        const val SKEW_MS = 30_000L
        const val LIVE_MS = 2 * 60_000L
        const val GOOD_MAX_MS = 30 * 60_000L
        const val RADIUS_M = 2_000.0
    }
}
