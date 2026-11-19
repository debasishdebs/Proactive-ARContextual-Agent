package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.place.Geo
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.memory.place.ZoneFenced
import com.rokid.context.memory.place.ZoneTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
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
data class PlaceMemoryRequest(
    val frameId: String, val place: PlaceEntity, val parentName: String?, val confidence: Float, val delivered: Boolean, val generation: Long,
    /** Asked INSIDE the memory transaction, after the rows are written and before the commit: false aborts the transaction (nothing becomes durable). */
    val allowed: () -> Boolean = { true },
)

/**
 * The durable record of place memories that may exist without a card (they are written HIDDEN and stay unreadable until the card is out): marked BEFORE the commit, cleared once the attempt ended with its card published (the memory stays) or the memory
 * was taken back. Whatever is still marked (a rollback that failed, a process death in between) is deleted by the next sweep.
 */
interface PlaceMemoryLedger {
    fun mark(frameId: String)
    fun clear(frameId: String)
    fun pending(): Set<String>
}

class InMemoryPlaceLedger : PlaceMemoryLedger {
    private val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override fun mark(frameId: String) { ids.add(frameId) }
    override fun clear(frameId: String) { ids.remove(frameId) }
    override fun pending(): Set<String> = ids.toSet()
}

enum class PlaceOutcome { DISABLED, ZONE, NO_FRAME, STALE_FRAME, SEEN, NO_FIX, STALE_FIX, NO_CANDIDATES, DUPLICATE, RATE_LIMITED, BUSY, MATCHED, NONE, CONTRACT, LOW_CONFIDENCE, NEARBY, NOTHING_NEARBY, DROPPED, FAILED, NOT_ADMITTED }

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
 * set is a contract failure and counts as NONE. Offline or after a GENUINE failed call (provider or network; never a power/thermal/policy denial, an invalid reply, NONE or low confidence: G1), a fallback card shows the single nearest place within [NEARBY_M] with "nearby · not checked". The card text is
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
    /** Roaming data saver: false = the phone is online but a photo upload is not allowed on this network. Then no call is made and the same pack-based nearby card as offline is shown. Counted by the owner of the lambda. */
    private val photoAllowed: () -> Boolean = { true },
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
    /** THE zone fence: the attempt runs in its cancellable job and every side effect goes through it. */
    private val fence: ZoneFence = ZoneFence(),
    private val ledger: PlaceMemoryLedger = InMemoryPlaceLedger(),
    /**
     * Makes the memory of [frameId] readable (it is written hidden, see [PlaceMemory.save]) once its card is out; false = it stays hidden and marked, and recovery deletes it.
     * Asked with the wipe generation of the attempt.
     */
    private val reveal: suspend (frameId: String, generation: Long) -> Boolean = { _, _ -> true },
    /** A take-back gave up for now (the mark stays): schedules the independent recovery ([PlaceRecovery]); the hidden memory is not readable meanwhile. */
    private val onRollbackFailed: () -> Unit = {},
    /**
     * S2: the stored zones, for the geometric judgement of THE FIX ITSELF ([com.rokid.context.core.model.zone.ZoneEvaluator.blocksPoint]); null = unreadable (blocks). The controller's earlier OUTSIDE reading can be
     * up to 15 min old, so a fix near or inside a zone is refused here whatever the controller last said. Production passes the real list (no default there); this default only serves the pure-flow tests.
     */
    private val zones: suspend () -> List<com.rokid.context.core.model.zone.PauseZone>? = { emptyList() },
) {
    @Volatile private var closed = false
    @Volatile private var job: Job? = null
    private val handled = ArrayDeque<String>()

    /**
     * The decision record of the attempt in flight, logged as ONE `place attempt ...` line when it ends. Only ids (`wd:`, `osm:`, `user:<uuid>`), counts, buckets, durations and fixed reason tokens:
     * never a name, note, summary, coordinate, photo or frame id. One attempt runs at a time (the single-flight [PlaceFlight]), so a field is enough.
     */
    private class Trace(val startedAt: Long) {
        var reason = "-"; var call = "none"; var online = "-"; var cell = "-"; var cand = 0; var ids = "-"
        var frameAgeS = -1L; var fixAgeB = "-"; var accB = "-"; var candMs = -1L; var callMs = -1L
        var chosen = "-"; var distB = "-"; var confPct = -1; var dedupeAgeS = -1L; var dedupeDistB = "-"; var callGapS = -1L
        var card = "none"; var cardWhy = "-"; var reveal = "-"; var err = "-"
    }
    @Volatile private var tr = Trace(0)

    /** A frame was stored. Cheap and non-blocking; the work runs on [scope]. */
    fun poke() {
        if (closed || !enabled()) return
        if (!flight.busy.compareAndSet(false, true)) { finish(PlaceOutcome.BUSY, Trace(elapsed()).also { it.reason = "single_flight" }); return }
        val j = fence.launch(scope) { run() }
        job = j
        j.invokeOnCompletion { flight.busy.set(false) }
    }

    fun close() { closed = true; job?.cancel() }

    private fun live(gen: Long) = !closed && enabled() && pipelineOn() && generation() == gen

    private fun finish(o: PlaceOutcome, t: Trace = tr) {
        stats(o)
        log("place outcome=${o.name}")
        val totalMs = (elapsed() - t.startedAt).coerceAtLeast(0L)
        log("place attempt outcome=${o.name} reason=${t.reason} call=${t.call} online=${t.online} cell=${t.cell} cand=${t.cand} ids=${t.ids} frameAgeS=${t.frameAgeS} fixAgeB=${t.fixAgeB} accB=${t.accB} candMs=${t.candMs} callMs=${t.callMs} totalMs=$totalMs chosen=${t.chosen} distB=${t.distB} confPct=${t.confPct} dedupeAgeS=${t.dedupeAgeS} dedupeDistB=${t.dedupeDistB} callGapS=${t.callGapS} card=${t.card} cardWhy=${t.cardWhy} reveal=${t.reveal} err=${t.err}")
    }

    private suspend fun run() {
        val ticket = coroutineContext[ZoneTicket] ?: fence.ticket()
        tr = Trace(elapsed())
        try {
            sweep()
            finish(attempt(ticket))
        } catch (c: CancellationException) {
            if (c is ZoneFenced || !ticket.isOpen()) { tr.reason = "cancelled_by_zone_fence"; finish(PlaceOutcome.ZONE) } else { tr.reason = "cancelled"; tr.err = "CancellationException"; finish(PlaceOutcome.FAILED) }
            throw c
        } catch (e: Exception) { tr.reason = "exception"; tr.err = e.javaClass.simpleName; finish(PlaceOutcome.FAILED) }
    }

    /** Takes back every place memory a failed rollback left marked. Runs to the end even if the attempt is cancelled; a failure leaves the mark for the next sweep. */
    suspend fun sweep() {
        val ids = ledger.pending()
        if (ids.isEmpty()) return
        withContext(NonCancellable) { for (id in ids) takeBack(id) }
    }

    /** Deletes the memory of [frameId] and clears its mark; false (mark kept) when the deletion failed. */
    private suspend fun takeBack(frameId: String): Boolean {
        repeat(ROLLBACK_TRIES) { n ->
            try { deleteMemory(frameId); ledger.clear(frameId); return true } catch (c: CancellationException) { if (c !is ZoneFenced) throw c } catch (_: Exception) { }
            if (n < ROLLBACK_TRIES - 1) delay(ROLLBACK_RETRY_MS)
        }
        try { onRollbackFailed() } catch (_: Exception) { }
        return false
    }

    /**
     * Why work must stop now, or null. Asked after EVERY suspension point (the suspending parts first) and the synchronous fence LAST, so that nothing suspends between the final check and
     * the action it guards (a fix, a fetch, an upload, a write, a card).
     */
    private suspend fun stopped(gen: Long, ticket: ZoneTicket, frameId: String? = null): PlaceOutcome? {
        if (!live(gen)) { tr.reason = notLiveReason(gen); return PlaceOutcome.DISABLED }
        if (zoneBlocked()) { tr.reason = "zone_blocked"; return PlaceOutcome.ZONE }
        if (frameId != null && store.tombstoned(frameId)) { tr.reason = "tombstoned"; return PlaceOutcome.DROPPED }
        return if (!ticket.isOpen() || !live(gen)) { tr.reason = if (!ticket.isOpen()) "fence_closed" else notLiveReason(gen); PlaceOutcome.ZONE } else null
    }

    /** Which of the liveness conditions failed (log only). */
    private fun notLiveReason(gen: Long) = when {
        closed -> "closed"
        !enabled() -> "switch_off"
        !pipelineOn() -> "pipeline_off"
        generation() != gen -> "wipe_generation_changed"
        else -> "not_live"
    }

    private suspend fun attempt(ticket: ZoneTicket): PlaceOutcome {
        val gen = generation()
        val cardEpoch = cards?.epoch() ?: 0L
        // The zone gate comes first: nothing below runs while a zone is active, approaching or unreadable.
        stopped(gen, ticket)?.let { return it }
        val now = clock()
        val f = store.newestDwell(now - RECENT_MS) ?: run { tr.reason = "no_dwell_frame"; return PlaceOutcome.NO_FRAME }
        val cap = f.capturedAtMs
        tr.frameAgeS = if (cap == null) -1L else (now - cap) / 1000
        if (cap == null || cap < now - RECENT_MS || cap > now + SKEW_MS || store.tombstoned(f.frameId)) {
            tr.reason = if (cap == null) "no_capture_time" else if (cap < now - RECENT_MS) "frame_too_old" else if (cap > now + SKEW_MS) "frame_in_future" else "tombstoned"
            return PlaceOutcome.STALE_FRAME
        }
        if (f.frameId in handled) { tr.reason = "already_handled"; return PlaceOutcome.SEEN }
        stopped(gen, ticket, f.frameId)?.let { return it } // before the fix is asked for
        val fx = fix() ?: run { tr.reason = "fix_unavailable"; return PlaceOutcome.NO_FIX }
        tr.fixAgeB = bucketAgeMs(fx.ageMs); tr.accB = bucketAccuracyM(fx.accuracyM)
        if (fx.ageMs < 0 || fx.ageMs > MAX_FIX_AGE_MS || fx.accuracyM.isNaN() || fx.accuracyM > MAX_FIX_ACCURACY_M || !fx.lat.isFinite() || !fx.lon.isFinite()) {
            tr.reason = if (fx.ageMs < 0) "fix_age_negative" else if (fx.ageMs > MAX_FIX_AGE_MS) "fix_too_old" else if (!fx.lat.isFinite() || !fx.lon.isFinite()) "fix_not_finite" else "fix_inaccurate"
            return PlaceOutcome.STALE_FIX
        }
        // S2: the fix is judged against the zones itself (box + buffer + approach margin, accuracy against the user, unreadable zones block), before the cell fetch or any upload.
        if (com.rokid.context.core.model.zone.ZoneEvaluator.blocksPoint(try { zones() } catch (c: CancellationException) { throw c } catch (e: Exception) { null }, fx.lat, fx.lon, fx.accuracyM)) { tr.reason = "zone_fix"; return PlaceOutcome.ZONE }
        stopped(gen, ticket, f.frameId)?.let { return it } // before the pack may fetch
        remember(f.frameId)
        val online = networkUp()
        tr.online = online.toString()
        val packAt = elapsed()
        val found = candidates(fx.lat, fx.lon, online)
        tr.candMs = (elapsed() - packAt).coerceAtLeast(0L); tr.cell = found.outcome.name; tr.cand = found.places.size
        if (found.places.isEmpty()) { tr.reason = "pack_empty"; return PlaceOutcome.NO_CANDIDATES }
        flight.lastAttempt?.let { a ->
            val d = Geo.distanceM(fx.lat, fx.lon, a.lat, a.lon)
            tr.dedupeAgeS = (now - a.at) / 1000; tr.dedupeDistB = bucketM(d)
            if (now - a.at in 0..DEDUPE_MS && d <= DEDUPE_M) { tr.reason = "same_place_recently"; return PlaceOutcome.DUPLICATE }
        }
        val hits = cutKeepingPins(found.places.map { it to Geo.distanceM(fx.lat, fx.lon, it.lat, it.lon) })
        tr.cand = hits.size; tr.ids = idList(hits.map { it.first.id })
        // The spacing is judged NOW (after any lengthy fetch), against the end of the previous call, on the monotonic clock.
        val sinceCall = elapsed() - flight.lastCallAt
        tr.callGapS = if (flight.lastCallAt < 0) -1L else (sinceCall / 1000).coerceAtLeast(0L)
        val callOk = online && sinceCall >= MIN_CALL_GAP_MS
        if (online && !callOk) { tr.reason = "call_gap_${MIN_CALL_GAP_MS / 1000}s"; return PlaceOutcome.RATE_LIMITED }
        flight.lastAttempt = PlaceFlight.Attempt(fx.lat, fx.lon, now)
        stopped(gen, ticket, f.frameId)?.let { return it } // before the upload (the router asks again inside its permit)
        if (!callOk) { tr.reason = "offline"; tr.call = "skipped_offline"; return fallback(hits, f.frameId, gen, cardEpoch, ticket) } // offline: the only no-call fallback (G1)
        // Roaming data saver: online, but photos may not go out on this network (mobile, roaming, unknown). Same path as offline: the name-and-distance card from the pack, no identification, no photo sent.
        if (!photoAllowed()) { tr.reason = "data_saver"; tr.call = "skipped_data_saver"; return fallback(hits, f.frameId, gen, cardEpoch, ticket) }
        // G1 (ruling 2026-11-17): a power, thermal or policy denial is NOT a failure of the match and never becomes a nearby identification: no call, no card.
        if (!admit()) { tr.reason = "not_admitted"; tr.call = "skipped_not_admitted"; return PlaceOutcome.NOT_ADMITTED }
        flight.lastCallAt = elapsed()
        val lines = hits.mapIndexed { i, h -> "${i + 1}. ${h.first.name} | ${h.first.kind.replace('_', ' ')}" }
        if (!ticket.isOpen()) { tr.reason = "fence_closed"; return PlaceOutcome.ZONE } // the last check; nothing suspends between it and the call, which the fence's job cancels if the zone comes later
        // G1: only a GENUINE call failure (the provider or the network failed) may fall back to the nearby card; a policy denial, a forgotten photo or an invalid reply is no identification.
        var genuineFailure = false
        tr.call = "made"
        val callAt = elapsed()
        val res = try { match(f.frameId, lines) } catch (c: CancellationException) { throw c } catch (e: Exception) { genuineFailure = isGenuineCallFailure(e); tr.err = e.javaClass.simpleName; null }
        flight.lastCallAt = elapsed() // the permit wait and the call are over: the gap runs from here, not from the start of the attempt
        tr.callMs = (flight.lastCallAt - callAt).coerceAtLeast(0L)
        if (stopped(gen, ticket, f.frameId) != null) return PlaceOutcome.DROPPED
        if (res == null) { tr.reason = if (genuineFailure) "call_failed_genuine" else "call_failed_or_photo_gone"; return if (genuineFailure) fallback(hits, f.frameId, gen, cardEpoch, ticket) else PlaceOutcome.FAILED }
        if (!res.delivered) { tr.reason = "photo_not_delivered"; return PlaceOutcome.FAILED } // the photo was not proven to have reached the model: not a photo match, not a nearby guess either
        val choice = res.output.choice
        tr.confPct = (res.output.confidence * 100).toInt()
        if (choice.equals(NONE, ignoreCase = true)) { tr.reason = "model_none"; return PlaceOutcome.NONE }
        val idx = choice.toIntOrNull()?.takeIf { it in 1..hits.size && it.toString() == choice } ?: run { tr.reason = "choice_out_of_range"; return PlaceOutcome.CONTRACT }
        tr.chosen = hits[idx - 1].first.id; tr.distB = bucketM(hits[idx - 1].second)
        if (res.output.confidence < MIN_CONFIDENCE) { tr.reason = "confidence_below_${(MIN_CONFIDENCE * 100).toInt()}"; return PlaceOutcome.LOW_CONFIDENCE }
        val place = hits[idx - 1].first
        val parent = place.parentId?.let { pid -> found.places.firstOrNull { it.id == pid }?.name }
        return commit(place, parent, res, f.frameId, gen, cardEpoch, ticket)
    }

    /**
     * Writes the memory and shows the card, as one unit. The mark in the [ledger] is durable BEFORE the commit and cleared only when the card is out (the memory stays) or the memory is taken
     * back, so a rollback that fails, a cancellation or a process death in between leaves a mark the next [sweep] acts on. The transaction itself refuses to commit once the fence closed.
     */
    private suspend fun commit(place: PlaceEntity, parent: String?, res: PlaceMatchResult, frameId: String, gen: Long, epoch: Long, ticket: ZoneTicket): PlaceOutcome {
        var keep = false
        ledger.mark(frameId)
        try {
            val saved = saveMemory(PlaceMemoryRequest(frameId, place, parent, res.output.confidence, res.delivered, gen, allowed = { ticket.isOpen() && live(gen) }))
            if (!saved) { tr.reason = "memory_not_saved"; tr.card = "dropped"; tr.cardWhy = "memory_not_saved"; return PlaceOutcome.DROPPED }
            // A zone that became active while the memory was being saved (after its last in-transaction check): the memory goes again and no card is shown.
            if (stopped(gen, ticket, frameId) != null) { tr.card = "dropped"; tr.cardWhy = tr.reason; return PlaceOutcome.DROPPED }
            if (!publish(PlaceCards.photo(place, frameId), epoch, gen, frameId, ticket)) return PlaceOutcome.DROPPED
            keep = true
            tr.reason = "matched"
            return PlaceOutcome.MATCHED
        } finally {
            withContext(NonCancellable) { if (keep) show(frameId, gen) else rollback(frameId, gen) }
        }
    }

    /**
     * The card is out: the memory (written hidden) becomes readable and the mark is cleared. If that fails the mark stays and the memory stays hidden, so it is taken back by recovery
     * rather than left in an unknown state.
     */
    private suspend fun show(frameId: String, gen: Long) {
        val shown = try { reveal(frameId, gen) } catch (c: CancellationException) { throw c } catch (_: Exception) { false }
        tr.reveal = if (shown) "ok" else "failed"
        if (shown) ledger.clear(frameId) else try { onRollbackFailed() } catch (_: Exception) { }
    }

    /** Takes back the memory of a photo whose card was not (or may not be) shown because a zone, a Forget or a wipe came first. Never skipped by cancellation; a failure stays marked for [sweep]. */
    private suspend fun rollback(frameId: String, gen: Long) {
        if (generation() != gen) { ledger.clear(frameId); return } // Delete all already removed everything
        takeBack(frameId)
    }

    /**
     * Shows [card] only if nothing withdrew cards since the attempt began and the fence, switch, wipe and forget fences still hold: the fence check, the switch/wipe check and the holder's
     * epoch check are made together, under the fence's lock, in the same synchronous step as the publication. False = no card (the caller drops the attempt).
     */
    private suspend fun publish(card: PlaceCard, epoch: Long, gen: Long, frameId: String, ticket: ZoneTicket): Boolean {
        if (stopped(gen, ticket, frameId) != null) { tr.card = "dropped"; tr.cardWhy = tr.reason; return false } // the suspending checks (zone records, tombstone) come first; the synchronous ones below come last
        val h = cards
        val shown = fence.act(ticket) { if (!live(gen)) false else if (h != null) h.publishIf(card, epoch) else { onCard(card); true } } == true
        if (shown) { tr.card = "shown"; tr.cardWhy = "-" } else { tr.card = "dropped"; tr.cardWhy = "withdrawn_or_fenced"; tr.reason = "card_dropped" }
        return shown
    }

    /** The nearest place's card when it is unique within [NEARBY_M] (edge distance); otherwise nothing. */
    private suspend fun fallback(hits: List<Pair<PlaceEntity, Double>>, frameId: String, gen: Long, epoch: Long, ticket: ZoneTicket): PlaceOutcome {
        val first = hits.firstOrNull() ?: run { tr.reason = "no_hits"; return PlaceOutcome.NOTHING_NEARBY }
        tr.chosen = first.first.id; tr.distB = bucketM(first.second)
        if (gap(first.first, first.second) > NEARBY_M) { tr.reason = "nearest_beyond_${NEARBY_M.toInt()}m"; tr.chosen = "-"; return PlaceOutcome.NOTHING_NEARBY }
        if (hits.size > 1 && gap(hits[1].first, hits[1].second) <= NEARBY_M) { tr.reason = "ambiguous_second_within_${NEARBY_M.toInt()}m"; tr.chosen = "-"; return PlaceOutcome.NOTHING_NEARBY }
        if (!publish(PlaceCards.nearby(first.first, gap(first.first, first.second), frameId), epoch, gen, frameId, ticket)) return PlaceOutcome.DROPPED
        tr.reason = "nearby_card"
        return PlaceOutcome.NEARBY
    }

    /** A provider or network failure (transient, auth, rejected): the only thing that may show the "nearby · not checked" card online. Policy (NOT_SET), invalid output and anything unknown do not. */
    private fun isGenuineCallFailure(e: Exception): Boolean =
        e is java.io.IOException || (e is com.rokid.context.ai.ModelCallException && e.kind in GENUINE_KINDS)

    private fun gap(p: PlaceEntity, centreM: Double) = (centreM - p.radiusM).coerceAtLeast(0.0)

    private fun remember(id: String) { handled.addLast(id); while (handled.size > HANDLED_MAX) handled.removeFirst() }

    companion object {
        /** Log buckets: never an exact position. Distances are metres to a candidate's centre. */
        internal fun bucketM(m: Double): String = when { m < 25 -> "lt25m"; m < 50 -> "lt50m"; m < 100 -> "lt100m"; m < 250 -> "lt250m"; m < 500 -> "lt500m"; m < 1000 -> "lt1km"; m < 5000 -> "lt5km"; else -> "ge5km" }
        internal fun bucketAgeMs(ms: Long): String = when { ms < 0 -> "negative"; ms <= 5_000 -> "le5s"; ms <= 15_000 -> "le15s"; ms <= 30_000 -> "le30s"; ms <= 60_000 -> "le60s"; else -> "gt60s" }
        internal fun bucketAccuracyM(m: Float): String = when { m.isNaN() -> "nan"; m <= 10f -> "le10m"; m <= 25f -> "le25m"; m <= 50f -> "le50m"; m <= 100f -> "le100m"; else -> "gt100m" }
        /** At most 12 ids (nearest first) and how many more: keeps the line under the 1 KiB cap. */
        internal fun idList(ids: List<String>): String = if (ids.isEmpty()) "-" else ids.take(12).joinToString(",") + if (ids.size > 12) ",+${ids.size - 12}" else ""

        /** The FINAL model list (after the sub-place expansion): nearest edge first, cut to [MAX_LIST], but the nearest [Geo.PIN_SLOTS] user pins always keep a place (they replace the farthest non-pins). */
        internal fun cutKeepingPins(all: List<Pair<PlaceEntity, Double>>): List<Pair<PlaceEntity, Double>> {
            val order = compareBy<Pair<PlaceEntity, Double>>({ (it.second - it.first.radiusM).coerceAtLeast(0.0) }, { it.second }, { it.first.id })
            val sorted = all.sortedWith(order)
            val head = sorted.take(MAX_LIST)
            val pins = sorted.filter { UserPlace.isUserId(it.first.id) }.take(Geo.PIN_SLOTS)
            val missing = pins.filter { p -> head.none { it.first.id == p.first.id } }
            if (missing.isEmpty()) return head
            val keep = head.toMutableList()
            for (pin in missing) { val at = keep.indexOfLast { !UserPlace.isUserId(it.first.id) }; if (at >= 0) keep.removeAt(at) else break; keep += pin }
            return keep.sortedWith(order)
        }

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
        private val GENUINE_KINDS = setOf(com.rokid.context.ai.FailureKind.TRANSIENT, com.rokid.context.ai.FailureKind.AUTH, com.rokid.context.ai.FailureKind.REJECTED)
        internal const val ROLLBACK_TRIES = 3
        internal const val ROLLBACK_RETRY_MS = 200L
        private const val HANDLED_MAX = 32
    }
}
