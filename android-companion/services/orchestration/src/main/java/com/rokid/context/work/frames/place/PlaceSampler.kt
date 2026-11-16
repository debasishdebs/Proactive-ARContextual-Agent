package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.place.Geo
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** A fix as the sampler needs it: where, how old and how accurate. Held in a local only; never stored or logged. */
data class PlaceFix(val lat: Double, val lon: Double, val ageMs: Long, val accuracyM: Float)

/** A dwell photo: its id and when it was captured (phone clock, null = unknown). */
data class PlaceFrame(val frameId: String, val capturedAtMs: Long?)

interface PlaceFrameStore {
    /** The newest dwell photo received at or after [sinceReceivedMs]. */
    suspend fun newestDwell(sinceReceivedMs: Long): PlaceFrame?
    suspend fun tombstoned(frameId: String): Boolean
}

/** The model's answer and whether the router proved the photo reached it. */
data class PlaceMatchResult(val output: PlaceMatchOutput, val delivered: Boolean)

/** What the memory writer needs for one `type="place"` row. */
data class PlaceMemoryRequest(val frameId: String, val place: PlaceEntity, val parentName: String?, val confidence: Float, val delivered: Boolean, val generation: Long)

enum class PlaceOutcome { DISABLED, ZONE, NO_FRAME, STALE_FRAME, SEEN, NO_FIX, STALE_FIX, NO_CANDIDATES, DUPLICATE, RATE_LIMITED, BUSY, MATCHED, NONE, CONTRACT, LOW_CONFIDENCE, NEARBY, NOTHING_NEARBY, DROPPED, FAILED }

/**
 * Process-wide single-flight and spacing state shared by every [PlaceSampler] (the hub makes a new one at each restart): one run at a time, one model call per [PlaceSampler.MIN_CALL_GAP_MS],
 * and the last attempt (position and time, kept in memory for [PlaceSampler.DEDUPE_MS] only) for the same-place skip.
 */
class PlaceFlight {
    internal val busy = AtomicBoolean(false)
    @Volatile internal var lastCallAt = Long.MIN_VALUE / 2
    @Volatile internal var lastAttempt: Attempt? = null
    internal data class Attempt(val lat: Double, val lon: Double, val at: Long)
}

/**
 * Place Guide matching (spec 2026-11-16 §B4), hooked after a frame is stored and committed. Runs only for a DWELL photo, only while Place Guide is on, and never while a pause zone is
 * active or approaching ([zoneBlocked], which fails closed: when it is true nothing is read, fetched or sent). A candidate list comes from the place pack for a fresh fix
 * (<= [MAX_FIX_AGE_MS], accuracy <= [MAX_FIX_ACCURACY_M]); none means no upload. Online, ONE [match] call sends the photo and the numbered names and kinds (no summaries); a choice outside the
 * set is a contract failure and counts as NONE. Offline or on a failed call, a fallback card shows the single nearest place within [NEARBY_M] with "nearby · not checked". The card text is
 * built from the pack ([PlaceCards]); the model only picks a number.
 *
 * Fences like the intent sampler: the source switch, the pipeline, the wipe [generation] and the forget tombstone are checked at admission and again after the call. [admit] (the shared
 * thermal/power/battery gate) is asked last, only before a model call. All logs are literal text and the outcome name.
 */
class PlaceSampler(
    private val scope: CoroutineScope,
    private val store: PlaceFrameStore,
    private val fix: suspend () -> PlaceFix?,
    private val candidates: suspend (lat: Double, lon: Double, networkUp: Boolean) -> PlaceCandidates,
    private val match: suspend (frameId: String, candidateLines: List<String>) -> PlaceMatchResult?,
    private val saveMemory: suspend (PlaceMemoryRequest) -> Boolean,
    private val onCard: (PlaceCard) -> Unit,
    private val clock: () -> Long,
    private val enabled: () -> Boolean,
    private val zoneBlocked: suspend () -> Boolean,
    private val networkUp: () -> Boolean,
    private val admit: () -> Boolean = { true },
    private val pipelineOn: () -> Boolean = { true },
    private val generation: () -> Long = { 0L },
    private val flight: PlaceFlight = PlaceFlight(),
    private val log: (String) -> Unit = {},
    private val stats: (PlaceOutcome) -> Unit = {},
    /** Monotonic ms for the call spacing (production: elapsed realtime); a wall-clock step never opens or shuts the gap. */
    private val elapsed: () -> Long = clock,
    /** The card holder: publication is fenced against Forget/Delete all by its epoch. Null = [onCard] is called directly (tests of the pure flow). */
    private val cards: PlaceCardHolder? = null,
    /** Removes the place memory this attempt wrote for [frameId] (a zone became active while it was being saved). */
    private val deleteMemory: suspend (frameId: String) -> Unit = {},
) {
    @Volatile private var closed = false
    @Volatile private var job: Job? = null
    private val handled = ArrayDeque<String>()

    /** A frame was stored. Cheap and non-blocking; the work runs on [scope]. */
    fun poke() {
        if (closed || !enabled()) return
        if (!flight.busy.compareAndSet(false, true)) { finish(PlaceOutcome.BUSY); return }
        val j = scope.launch { run() }
        job = j
        j.invokeOnCompletion { flight.busy.set(false) }
    }

    fun close() { closed = true; job?.cancel() }

    private fun live(gen: Long) = !closed && enabled() && pipelineOn() && generation() == gen

    private fun finish(o: PlaceOutcome) {
        stats(o)
        log("place outcome=${o.name}")
    }

    private suspend fun run() {
        try { finish(attempt()) } catch (c: CancellationException) { throw c } catch (_: Exception) { finish(PlaceOutcome.FAILED) }
    }

    /** Why work must stop now, or null. Asked after EVERY suspension point and before every side effect (a fix, a fetch, an upload, a write, a card): the zone state can change at any of them. */
    private suspend fun stopped(gen: Long, frameId: String? = null): PlaceOutcome? = when {
        !live(gen) -> PlaceOutcome.DISABLED
        zoneBlocked() -> PlaceOutcome.ZONE
        frameId != null && store.tombstoned(frameId) -> PlaceOutcome.DROPPED
        else -> null
    }

    private suspend fun attempt(): PlaceOutcome {
        val gen = generation()
        val cardEpoch = cards?.epoch() ?: 0L
        // The zone gate comes first: nothing below runs while a zone is active, approaching or unreadable.
        stopped(gen)?.let { return it }
        val now = clock()
        val f = store.newestDwell(now - RECENT_MS) ?: return PlaceOutcome.NO_FRAME
        val cap = f.capturedAtMs
        if (cap == null || cap < now - RECENT_MS || cap > now + SKEW_MS || store.tombstoned(f.frameId)) return PlaceOutcome.STALE_FRAME
        if (f.frameId in handled) return PlaceOutcome.SEEN
        stopped(gen, f.frameId)?.let { return it } // before the fix is asked for
        val fx = fix() ?: return PlaceOutcome.NO_FIX
        if (fx.ageMs < 0 || fx.ageMs > MAX_FIX_AGE_MS || fx.accuracyM.isNaN() || fx.accuracyM > MAX_FIX_ACCURACY_M || !fx.lat.isFinite() || !fx.lon.isFinite()) return PlaceOutcome.STALE_FIX
        stopped(gen, f.frameId)?.let { return it } // before the pack may fetch
        remember(f.frameId)
        val online = networkUp()
        val found = candidates(fx.lat, fx.lon, online)
        if (found.places.isEmpty()) return PlaceOutcome.NO_CANDIDATES
        flight.lastAttempt?.let { a ->
            if (now - a.at in 0..DEDUPE_MS && Geo.distanceM(fx.lat, fx.lon, a.lat, a.lon) <= DEDUPE_M) return PlaceOutcome.DUPLICATE
        }
        val hits = found.places.map { it to Geo.distanceM(fx.lat, fx.lon, it.lat, it.lon) }
            .sortedWith(compareBy({ gap(it.first, it.second) }, { it.second }, { it.first.id })).take(MAX_LIST)
        // The spacing is judged NOW (after any lengthy fetch), against the end of the previous call, on the monotonic clock.
        val callOk = online && elapsed() - flight.lastCallAt >= MIN_CALL_GAP_MS
        if (online && !callOk) return PlaceOutcome.RATE_LIMITED
        flight.lastAttempt = PlaceFlight.Attempt(fx.lat, fx.lon, now)
        stopped(gen, f.frameId)?.let { return it } // before the upload (the router asks again inside its permit)
        if (!callOk || !admit()) return fallback(hits, f.frameId, gen, cardEpoch)
        flight.lastCallAt = elapsed()
        val lines = hits.mapIndexed { i, h -> "${i + 1}. ${h.first.name} | ${h.first.kind.replace('_', ' ')}" }
        val res = try { match(f.frameId, lines) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
        flight.lastCallAt = elapsed() // the permit wait and the call are over: the gap runs from here, not from the start of the attempt
        if (stopped(gen, f.frameId) != null) return PlaceOutcome.DROPPED
        if (res == null || !res.delivered) return fallback(hits, f.frameId, gen, cardEpoch)
        val choice = res.output.choice
        if (choice.equals(NONE, ignoreCase = true)) return PlaceOutcome.NONE
        val idx = choice.toIntOrNull()?.takeIf { it in 1..hits.size && it.toString() == choice } ?: return PlaceOutcome.CONTRACT
        if (res.output.confidence < MIN_CONFIDENCE) return PlaceOutcome.LOW_CONFIDENCE
        val place = hits[idx - 1].first
        val parent = place.parentId?.let { pid -> found.places.firstOrNull { it.id == pid }?.name }
        val saved = saveMemory(PlaceMemoryRequest(f.frameId, place, parent, res.output.confidence, res.delivered, gen))
        if (!saved) return PlaceOutcome.DROPPED
        // A zone that became active while the memory was being saved: the memory goes again and no card is shown.
        if (stopped(gen, f.frameId) != null) { rollback(f.frameId, gen); return PlaceOutcome.DROPPED }
        if (!publish(PlaceCards.photo(place, f.frameId), cardEpoch, gen, f.frameId)) { rollback(f.frameId, gen); return PlaceOutcome.DROPPED }
        return PlaceOutcome.MATCHED
    }

    /** Takes back the memory of a photo whose card was not (or may not be) shown because a zone, a Forget or a wipe came first. Best effort: Forget and Delete all remove it on their own. */
    private suspend fun rollback(frameId: String, gen: Long) {
        if (generation() != gen) return // Delete all already removed everything
        try { deleteMemory(frameId) } catch (c: CancellationException) { throw c } catch (_: Exception) { }
    }

    /**
     * Shows [card] only if nothing withdrew cards since the attempt began and the zone, switch, wipe and forget fences still hold at the moment of publication; then asks the zone once more and
     * withdraws the card if a zone began in between. False = no card (the caller drops the attempt).
     */
    private suspend fun publish(card: PlaceCard, epoch: Long, gen: Long, frameId: String): Boolean {
        val h = cards
        val out = if (h != null) h.publishIf(card, epoch) { stopped(gen, frameId) == null } else if (stopped(gen, frameId) == null) { onCard(card); true } else false
        if (!out) return false
        if (zoneBlocked()) { h?.withdraw(frameId, card.placeId); return false }
        return true
    }

    /** The nearest place's card when it is unique within [NEARBY_M] (edge distance); otherwise nothing. */
    private suspend fun fallback(hits: List<Pair<PlaceEntity, Double>>, frameId: String, gen: Long, epoch: Long): PlaceOutcome {
        val first = hits.firstOrNull() ?: return PlaceOutcome.NOTHING_NEARBY
        if (gap(first.first, first.second) > NEARBY_M) return PlaceOutcome.NOTHING_NEARBY
        if (hits.size > 1 && gap(hits[1].first, hits[1].second) <= NEARBY_M) return PlaceOutcome.NOTHING_NEARBY
        if (!publish(PlaceCards.nearby(first.first, gap(first.first, first.second), frameId), epoch, gen, frameId)) return PlaceOutcome.DROPPED
        return PlaceOutcome.NEARBY
    }

    private fun gap(p: PlaceEntity, centreM: Double) = (centreM - p.radiusM).coerceAtLeast(0.0)

    private fun remember(id: String) { handled.addLast(id); while (handled.size > HANDLED_MAX) handled.removeFirst() }

    companion object {
        const val RECENT_MS = 90_000L
        const val SKEW_MS = 30_000L
        const val MAX_FIX_AGE_MS = 60_000L
        const val MAX_FIX_ACCURACY_M = 50f
        const val MIN_CALL_GAP_MS = 20_000L
        const val DEDUPE_MS = 120_000L
        const val DEDUPE_M = 30.0
        const val NEARBY_M = 100.0
        const val MAX_LIST = 16
        const val MIN_CONFIDENCE = 0.5f
        const val NONE = "NONE"
        private const val HANDLED_MAX = 32
    }
}
