package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.core.net.Backend
import com.rokid.context.core.net.BackendThresholds
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.ResolveReason
import com.rokid.context.memory.place.CardDelivery
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/** One recent card of the history, as the sampler seeds its in-memory dedupe and visits at a process start: place, wall time, kind name and cluster members (no position, no text). */
data class PlaceSeed(val placeId: String, val at: Long, val kind: String = "", val members: List<String> = emptyList(), val delivery: String = "")

/** A fix as the sampler needs it: where, how old and how accurate. Held in a local only; never stored or logged. */
data class PlaceFix(val lat: Double, val lon: Double, val ageMs: Long, val accuracyM: Float)

/** A dwell photo: its id and when it was captured (phone clock, null = unknown). */
data class PlaceFrame(val frameId: String, val capturedAtMs: Long?, val motion: String? = null, val sessionId: String? = null)

interface PlaceFrameStore {
    /** The newest dwell photo received at or after [sinceReceivedMs]. */
    suspend fun newestDwell(sinceReceivedMs: Long): PlaceFrame?
    suspend fun tombstoned(frameId: String): Boolean
    /** True when a WALKING or VEHICLE frame was received at or after [sinceReceivedMs] (F2 fix reuse). A query only. */
    suspend fun movedSince(sinceReceivedMs: Long): Boolean = false
}

/** The model's answer and whether the router proved the photo reached it. */
data class PlaceMatchResult(val output: PlaceMatchOutput, val delivered: Boolean, /** Layer 2 counts only (never names): reference photos sent, candidates that had one. */ val refImages: Int = 0, val candWithRefs: Int = 0)

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

/**
 * Which backend the model resolver picked for this attempt, and why ([backend] null = none: [reason] says why). Only [Backend.ONLINE] makes a call (the offline slot is filled by slice 1.4.5). [label] is the
 * resolver's fixed, content-free label (for example `remote:AZURE_OPENAI`).
 */
data class BackendChoice(val backend: Backend?, val reason: ResolveReason, val mode: ModelPrivacy = ModelPrivacy.AUTO, val label: String = "-") {
    /** Offline only and no on-device model: the typed "needs an on-device model" state. */
    val needsOnDeviceModel: Boolean get() = reason == ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL

    companion object {
        val ONLINE = BackendChoice(Backend.ONLINE, ResolveReason.OK)
        val NO_NETWORK = BackendChoice(null, ResolveReason.NO_NETWORK_NO_LOCAL_MODEL)
    }
}

/** Why [PlaceSampler.retryRecent] was called: a pin was just saved, or the place pack of the cell arrived after a miss. */
enum class RetryReason { PIN_CREATED, CELL_RETRY }

/** "How to check the photo" (Settings > Place Guide): Automatic = on the phone first, then online when the network and Data saver allow, then the offline guess; Online only = never the offline guess; On the phone only = never online. */
enum class CheckMode {
    AUTOMATIC, ONLINE_ONLY, PHONE_ONLY;
    companion object { fun parse(s: String?): CheckMode = values().firstOrNull { it.name == s } ?: AUTOMATIC }
}

/**
 * The layered-confirmation policy of ONE attempt (read per attempt). The default is the LEGACY behaviour (no Layer 1 hook is asked for unless a [PhotoLayer] is given, a location-only nearby or pin card may go to the
 * glasses, every check mode behaves as Automatic): the older tests and any caller without the new settings are unchanged. Production passes the real settings ([PlaceGuideSettings.policy]).
 */
data class PlacePolicy(
    /** "Cards on glasses: only when the photo matches". ON = a location-only match is held in the history with a HELD_* reason, never sent to the glasses. */
    val confirmedOnly: Boolean = false,
    val checkMode: CheckMode = CheckMode.AUTOMATIC,
    /** "Also show pin cards when nearby": only meaningful with [confirmedOnly]. */
    val pinCardsNearby: Boolean = false,
    /** Developer layer switches (default ON). */
    val l1: Boolean = true,
    val l2: Boolean = true,
    /** The nine Layer 1 numbers in force: only the FLOOR of a kind is read here (the loop guard of B3: L2 confirmed AND the Layer 1 score at or above a real floor makes a glance a reference). */
    val photoMatch: com.rokid.context.memory.retrieval.PhotoMatchConfig = com.rokid.context.memory.retrieval.PhotoMatchConfig.DEFAULT,
    /** "Also show places that have no photo, by GPS only" (default OFF): with [confirmedOnly], nearby places with NO reference photo are exempt from the hold and get the old nearby card. */
    val noPhotoByGps: Boolean = false,
) {
    /** A pin-first (location only) card is held instead of shown. */
    val holdPins: Boolean get() = confirmedOnly && !pinCardsNearby
}

/** What the Layer 1 hook gives back: a decision of [PhotoConfirm] (with how many frames the query used), or a fixed skip token (no vector, no reference reader, hot phone, timeout...). Never an id or a score in a token. */
sealed class PhotoLayerOutcome {
    class Skipped(val token: String) : PhotoLayerOutcome()
    class Decided(val result: com.rokid.context.memory.retrieval.PhotoConfirm.Result, val frames: Int) : PhotoLayerOutcome()
}

enum class PlaceOutcome { DISABLED, ZONE, NO_FRAME, STALE_FRAME, SEEN, NO_FIX, STALE_FIX, NO_CANDIDATES, DUPLICATE, RATE_LIMITED, BUSY, MATCHED, NONE, CONTRACT, LOW_CONFIDENCE, NEARBY, MAYBE, NOTHING_NEARBY, DROPPED, FAILED, NOT_ADMITTED, PIN_FIRST, HELD, IMAGE }

/**
 * Process-wide single-flight and spacing state shared by every [PlaceSampler] (the hub makes a new one at each restart): one run at a time, one model call per [PlaceSampler.MIN_CALL_GAP_MS],
 * and the last attempt (position and time, kept in memory for [PlaceSampler.DEDUPE_MS] only) for the same-place skip.
 */
class PlaceFlight {
    internal val busy = AtomicBoolean(false)
    @Volatile internal var lastCallAt = Long.MIN_VALUE / 2
    @Volatile internal var lastAttempt: Attempt? = null
    internal data class Attempt(val lat: Double, val lon: Double, val at: Long)

    /** F2: the newest fix that passed the strict gate and the zone check, in memory only: where, how accurate, when (monotonic), in which capture session, and when its frames were received (for [PlaceFrameStore.movedSince]). */
    internal data class GoodFix(val lat: Double, val lon: Double, val accM: Float, val atElapsed: Long, val sessionId: String?, val receivedAtMs: Long, val generation: Long)
    @Volatile internal var good: GoodFix? = null

    /** F3: the last weak answer (NONE, CONTRACT, LOW_CONFIDENCE): where, when and how many in a row. In memory only. */
    internal data class Weak(val lat: Double, val lon: Double, val at: Long, val streak: Int)
    @Volatile internal var weak: Weak? = null

    /** F3: where and when a place last had a card. [lat] is NaN for a row seeded from the history after a restart (position unknown: suppresses by place and time alone). */
    internal data class CardAt(val lat: Double, val lon: Double, val at: Long)
    private val lastCard = LinkedHashMap<String, CardAt>()
    @Volatile internal var seeded = false
    /** F1: the visits of pin-first cards (in memory only). */
    internal val visits = PinVisits()

    /**
     * ONE atomic visit/card reservation shared by the sampler and [PlaceTextMatch]: the check (a live visit, a card of the place under 30 min ago per [shownHere], or another attempt holding the pin) and the claim happen
     * under one lock, so two overlapping attempts can never both card the same pin. The holder calls [unreserve] when its card is published and its visit begun (or when it gave up).
     */
    private val gate = Any()
    private val reserved = HashSet<String>()
    internal fun reserve(ids: Collection<String>, now: Long, shownHere: (String, CardAt?) -> Boolean = { _, _ -> false }): Boolean = synchronized(gate) {
        if (ids.any { it in reserved } || visits.has(ids, now) || ids.any { shownHere(it, cardAt(it)) }) false else { reserved += ids; true }
    }
    internal fun unreserve(ids: Collection<String>) = synchronized(gate) { reserved -= ids.toSet() }

    internal fun noteCard(placeId: String, c: CardAt) = synchronized(lastCard) {
        lastCard.remove(placeId); lastCard[placeId] = c
        lastCard.entries.removeAll { c.at - it.value.at > PlaceSampler.ONCE_PER_PLACE_MS }
        while (lastCard.size > 64) lastCard.remove(lastCard.keys.first())
    }
    internal fun cardAt(placeId: String): CardAt? = synchronized(lastCard) { lastCard[placeId] }
    internal fun clearCards() = synchronized(lastCard) { lastCard.clear() }
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
    /**
     * The model resolver's pick for this attempt (setting first, then network). The default is today's rule, so the pure-flow tests and any caller without a resolver behave as before: online exactly when [networkUp].
     * Production passes the resolver. Under Offline only the pick is never [Backend.ONLINE], so no pack fetch and no model call is made.
     */
    private val backend: suspend () -> BackendChoice = { if (networkUp()) BackendChoice.ONLINE else BackendChoice.NO_NETWORK },
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
    /**
     * Shadow scoring (1.4.4 slice A), null = absent. Asked only when [shadowEnabled] and a model call is about to be made, on [shadowScope]; its result only ever adds fields to the `place attempt` line.
     * The attempt never waits for it before the call result, the call timing, the memory or the card; after the card it waits at most what is left of [SHADOW_BUDGET_MS]. A failure or a miss is logged `shadow=err`;
     * it never changes an outcome, a card or the call.
     */
    private val shadow: (suspend (frameId: String, hits: List<Pair<PlaceEntity, Double>>, live: () -> Boolean) -> ShadowOutcome)? = null,
    /** The Developer flag, read SYNCHRONOUSLY before anything is launched: false = no hook runs, nothing is launched and the log line carries no shadow field at all. */
    private val shadowEnabled: () -> Boolean = { false },
    /** Called once when an attempt ends (any way): the owner drops what it shared between the model call and the shadow (the one loaded photo). */
    private val onAttemptEnd: () -> Unit = {},
    /** Detached from the attempt on purpose: an embed that cannot be cancelled must not hold the attempt (its late result is dropped). */
    private val shadowScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** F6: the card history sink (S1 seam; the default drops every event). Holds no coordinates. */
    private val history: suspend (PlaceHistoryEvent) -> Unit = {},
    /** The Developer flag of the history (production: `placeCardHistory`): false = no row is written and the line says `hist=off`. */
    private val historyOn: () -> Boolean = { true },
    /** CH 6: the history authority (`frame_forget_rev`, wipe generation, fence epoch), captured at the START of an attempt, before the frame, the fix or the pins are read. */
    private val historyAuthority: suspend () -> HistoryAuthority = { HistoryAuthority.NONE },
    /** Developer switches, read synchronously per attempt. Defaults OFF keep every older test unchanged; production passes the real flags. */
    private val pinFirstOn: () -> Boolean = { false },
    private val fixReuseOn: () -> Boolean = { false },
    private val dedupeV2On: () -> Boolean = { false },
    private val retryOn: () -> Boolean = { false },
    /** The user pins among the candidates of an attempt (identity: S5 uses the pins inside `found.places`). */
    private val pinsNear: (List<PlaceEntity>) -> List<PlaceEntity> = { it },
    /** F3: the places that had a card in the last 30 min (place id, time), read once at the first attempt after a process start (position unknown). */
    private val seedRecent: suspend (sinceMs: Long) -> List<PlaceSeed> = { emptyList() },
    /**
     * V2b: the offline place guess, null = absent (the default; production passes it only behind its flags). Asked ONLY on the offline path (no allowed network, or Offline only / data saver) with the candidates the pack gave for the fix
     * (place, distance to its centre in metres). Returns the place to show as "Maybe <place>" or null (no guess). It never throws except for cancellation. Its [OfflineTierResult.token] is the only thing logged.
     */
    private val offlineGuess: (suspend (frameId: String, hits: List<Pair<PlaceEntity, Double>>) -> OfflineTierResult)? = null,
    /**
     * Layer 1 (design 2026-11-27): the photo against the reference photos of the candidates, on the phone, any network state. Null = absent (the default: no Layer 1). Asked once per attempt, after the candidates and the
     * dedupe and before the call gap; it returns a [PhotoLayerOutcome] and never throws except for cancellation. [live] is the attempt's fence.
     */
    private val photoLayer: (suspend (frameId: String, hits: List<Pair<PlaceEntity, Double>>, live: () -> Boolean) -> PhotoLayerOutcome)? = null,
    /** Layer 2 (owner spec 28 Nov): when set, used INSTEAD of [match]: the camera photo plus the reference photos and the name, kind and description of each candidate go in one call. Same result contract as [match]. */
    private val matchPlaces: (suspend (frameId: String, places: List<PlaceEntity>) -> PlaceMatchResult?)? = null,
    /** Of these places, the ids that HAVE any reference photo (seeded thumbnail, pin Moment, glance reference). Null = unknown: the no-photo switch then exempts nothing. */
    private val refPlaceIds: (suspend (places: List<PlaceEntity>) -> Set<String>)? = null,
    /** The policy of the attempt, read once per attempt. */
    private val policy: () -> PlacePolicy = { PlacePolicy() },
    /** Is the offline guess (Layer 3) available at all (its Developer flags, the user's switch and the downloaded model)? Only used to tell "no model" from "no guess" in a held row. Default: as the [offlineGuess] hook exists. */
    private val offlineAvailable: () -> Boolean = { offlineGuess != null },
    /** Test and replay seam: the ids the attempt chose, handed over once when it ends. NEVER logged (a user-pin id is private). */
    internal val chosenSink: (List<String>) -> Unit = {},
) {
    @Volatile private var closed = false
    @Volatile private var job: Job? = null
    private val handled = ArrayDeque<String>()

    /**
     * The decision record of the attempt in flight, logged as ONE `place attempt ...` line when it ends. Only public ids (`wd:`, `osm:`), counts (`pinCand` = how many user pins), buckets, durations and fixed reason tokens (`chosen=pin` for a user pin):
     * never a user-pin id, a name, note, summary, coordinate, photo or frame id. The single-flight [PlaceFlight] is released once the card is decided, so a LATER attempt can start while this one settles its shadow: each attempt creates and logs its OWN Trace ([runAttempt]); [tr] only points at the newest one.
     */
    private class Trace(val startedAt: Long) {
        var reason = "-"; var call = "none"; var online = "-"; var cell = "-"; var cand = 0; var ids = "-"; var pinCand = 0
        var frameAgeS = -1L; var fixAgeB = "-"; var accB = "-"; var candMs = -1L; var callMs = -1L
        var chosen = "-"; var distB = "-"; var confPct = -1; var dedupeAgeS = -1L; var dedupeDistB = "-"; var callGapS = -1L
        var card = "none"; var cardWhy = "-"; var reveal = "-"; var err = "-"
        /** V2b: the offline tier's fixed outcome token and the candidate and margin buckets; null = the tier was not asked (the line is then unchanged). */
        var maybe: String? = null
        var mode = "-"; var backend = "-"; var resolve = "-"; var label = "-"
        /** Layered confirmation (counts and fixed tokens only; null l1 = Layer 1 not asked and the `place layers` line is not written). */
        val layersCard: String get() = if (card == "shown") "shown" else if (held != "-") "held_${held.removePrefix("held_")}" else "none"
        /** B3 eval record inputs: the Layer 1 decision (scores, margin), the check mode token, the Layer 2 answer token, confidence and picked place id. Never logged; they only go into the history row's eval. */
        var l1Res: com.rokid.context.memory.retrieval.PhotoConfirm.Result? = null; var checkMode = "-"; var photoMatch = com.rokid.context.memory.retrieval.PhotoMatchConfig.DEFAULT; var l2Conf: Int? = null; var l2Pick: String? = null; var evalCand: List<String> = emptyList()
        var noPhoto: List<Pair<PlaceEntity, Double>> = emptyList(); var epoch = 0L; var l2Refs = 0; var l2WithRefs = 0
        var l1: String? = null; var l1k = 0; var l1With = 0; var l1No = 0; var l1Score = "na"; var l1Margin = "na"; var l1Cand = 0; var l2 = "-"; var l3 = "-"; var held = "-"; var l1Skip = "-"
        /** F7 fields (default "-"): where the fix came from, how old a reused fix really was, the pin-first decision, the weak back-off window, the retry reason, the history write, the text hit. */
        /** The place ids this attempt chose (a model pick, a pin card, a cluster, the nearest): handed to [chosenSink] ONLY; user-pin ids are never written to the log. */
        var chosenIds: List<String> = emptyList()
        var v2 = false; var fxLat = Double.NaN; var fxLon = Double.NaN; var fxAcc = 0f; var nowAt = 0L
        var cardId: String? = null; var fixSrc = "-"; var fixRealAgeB = "-"; var pinFirst = "-"; var weakWin = "-"; var retry = "-"; var hist = "-"; var textHit = "-"; var authority: HistoryAuthority? = null
        /** null = shadow off (the line is unchanged); "err", "na" or "ok". */
        var shadow: String? = null; var shadowOut: ShadowOutcome? = null; var shadowRank = "na"; var shadowAgrees = "na"
        var shadowPending: kotlinx.coroutines.Deferred<ShadowOutcome?>? = null; var shadowStartedAt = 0L; var shadowChoice: String? = null
        /** The pack (non-pin) candidate ids of the attempt: on the image channel they are not ranked, which is what `shMixed` counts. */
        var shadowPackIds: List<String> = emptyList()
    }
    @Volatile private var tr = Trace(0)

    /** One pending retry request (F4). [delayed] = it is the single delayed re-run after RATE_LIMITED. */
    private class Retry(val reason: RetryReason, val pinId: String?, var delayed: Boolean = false)
    private val pendingRetry = java.util.concurrent.atomic.AtomicReference<Retry?>(null)
    private val delayedPending = AtomicBoolean(false)
    @Volatile private var cellRetryFrame: String? = null

    /** A frame was stored. Cheap and non-blocking; the work runs on [scope]. */
    fun poke() = start(null)

    /**
     * F4: look again at the frame in view after an explicit user act (a pin was just saved) or a pack miss. Like [poke], but the attempt skips the `handled` check, the card dedupe and the weak window once; every other gate
     * (zone, fix, fence, the call gap, [admit]) is unchanged. A busy single-flight queues ONE retry that runs when the current attempt ends; a RATE_LIMITED retry is re-run once at the end of the call gap.
     * For [RetryReason.PIN_CREATED] the attempt goes on only when [pinId] is among the candidates (a pin made far away is no call).
     */
    fun retryRecent(reason: RetryReason, pinId: String? = null) {
        if (closed || !enabled() || !retryOn()) return
        start(Retry(reason, pinId))
    }

    private fun start(retry: Retry?) {
        if (closed || !enabled()) return
        if (!flight.busy.compareAndSet(false, true)) {
            if (retry != null) pendingRetry.set(retry) else finish(PlaceOutcome.BUSY, Trace(elapsed()).also { it.reason = "single_flight" })
            return
        }
        // The single-flight is held until the card is decided, not through the shadow settle (a poke then is not logged BUSY). Released once per poke, by whichever of the two comes first.
        val held = AtomicBoolean(true)
        val release = { if (held.compareAndSet(true, false)) flight.busy.set(false) }
        val j = fence.launch(scope) { runAttempt(release, retry) }
        job = j
        j.invokeOnCompletion { release(); pendingRetry.getAndSet(null)?.let { r -> if (!closed) start(r) } }
    }

    /** At most one delayed re-run of a retry that hit the call gap. */
    private fun scheduleRetry(r: Retry, afterMs: Long) {
        if (r.delayed || !delayedPending.compareAndSet(false, true)) return
        r.delayed = true
        fence.launch(scope) { try { delay(afterMs.coerceIn(0L, MIN_CALL_GAP_MS)) } finally { delayedPending.set(false) }; start(r) }
    }

    fun close() { closed = true; flight.good = null; job?.cancel(); tr.shadowPending?.cancel(); shadowScope.coroutineContext[Job]?.cancel() }

    private fun live(gen: Long) = !closed && enabled() && pipelineOn() && generation() == gen

    private fun finish(o: PlaceOutcome, t: Trace = tr) {
        stats(o)
        try { chosenSink(t.chosenIds) } catch (_: Exception) { }
        log("place outcome=${o.name}")
        // The one `place layers` line (counts, buckets and fixed tokens; no id, name, coordinate or score), only for an attempt that went through the layered flow.
        if (t.l1 != null) log("place layers v=1 outcome=${o.name} l1=${t.l1} skip=${t.l1Skip} k=${t.l1k} cand=${t.cand} narrowed=${t.l1Cand} withRef=${t.l1With} noRef=${t.l1No} score=${t.l1Score} margin=${t.l1Margin} l2=${t.l2} l2refs=${t.l2Refs} l2candRef=${t.l2WithRefs} noPhoto=${t.noPhoto.size} l3=${t.l3} card=${t.layersCard}")
        val totalMs = (elapsed() - t.startedAt).coerceAtLeast(0L)
        log("place attempt outcome=${o.name} reason=${t.reason} call=${t.call} online=${t.online} cell=${t.cell} cand=${t.cand} ids=${t.ids} pinCand=${t.pinCand} frameAgeS=${t.frameAgeS} fixAgeB=${t.fixAgeB} accB=${t.accB} candMs=${t.candMs} callMs=${t.callMs} totalMs=$totalMs chosen=${t.chosen} distB=${t.distB} confPct=${t.confPct} dedupeAgeS=${t.dedupeAgeS} dedupeDistB=${t.dedupeDistB} callGapS=${t.callGapS} card=${t.card} cardWhy=${t.cardWhy} reveal=${t.reveal} err=${t.err} mode=${t.mode} backend=${t.backend} resolve=${t.resolve} label=${t.label} thrV=${BackendThresholds.VERSION} fixSrc=${t.fixSrc} fixRealAgeB=${t.fixRealAgeB} pinFirst=${t.pinFirst} weakWin=${t.weakWin} retry=${t.retry} hist=${t.hist} textHit=${t.textHit}${maybeFields(t)}${shadowFields(t)}")
    }

    /** `maybe=<token>`: the offline tier's fixed outcome token (counts and buckets only inside it). Empty when the tier was not asked. */
    private fun maybeFields(t: Trace): String = t.maybe?.let { " maybe=$it" }.orEmpty()

    /** The content-free shadow fields: buckets, a rank, a boolean and a duration. Empty when the shadow flag is off, so the line is then byte-identical to before. */
    private fun shadowFields(t: Trace): String {
        val s = t.shadow ?: return ""
        val r = t.shadowOut?.result
        if (s != "ok" || r == null) return " shadow=$s"
        return " shadow=ok shCh=${r.channel.name.lowercase()} scoreTop=${r.top1?.let { PlaceShadow.scoreBucket(it.milli) } ?: "na"} margin=${PlaceShadow.marginBucket(r.margin)} chosenRank=${t.shadowRank} agrees=${t.shadowAgrees} embedMs=${t.shadowOut?.embedMs ?: -1}${mixedField(t, r)}"
    }

    /** `shMixed=<n>`: on the IMAGE channel, how many pack places were left out of the ranking (they have no image reference), so a pin + pack attempt is told apart from a pins-only one. Only on a scored line (shadow on). */
    private fun mixedField(t: Trace, r: com.rokid.context.memory.retrieval.CandidateScorer.Result): String =
        " shMixed=${if (r.channel == com.rokid.context.memory.retrieval.CandidateScorer.Channel.IMAGE) t.shadowPackIds.count { r.rankOf(it) == null } else 0}"

    private suspend fun runAttempt(release: () -> Unit, retry: Retry?) {
        val ticket = coroutineContext[ZoneTicket] ?: fence.ticket()
        val mine = Trace(elapsed()) // a later poke may replace [tr] while this attempt settles its shadow: this attempt always logs its own trace
        tr = mine
        var decided: PlaceOutcome? = null
        try {
            sweep()
            val o = attempt(ticket, retry)
            decided = o
            release()
            try { settleShadow(mine) } catch (c: CancellationException) { mine.shadow = null; mine.shadowPending?.cancel(); finish(o, mine); throw c } // the card is out: the line keeps the REAL outcome
            finish(o, mine)
        } catch (c: CancellationException) {
            mine.shadowPending?.cancel(); mine.shadow = null
            if (decided == null) {
                if (c is ZoneFenced || !ticket.isOpen()) { mine.reason = "cancelled_by_zone_fence"; finish(PlaceOutcome.ZONE, mine) } else { mine.reason = "cancelled"; mine.err = "CancellationException"; finish(PlaceOutcome.FAILED, mine) }
            }
            throw c
        } catch (e: Exception) { mine.shadowPending?.cancel(); mine.shadow = null; mine.reason = "exception"; mine.err = e.javaClass.simpleName; finish(PlaceOutcome.FAILED, mine) }
        finally { try { onAttemptEnd() } catch (_: Exception) { } }
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

    private suspend fun attempt(ticket: ZoneTicket, retry: Retry? = null): PlaceOutcome {
        val gen = generation()
        val cardEpoch = cards?.epoch() ?: 0L
        tr.authority = if (historyOn()) try { historyAuthority() } catch (c: CancellationException) { throw c } catch (_: Exception) { null } else null // null = no history row for this attempt (fail closed)
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
        if (retry != null) tr.retry = if (retry.reason == RetryReason.PIN_CREATED) "pin_created" else "cell_retry"
        else if (cellRetryFrame == f.frameId) { tr.retry = "cell_retry"; cellRetryFrame = null }
        if (retry == null && f.frameId in handled) { tr.reason = "already_handled"; return PlaceOutcome.SEEN }
        stopped(gen, ticket, f.frameId)?.let { return it } // before the fix is asked for
        val raw = fix()
        if (raw != null) { tr.fixAgeB = bucketAgeMs(raw.ageMs); tr.accB = bucketAccuracyM(raw.accuracyM) }
        val strictReason = when {
            raw == null -> "fix_unavailable"
            raw.ageMs < 0 -> "fix_age_negative"
            raw.ageMs > MAX_FIX_AGE_MS -> "fix_too_old"
            raw.accuracyM.isNaN() || raw.accuracyM > MAX_FIX_ACCURACY_M -> "fix_inaccurate"
            !raw.lat.isFinite() || !raw.lon.isFinite() -> "fix_not_finite"
            else -> null
        }
        val fx: PlaceFix
        if (strictReason == null) { fx = raw!!; tr.fixSrc = "fresh" }
        else {
            // F2: indoors the fix is often missing or too old or too coarse while the wearer has not moved: the last good fix may stand in for it (only the candidate list and the zone judgement use it; pin-first never does).
            val re = if (fixReuseOn()) reusedFix(f, raw, gen) else null
            if (re?.fix == null) {
                tr.reason = re?.refusal ?: strictReason
                if (raw != null) { tr.fixSrc = "none" } else tr.fixSrc = "none"
                return if (raw == null) PlaceOutcome.NO_FIX else PlaceOutcome.STALE_FIX
            }
            fx = re.fix; tr.fixSrc = "reused"; tr.fixRealAgeB = bucketRealAgeMs(re.realAgeMs); tr.fixAgeB = bucketAgeMs(fx.ageMs); tr.accB = bucketAccuracyM(fx.accuracyM)
        }
        if (tr.fixSrc == "fresh") { tr.fixAgeB = bucketAgeMs(fx.ageMs); tr.accB = bucketAccuracyM(fx.accuracyM) }
        tr.v2 = dedupeV2On(); tr.fxLat = fx.lat; tr.fxLon = fx.lon; tr.fxAcc = fx.accuracyM; tr.nowAt = now
        // S2: the fix is judged against the zones itself (box + buffer + approach margin, accuracy against the user, unreadable zones block), before the cell fetch or any upload.
        if (com.rokid.context.core.model.zone.ZoneEvaluator.blocksPoint(try { zones() } catch (c: CancellationException) { throw c } catch (e: Exception) { null }, fx.lat, fx.lon, fx.accuracyM)) { tr.reason = "zone_fix"; return PlaceOutcome.ZONE }
        if (tr.fixSrc == "fresh") flight.good = PlaceFlight.GoodFix(fx.lat, fx.lon, fx.accuracyM, elapsed() - fx.ageMs, f.sessionId, now - fx.ageMs, gen) // the zone judged it first: a zone fix is never kept
        stopped(gen, ticket, f.frameId)?.let { return it } // before the pack may fetch
        if (f.frameId !in handled) remember(f.frameId)
        if (tr.v2 || pinFirstOn()) seedFromHistory(now)
        val picked = backend()
        val online = picked.backend == Backend.ONLINE // Offline only is never online: the pack is not fetched from the network and no photo or name is sent
        tr.online = online.toString(); tr.mode = picked.mode.name; tr.backend = picked.backend?.name?.lowercase() ?: "none"; tr.resolve = picked.reason.token; tr.label = picked.label
        val packAt = elapsed()
        val found = candidates(fx.lat, fx.lon, online)
        tr.candMs = (elapsed() - packAt).coerceAtLeast(0L); tr.cell = found.outcome.name; tr.cand = found.places.size
        if (retry?.reason == RetryReason.PIN_CREATED && (retry.pinId == null || found.places.none { it.id == retry.pinId })) { tr.retry = "pin_not_near"; tr.reason = "retry_pin_not_near"; return PlaceOutcome.NO_CANDIDATES }
        if (found.places.isEmpty()) {
            // F4: a pack that could not be read or fetched is not "nothing here": the frame is tried again at the next poke (the cell may have arrived by then). FRESH or FETCHED and empty stays handled.
            if (retryOn() && found.outcome in PACK_MISS) { handled.remove(f.frameId); cellRetryFrame = f.frameId }
            tr.reason = "pack_empty"; return PlaceOutcome.NO_CANDIDATES
        }
        tr.pinFirst = "off"
        val pol = try { policy() } catch (c: CancellationException) { throw c } catch (_: Exception) { PlacePolicy() }
        tr.checkMode = pol.checkMode.name; tr.photoMatch = pol.photoMatch
        val layered = pol.confirmedOnly || (photoLayer != null && pol.l1)
        if (layered) tr.l1 = "off"
        var pinHeld = false
        if (pinFirstOn()) {
            flight.visits.update(fx.lat, fx.lon, now)
            // P2-8: the ambiguity is judged against EVERY active pin within reach (the pack passes them uncapped), never against the capped model list
            val pins = pinsNear((found.places + found.nearbyPins).distinctBy { it.id }).filter { UserPlace.isUserId(it.id) }.map { it to Geo.distanceM(fx.lat, fx.lon, it.lat, it.lon) }
            val d = PinFirst.decide(fx, tr.fixSrc, pins, flight.visits, now)
            tr.pinFirst = d.token
            // Confirmed-only: a location-only pin card is NOT sent to the glasses (unless "Also show pin cards when nearby" is ON). The attempt goes on to the photo checks with the pin among the candidates.
            if (pol.holdPins && (d is PinFirst.Decision.Single || d is PinFirst.Decision.Cluster)) pinHeld = true
            else when (d) {
                is PinFirst.Decision.Single -> return pinFirstCard(d.pin, d.distanceM, f.frameId, gen, cardEpoch, ticket, now)
                // P1-1: several pins is answered at once with the fenced cluster card: no photo upload, no model call, no memory.
                is PinFirst.Decision.Cluster -> return clusterCard(d, d.members.map { m -> m.parentId?.let { pid -> found.places.firstOrNull { it.id == pid }?.name } }, f.frameId, gen, cardEpoch, ticket, now)
                else -> {}
            }
        }
        if (retry == null) flight.lastAttempt?.let { a ->
            val d = Geo.distanceM(fx.lat, fx.lon, a.lat, a.lon)
            tr.dedupeAgeS = (now - a.at) / 1000; tr.dedupeDistB = bucketM(d)
            if (now - a.at in 0..DEDUPE_MS && d <= DEDUPE_M) { tr.reason = "same_place_recently"; return PlaceOutcome.DUPLICATE }
        }
        if (tr.v2 && retry == null) flight.weak?.let { w ->
            val win = weakWindowMs(w.streak)
            if (now - w.at in 0..win && Geo.distanceM(fx.lat, fx.lon, w.lat, w.lon) <= DEDUPE_M) { tr.weakWin = (win / 1000).toString(); tr.reason = "weak_backoff"; return PlaceOutcome.DUPLICATE }
        }
        val pool = if (pinHeld) (found.places + found.nearbyPins).distinctBy { it.id } else found.places
        val hits = cutKeepingPins(pool.map { it to Geo.distanceM(fx.lat, fx.lon, it.lat, it.lon) })
        tr.cand = hits.size; tr.ids = idList(hits.map { it.first.id }.filter { !UserPlace.isUserId(it) }); tr.pinCand = hits.count { UserPlace.isUserId(it.first.id) }
        tr.evalCand = hits.map { it.first.id }
        tr.epoch = cardEpoch
        // Switch "Also show places that have no photo, by GPS only": the nearby places with NO reference photo are exempt from the confirmed-only hold (a pin keeps its own rule). Counts only.
        if (pol.confirmedOnly && pol.noPhotoByGps && refPlaceIds != null) {
            val has = try { refPlaceIds.invoke(hits.map { it.first }) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            if (has != null) tr.noPhoto = hits.filter { !UserPlace.isUserId(it.first.id) && it.first.id !in has }
        }
        // ---- LAYER 1: the photo against the reference photos of the candidates (on the phone, any network state) ----
        var list = hits
        val l1 = photoLayer
        if (l1 != null && pol.l1 && hits.isNotEmpty()) {
            stopped(gen, ticket, f.frameId)?.let { return it } // before the photo is read and embedded
            val out = try { l1(f.frameId, hits) { ticket.isOpen() && live(gen) } } catch (c: CancellationException) { throw c } catch (_: Exception) { PhotoLayerOutcome.Skipped("err") }
            stopped(gen, ticket, f.frameId)?.let { return it }
            when (out) {
                is PhotoLayerOutcome.Skipped -> { tr.l1 = "skip"; tr.l1Skip = out.token }
                is PhotoLayerOutcome.Decided -> {
                    val r = out.result
                    tr.l1Res = r
                    tr.l1 = r.outcome.name.lowercase(); tr.l1k = out.frames; tr.l1With = r.withRef; tr.l1No = r.noRef
                    tr.l1Score = com.rokid.context.memory.retrieval.PhotoConfirm.scoreBucket(r.accepted?.milli ?: r.topMilli); tr.l1Margin = com.rokid.context.memory.retrieval.PhotoConfirm.marginBucket(r.margin)
                    val acc = r.accepted
                    if (r.outcome == com.rokid.context.memory.retrieval.PhotoConfirm.Outcome.ACCEPT && acc != null) {
                        val hit = hits.firstOrNull { it.first.id == acc.placeId }
                        if (hit != null) { tr.l1Cand = 1; return l1Accept(hit.first, hit.second, acc.milli ?: 0, found.places, f.frameId, gen, cardEpoch, ticket) }
                    }
                    if (r.outcome == com.rokid.context.memory.retrieval.PhotoConfirm.Outcome.MEDIUM || r.outcome == com.rokid.context.memory.retrieval.PhotoConfirm.Outcome.BELOW) {
                        val keep = r.candidates.toSet(); list = hits.filter { it.first.id in keep }
                    }
                    tr.l1Cand = list.size
                }
            }
        }
        if (list.isEmpty()) { // every nearby place with a reference photo scored below its floor: there is nothing to ask the online or offline layers
            tr.reason = "l1_all_below"
            return if (pol.confirmedOnly) held(hits, CardDelivery.HELD_NOT_CONFIRMED, f.frameId, gen, ticket) else fallback(hits, f.frameId, gen, cardEpoch, ticket)
        }
        // The spacing is judged NOW (after any lengthy fetch), against the end of the previous call, on the monotonic clock.
        val sinceCall = elapsed() - flight.lastCallAt
        tr.callGapS = if (flight.lastCallAt < 0) -1L else (sinceCall / 1000).coerceAtLeast(0L)
        val l2Allowed = pol.l2 && pol.checkMode != CheckMode.PHONE_ONLY
        val callOk = online && l2Allowed && sinceCall >= MIN_CALL_GAP_MS
        if (online && l2Allowed && !callOk) { tr.reason = "call_gap_${MIN_CALL_GAP_MS / 1000}s"; if (retry != null) scheduleRetry(retry, MIN_CALL_GAP_MS - sinceCall); return PlaceOutcome.RATE_LIMITED }
        if (!tr.v2) flight.lastAttempt = PlaceFlight.Attempt(fx.lat, fx.lon, now) // v2: only a published (or suppressed) card consumes the card dedupe
        stopped(gen, ticket, f.frameId)?.let { return it } // before the upload (the router asks again inside its permit)
        if (!callOk) { // Offline only without an on-device model keeps the pack-only fallback (no photo analysis, nothing leaves the phone) and says why in the log
            val why = when {
                online && !pol.l2 -> "l2_off"
                online -> "phone_only"
                picked.needsOnDeviceModel -> "offline_only_no_local_model"
                else -> "offline"
            }
            tr.reason = why; tr.call = when (why) { "offline_only_no_local_model" -> "skipped_offline_only"; "offline" -> "skipped_offline"; else -> "skipped_$why" }; tr.l2 = "skipped_$why"
            // The reason to show when the offline guess cannot confirm either: "Online only" without a network; with a network but "On the phone only" / Layer 2 off and no reference photo to compare, no reference; else the offline guess is simply unavailable.
            val base = when {
                !online && pol.checkMode == CheckMode.ONLINE_ONLY -> CardDelivery.HELD_ONLINE_ONLY_NO_NETWORK
                online && tr.l1 == "no_ref" -> CardDelivery.HELD_NO_REFERENCE
                else -> CardDelivery.HELD_OFFLINE_NO_MODEL
            }
            return offlineFallback(list, hits, f.frameId, gen, cardEpoch, ticket, pol, base) } // offline: the only no-call fallback (G1)
        // Roaming data saver: online, but photos may not go out on this network (mobile, roaming, unknown). Same path as offline: no identification, no photo sent.
        if (!photoAllowed()) { tr.reason = "data_saver"; tr.call = "skipped_data_saver"; tr.l2 = "skipped_data_saver"; return offlineFallback(list, hits, f.frameId, gen, cardEpoch, ticket, pol, CardDelivery.HELD_DATA_SAVER) }
        // G1 (ruling 2026-11-17): a power, thermal or policy denial is NOT a failure of the match and never becomes a nearby identification: no call, no card.
        if (!admit()) { tr.reason = "not_admitted"; tr.call = "skipped_not_admitted"; tr.l2 = "skipped_not_admitted"; if (pol.confirmedOnly) held(hits, CardDelivery.HELD_NOT_ADMITTED, f.frameId, gen, ticket); return PlaceOutcome.NOT_ADMITTED }
        flight.lastCallAt = elapsed()
        val lines = list.mapIndexed { i, h -> "${i + 1}. ${h.first.name} | ${h.first.kind.replace('_', ' ')}" }
        if (!ticket.isOpen()) { tr.reason = "fence_closed"; return PlaceOutcome.ZONE } // the last check; nothing suspends between it and the call, which the fence's job cancels if the zone comes later
        // G1: only a GENUINE call failure (the provider or the network failed) may fall back to the nearby card; a policy denial, a forgotten photo or an invalid reply is no identification.
        var genuineFailure = false
        tr.call = "made"; tr.l2 = "made"
        val callAt = elapsed()
        val sh = shadow
        if (sh != null && shadowEnabled()) { tr.shadowStartedAt = elapsed(); tr.shadowPackIds = hits.map { it.first.id }.filter { !UserPlace.isUserId(it) }; tr.shadowPending = shadowScope.async { try { sh(f.frameId, hits) { shadowEnabled() && ticket.isOpen() && live(gen) } } catch (c: CancellationException) { throw c } catch (_: Exception) { null } } }
        val res = try { if (matchPlaces != null) matchPlaces.invoke(f.frameId, list.map { it.first }) else match(f.frameId, lines) } catch (c: CancellationException) { throw c } catch (e: Exception) { genuineFailure = isGenuineCallFailure(e); tr.err = e.javaClass.simpleName; null }
        flight.lastCallAt = elapsed() // the permit wait and the call are over: the gap runs from here, not from the start of the attempt
        tr.callMs = (flight.lastCallAt - callAt).coerceAtLeast(0L)
        if (stopped(gen, ticket, f.frameId) != null) return PlaceOutcome.DROPPED
        if (res == null) {
            tr.reason = if (genuineFailure) "call_failed_genuine" else "call_failed_or_photo_gone"; tr.l2 = "failed"
            if (!pol.confirmedOnly) return if (genuineFailure) fallback(hits, f.frameId, gen, cardEpoch, ticket) else PlaceOutcome.FAILED
            if (!genuineFailure) { held(hits, CardDelivery.HELD_CALL_FAILED, f.frameId, gen, ticket); return PlaceOutcome.FAILED }
            // Automatic: the offline guess may still confirm it; Online only: held.
            return if (pol.checkMode == CheckMode.ONLINE_ONLY) held(hits, CardDelivery.HELD_CALL_FAILED, f.frameId, gen, ticket) else offlineFallback(list, hits, f.frameId, gen, cardEpoch, ticket, pol, CardDelivery.HELD_CALL_FAILED)
        }
        if (!res.delivered) { tr.reason = "photo_not_delivered"; tr.l2 = "not_delivered"; if (pol.confirmedOnly) held(hits, CardDelivery.HELD_CALL_FAILED, f.frameId, gen, ticket); return PlaceOutcome.FAILED } // the photo was not proven to have reached the model: not a photo match, not a nearby guess either
        val choice = res.output.choice
        tr.confPct = (res.output.confidence * 100).toInt(); tr.l2Conf = tr.confPct; tr.l2Refs = res.refImages; tr.l2WithRefs = res.candWithRefs
        if (choice.equals(NONE, ignoreCase = true)) { tr.reason = "model_none"; tr.l2 = "none"; if (pol.confirmedOnly) { exemptNearby(f.frameId, gen, ticket)?.let { return it }; heldNoMatch(CardDelivery.HELD_NOT_CONFIRMED, f.frameId) }; return weak(PlaceOutcome.NONE) }
        val idx = choice.toIntOrNull()?.takeIf { it in 1..list.size && it.toString() == choice } ?: run { tr.reason = "choice_out_of_range"; tr.l2 = "contract"; if (pol.confirmedOnly) { exemptNearby(f.frameId, gen, ticket)?.let { return it }; heldNoMatch(CardDelivery.HELD_NOT_CONFIRMED, f.frameId) }; return weak(PlaceOutcome.CONTRACT) }
        tr.l2Pick = list[idx - 1].first.id
        setChosen(list[idx - 1].first); tr.distB = bucketM(list[idx - 1].second)
        tr.shadowChoice = tr.chosenIds.firstOrNull() // P3-7: the private id stays internal for the scorer's ranking; the log only ever prints the redacted `chosen` token
        val floor = picked.backend ?: Backend.ONLINE // the floor of the backend that made the call
        if (res.output.confidence < BackendThresholds.minConfidence(floor)) { tr.reason = "confidence_below_${BackendThresholds.minConfidencePct(floor)}"; tr.l2 = "low"
            // S6: never silent. The answer is kept as a phone-only "possible" row (no glasses card, no memory, no card dedupe consumed).
            record(HistoryKind.POSSIBLE, list[idx - 1].first.id, list[idx - 1].first.name, emptyList(), f.frameId, tr.confPct)
            return weak(PlaceOutcome.LOW_CONFIDENCE) }
        tr.l2 = "confirmed"
        val place = list[idx - 1].first
        val parent = place.parentId?.let { pid -> found.places.firstOrNull { it.id == pid }?.name }
        return commit(place, parent, res.output.confidence, res.delivered, f.frameId, gen, cardEpoch, ticket, HistoryKind.PHOTO, if (pol.confirmedOnly) tr.confPct else null)
    }

    /** Layer 1 accepted [place] (the photo matches its reference photo, with the margin): memory and a "photo match" card, history kind IMAGE. The deciding number is the score / 10. */
    private suspend fun l1Accept(place: PlaceEntity, centreM: Double, milli: Int, all: List<PlaceEntity>, frameId: String, gen: Long, epoch: Long, ticket: ZoneTicket): PlaceOutcome {
        setChosen(place); tr.distB = bucketM(centreM); tr.confPct = (milli / 10).coerceIn(0, 100)
        val parent = place.parentId?.let { pid -> all.firstOrNull { it.id == pid }?.name }
        return commit(place, parent, milli / 1000f, true, frameId, gen, epoch, ticket, HistoryKind.IMAGE, tr.confPct)
    }

    /**
     * Runs AFTER the card, the memory and the call timing are settled. A shadow that is already done is read at once; otherwise this waits at most what is left of [SHADOW_BUDGET_MS] since it started, and a
     * miss is `err`. Nothing here can change the outcome. A result dropped by the fence (a zone, the switch, a wipe) logs nothing.
     */
    private suspend fun settleShadow(t: Trace) {
        val pending = t.shadowPending ?: return
        // The Developer flag went OFF after launch: the work is fenced (its liveness reads the flag) and cancelled here; the line then carries no shadow field, like a flag-off line.
        if (!shadowEnabled()) { pending.cancel(); t.shadow = null; return }
        val out: ShadowOutcome? = try {
            if (pending.isCompleted) pending.getCompleted()
            else {
                val left = SHADOW_BUDGET_MS - (elapsed() - t.shadowStartedAt)
                if (left > 0) withTimeoutOrNull(left) { pending.await() } else null
            }
        } catch (c: CancellationException) { pending.cancel(); throw c } catch (_: Exception) { null }
        if (out == null) { pending.cancel(); t.shadow = "err"; return }
        when (out.kind) {
            ShadowOutcome.Kind.OFF, ShadowOutcome.Kind.DROPPED -> t.shadow = null
            ShadowOutcome.Kind.UNAVAILABLE -> t.shadow = "na"
            ShadowOutcome.Kind.SCORED -> { t.shadow = "ok"; t.shadowOut = out; t.shadowChoice?.let { shadowAgreement(t, it) } }
        }
    }

    /** The rank of the model choice by score, and whether it is the top score. "na" when there was no scored result or the choice was not ranked. */
    private fun shadowAgreement(t: Trace, chosenId: String) {
        val r = t.shadowOut?.result ?: return
        val rank = r.rankOf(chosenId) ?: return
        t.shadowRank = rank.toString(); t.shadowAgrees = (rank == 1).toString()
    }

    /**
     * Writes the memory and shows the card, as one unit. The mark in the [ledger] is durable BEFORE the commit and cleared only when the card is out (the memory stays) or the memory is taken
     * back, so a rollback that fails, a cancellation or a process death in between leaves a mark the next [sweep] acts on. The transaction itself refuses to commit once the fence closed.
     */
    private suspend fun commit(place: PlaceEntity, parent: String?, confidence: Float, delivered: Boolean, frameId: String, gen: Long, epoch: Long, ticket: ZoneTicket, kind: HistoryKind = HistoryKind.PHOTO, confPct: Int? = null): PlaceOutcome {
        var keep = false
        ledger.mark(frameId)
        try {
            val saved = saveMemory(PlaceMemoryRequest(frameId, place, parent, confidence, delivered, gen, allowed = { ticket.isOpen() && live(gen) }))
            if (!saved) { tr.reason = "memory_not_saved"; tr.card = "dropped"; tr.cardWhy = "memory_not_saved"; return PlaceOutcome.DROPPED }
            // A zone that became active while the memory was being saved (after its last in-transaction check): the memory goes again and no card is shown.
            if (stopped(gen, ticket, frameId) != null) { tr.card = "dropped"; tr.cardWhy = tr.reason; return PlaceOutcome.DROPPED }
            if (suppressedHere(place.id)) { keep = true; tr.reason = "matched"; return PlaceOutcome.MATCHED } // the memory is kept and revealed, only the card is withheld
            // P2-5: the one reservation shared by every automatic card path (pin, cluster, text, photo match, nearby): another path holding or just showing this place withholds only the card
            val ids = listOf(place.id)
            if (!flight.reserve(ids, tr.nowAt)) { keep = true; tr.reason = "matched"; tr.card = "suppressed"; tr.cardWhy = "already_shown_here"; return PlaceOutcome.MATCHED }
            try {
                if (!publish(PlaceCards.photo(place, frameId), epoch, gen, frameId, ticket)) return PlaceOutcome.DROPPED
                noteCard(place.id)
            } finally { flight.unreserve(ids) }
            record(kind, place.id, place.name, emptyList(), frameId, confPct)
            keep = true
            tr.reason = "matched"
            return if (kind == HistoryKind.IMAGE) PlaceOutcome.IMAGE else PlaceOutcome.MATCHED
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
        var newId: String? = null
        val shown = fence.act(ticket) { if (!live(gen)) false else if (h != null) h.publishIfId(card, epoch)?.also { newId = it } != null else { onCard(card); true } } == true
        if (shown) tr.cardId = newId
        if (shown) { tr.card = "shown"; tr.cardWhy = "-" } else { tr.card = "dropped"; tr.cardWhy = "withdrawn_or_fenced"; tr.reason = "card_dropped" }
        return shown
    }

    /**
     * The offline path (V2b): first the offline tier (a "Maybe <place>" card from a photo caption, flags permitting), else (legacy) the nearest-place card of [fallback] exactly as before, or (confirmed-only) a held row.
     * The tier is never asked online, never under "Online only", and a place it names is shown at most once per [ONCE_PER_PLACE_MS] like every other card. A refusal, a miss or a score under the cut-off is "no guess".
     * [list] is the candidate list the tier ranks (Layer 1 may have narrowed it); [hits] is the whole list (the nearest-place rule). [base] = the held token when the tier could not confirm and the reason is the one that sent us here.
     */
    private suspend fun offlineFallback(list: List<Pair<PlaceEntity, Double>>, hits: List<Pair<PlaceEntity, Double>>, frameId: String, gen: Long, epoch: Long, ticket: ZoneTicket, pol: PlacePolicy, base: String): PlaceOutcome {
        val ask = offlineGuess.takeIf { pol.checkMode != CheckMode.ONLINE_ONLY }
        if (ask == null) { // the tier is absent, or "Online only" never uses it
            tr.l3 = if (pol.checkMode == CheckMode.ONLINE_ONLY) "skip_online_only" else "absent"
            return if (pol.confirmedOnly) held(hits, base, frameId, gen, ticket) else fallback(hits, frameId, gen, epoch, ticket)
        }
        stopped(gen, ticket, frameId)?.let { return it } // nothing is read or captioned when a zone, a Forget or a switch came first
        val r = try { ask(frameId, list) } catch (c: CancellationException) { throw c } catch (_: Exception) { tr.maybe = "err"; null }
        if (r != null) tr.maybe = r.token
        val place = r?.place
        if (place == null) {
            tr.l3 = if (r == null) "err" else if (r.token.startsWith("below")) "below" else r.token.substringBefore('(')
            if (!pol.confirmedOnly) return fallback(hits, frameId, gen, epoch, ticket)
            // Why nothing: the tier is not available (switched off or no model) = the base reason; it ran and found nothing, or could not run now = a more specific one.
            val token = when {
                r == null || r.token == "failed" -> if (base == CardDelivery.HELD_OFFLINE_NO_MODEL) CardDelivery.HELD_NOT_CONFIRMED else base
                r.token == "off" || !offlineAvailable() -> base
                r.token.startsWith("refused_") -> CardDelivery.HELD_NOT_ADMITTED
                base == CardDelivery.HELD_OFFLINE_NO_MODEL || base == CardDelivery.HELD_ONLINE_ONLY_NO_NETWORK || base == CardDelivery.HELD_NO_REFERENCE -> CardDelivery.HELD_NOT_CONFIRMED
                else -> base // the online check was refused (Data saver) or failed: that stays the visible reason
            }
            return held(hits, token, frameId, gen, ticket)
        }
        tr.l3 = "maybe"
        stopped(gen, ticket, frameId)?.let { return it }
        setChosen(place); tr.distB = bucketM(hits.firstOrNull { it.first.id == place.id }?.second ?: 0.0)
        if (suppressedHere(place.id)) { tr.reason = "maybe_card"; return PlaceOutcome.MAYBE }
        val ids = listOf(place.id)
        if (!flight.reserve(ids, tr.nowAt)) return reservedElsewhere()
        try {
            if (!publish(PlaceCards.maybe(place, frameId), epoch, gen, frameId, ticket)) return PlaceOutcome.DROPPED
            noteCard(place.id)
        } finally { flight.unreserve(ids) }
        record(HistoryKind.MAYBE, place.id, place.name, emptyList(), frameId, null)
        tr.reason = "maybe_card"
        return PlaceOutcome.MAYBE
    }

    /**
     * Confirmed-only: nothing confirmed the place, so NO card goes to the glasses. One history row says why ([token], a HELD_* delivery): the single nearest place within [NEARBY_M] (the same rule as [fallback]) as a NEARBY
     * (or PIN) row, else a NO_MATCH row. The row is never a shown card: it does not note the card, does not feed the once-per-place rule and is skipped when the dedupe is seeded. The position dedupe is consumed so the
     * same spot does not write a row for every frame.
     */
    private suspend fun held(hits: List<Pair<PlaceEntity, Double>>, token: String, frameId: String, gen: Long, ticket: ZoneTicket): PlaceOutcome {
        stopped(gen, ticket, frameId)?.let { return it }
        exemptNearby(frameId, gen, ticket)?.let { return it }
        val why = token
        val first = hits.firstOrNull()
        val unique = first != null && gap(first.first, first.second) <= NEARBY_M && !(hits.size > 1 && gap(hits[1].first, hits[1].second) <= NEARBY_M)
        if (unique) {
            val p = first!!.first
            setChosen(p); tr.distB = bucketM(first.second)
            record(if (UserPlace.isUserId(p.id)) HistoryKind.PIN else HistoryKind.NEARBY, p.id, p.name, emptyList(), frameId, tr.confPct.takeIf { it >= 0 }, why)
        } else record(HistoryKind.NO_MATCH, NO_MATCH_ID, NO_MATCH_TITLE, emptyList(), frameId, tr.confPct.takeIf { it >= 0 }, why)
        return heldDone(why)
    }

    /**
     * The no-photo switch: nothing confirmed, but nearby places that have NO reference photo at all are exempt from the hold and get the old nearby card (never the "confirmed" look). Null = none to show (the hold goes on).
     */
    private suspend fun exemptNearby(frameId: String, gen: Long, ticket: ZoneTicket): PlaceOutcome? {
        if (tr.noPhoto.isEmpty()) return null
        val o = fallback(tr.noPhoto, frameId, gen, tr.epoch, ticket)
        return if (o == PlaceOutcome.NOTHING_NEARBY) null else o
    }

    /** A model "none" or an unusable answer: a NO_MATCH row (the model looked and found no listed place). */
    private suspend fun heldNoMatch(token: String, frameId: String) {
        record(HistoryKind.NO_MATCH, NO_MATCH_ID, NO_MATCH_TITLE, emptyList(), frameId, tr.confPct.takeIf { it >= 0 }, token)
        heldDone(token)
    }

    private fun heldDone(token: String): PlaceOutcome {
        tr.card = "held"; tr.cardWhy = token.lowercase(); tr.held = token.lowercase(); if (tr.reason == "-") tr.reason = "held"
        if (!tr.fxLat.isNaN()) flight.lastAttempt = PlaceFlight.Attempt(tr.fxLat, tr.fxLon, tr.nowAt)
        return PlaceOutcome.HELD
    }

    /** The nearest place's card when it is unique within [NEARBY_M] (edge distance); otherwise nothing. */
    private suspend fun fallback(hits: List<Pair<PlaceEntity, Double>>, frameId: String, gen: Long, epoch: Long, ticket: ZoneTicket): PlaceOutcome {
        val first = hits.firstOrNull() ?: run { tr.reason = "no_hits"; return PlaceOutcome.NOTHING_NEARBY }
        setChosen(first.first); tr.distB = bucketM(first.second)
        if (gap(first.first, first.second) > NEARBY_M) { tr.reason = "nearest_beyond_${NEARBY_M.toInt()}m"; setChosen(null); return PlaceOutcome.NOTHING_NEARBY }
        if (hits.size > 1 && gap(hits[1].first, hits[1].second) <= NEARBY_M) { tr.reason = "ambiguous_second_within_${NEARBY_M.toInt()}m"; setChosen(null); return PlaceOutcome.NOTHING_NEARBY }
        if (suppressedHere(first.first.id)) { tr.reason = "nearby_card"; return PlaceOutcome.NEARBY }
        val ids = listOf(first.first.id)
        if (!flight.reserve(ids, tr.nowAt)) return reservedElsewhere() // P2-5: the shared reservation
        try {
            if (!publish(PlaceCards.nearby(first.first, gap(first.first, first.second), frameId), epoch, gen, frameId, ticket)) return PlaceOutcome.DROPPED
            noteCard(first.first.id)
        } finally { flight.unreserve(ids) }
        record(HistoryKind.NEARBY, first.first.id, first.first.name, emptyList(), frameId, null)
        tr.reason = "nearby_card"
        return PlaceOutcome.NEARBY
    }

    // ---- F1 pin-first ----

    private fun spotOf(p: PlaceEntity) = PinVisits.Spot(p.id, p.lat, p.lon, p.radiusM)

    /** The log token of the chosen place (a user pin is only `pin`, never its id) and the ids for the [chosenSink]. */
    private fun setChosen(p: PlaceEntity?) { tr.chosenIds = listOfNotNull(p?.id); tr.chosen = if (p == null) "-" else if (UserPlace.isUserId(p.id)) "pin" else p.id }

    /** What a refused reservation looks like in the trace: the card was not shown because another path holds, or just showed, this pin. */
    private fun reservedElsewhere(): PlaceOutcome {
        tr.reason = "card_reserved"; tr.card = "suppressed"; tr.cardWhy = "already_shown_here"
        if (!tr.fxLat.isNaN()) flight.lastAttempt = PlaceFlight.Attempt(tr.fxLat, tr.fxLon, tr.nowAt)
        flight.weak = null
        return PlaceOutcome.DUPLICATE
    }

    /** A single pin the fix is inside: its card, with no model call, no photo and no memory. Fenced like every card; the visit/card claim is atomic with the other card paths. */
    private suspend fun pinFirstCard(pin: PlaceEntity, distanceM: Double, frameId: String, gen: Long, epoch: Long, ticket: ZoneTicket, now: Long): PlaceOutcome {
        setChosen(pin); tr.distB = bucketM(distanceM); tr.evalCand = listOf(pin.id)
        val ids = listOf(pin.id)
        if (!flight.reserve(ids, now)) return reservedElsewhere()
        try {
            if (!publish(PlaceCards.pin(pin, frameId), epoch, gen, frameId, ticket)) return PlaceOutcome.DROPPED
            noteCard(pin.id)
            flight.visits.begin(listOf(spotOf(pin)), now)
            record(HistoryKind.PIN, pin.id, pin.name, emptyList(), frameId, null)
            tr.reason = "pin_first"
            return PlaceOutcome.PIN_FIRST
        } finally { flight.unreserve(ids) }
    }

    /** The "several pins" card of a cluster, returned AT ONCE (no photo, no model call): never a guessed room. The line says `pinFirst=cluster(n)` and `card=shown`. */
    private suspend fun clusterCard(c: PinFirst.Decision.Cluster, parents: List<String?>, frameId: String, gen: Long, epoch: Long, ticket: ZoneTicket, now: Long): PlaceOutcome {
        tr.chosenIds = c.members.map { it.id }
        val ids = c.members.map { it.id }; tr.evalCand = ids
        if (!flight.reserve(ids, now) { id, cd -> id == c.inside.id && shownHere(cd) }) return reservedElsewhere()
        try {
            val card = PlaceCards.cluster(c.members, parents, frameId)
            if (!publish(card, epoch, gen, frameId, ticket)) return PlaceOutcome.DROPPED
            noteCard(c.inside.id)
            flight.visits.begin(c.members.map(::spotOf), now)
            record(HistoryKind.PIN_CLUSTER, c.inside.id, card.title, ids, frameId, null)
            tr.reason = "pin_first_cluster"
            return PlaceOutcome.PIN_FIRST
        } finally { flight.unreserve(ids) }
    }

    // ---- card history (F6) ----

    /** One history row for the card this attempt just published (or a POSSIBLE row for a low-confidence answer). Never blocks or changes the card: a failure is `hist=fail`. */
    private suspend fun record(kind: HistoryKind, placeId: String, title: String, members: List<String>, frameId: String?, confPct: Int?, delivery: String? = null) {
        if (!historyOn()) { tr.hist = "off"; return }
        val auth = tr.authority ?: run { tr.hist = "fail"; return }
        val ref = glanceRefOk(kind, placeId)
        val ev = try { evalOf(kind, delivery, ref) } catch (c: CancellationException) { throw c } catch (_: Exception) { null } // the record must never block the row
        try { history(PlaceHistoryEvent(kind, placeId, title, members, frameId, if (kind == HistoryKind.POSSIBLE || delivery != null) null else tr.cardId, confPct, delivery, auth, eval = ev, glanceRef = ref)); tr.hist = "ok" }
        catch (c: CancellationException) { throw c } catch (_: Exception) { tr.hist = "fail" }
    }

    /** B3: the per-attempt layer record of the row about to be written (place ids, numbers and fixed tokens only). */
    /** Loop guard D14, the second signal: Layer 2 confirmed THIS place AND Layer 1 scored it at or above a real floor (a kind whose floor is off never counts, so no web photo alone makes a reference). */
    private fun glanceRefOk(kind: HistoryKind, placeId: String): Boolean {
        val t = tr
        if (kind != HistoryKind.PHOTO || t.l2 != "confirmed" || t.l2Pick != placeId) return false
        val s = t.l1Res?.scores?.firstOrNull { it.placeId == placeId } ?: return false
        val m = s.milli ?: return false
        val k = s.kind ?: return false
        val floor = t.photoMatch.of(k).floor
        return floor > 0 && m >= floor
    }

    private fun evalOf(kind: HistoryKind, delivery: String?, ref: Boolean): String {
        val t = tr
        val l1 = t.l1?.let { st -> PlaceEval.L1(st, t.l1k, t.l1Res?.scores.orEmpty(), t.l1Res?.margin, t.l1Skip.takeIf { it != "-" }) }
        return PlaceEval.build(PlaceEval.Input(kind, delivery, t.checkMode, com.rokid.context.memory.retrieval.MatchThresholds.VERSION, l1, t.evalCand, t.l2, t.l2Conf, t.l2Pick, t.l3, ref))
    }

    // ---- F2 fix reuse ----

    private class Reuse(val fix: PlaceFix?, val refusal: String?, val realAgeMs: Long)

    /** The last good fix as a stand-in for the current one, or the fixed reason it is refused (null refusal = there was nothing to reuse). */
    private suspend fun reusedFix(f: PlaceFrame, current: PlaceFix?, gen: Long): Reuse {
        val g = flight.good?.takeIf { it.generation == gen } ?: return Reuse(null, null, 0)
        val age = (elapsed() - g.atElapsed).coerceAtLeast(0L)
        fun no(r: String) = Reuse(null, r, age)
        if (age > FIX_REUSE_MAX_MS) return no("fix_reuse_too_old")
        if (f.motion != "STILL") return no("fix_reuse_not_still")
        if (f.sessionId == null || f.sessionId != g.sessionId) return no("fix_reuse_session")
        if (store.movedSince(g.receivedAtMs)) return no("fix_reuse_moved")
        val eff = g.accM + REUSE_GROWTH_M_PER_MIN * (age / 60_000f)
        if (eff > MAX_FIX_ACCURACY_M) return no("fix_reuse_inaccurate")
        if (current != null && current.lat.isFinite() && current.lon.isFinite() && !current.accuracyM.isNaN() && Geo.distanceM(current.lat, current.lon, g.lat, g.lon) > current.accuracyM + eff) return no("fix_reuse_conflict")
        return Reuse(PlaceFix(g.lat, g.lon, 0L, eff), null, age)
    }

    // ---- F3 dedupe v2 ----

    /** A call reached a verdict that is no card (NONE, CONTRACT, LOW_CONFIDENCE): opens or lengthens the short back-off window. FAILED, NOT_ADMITTED and DROPPED set nothing. */
    private fun weak(o: PlaceOutcome): PlaceOutcome {
        if (!tr.v2 || tr.fxLat.isNaN()) return o
        val prev = flight.weak
        val streak = if (prev != null && tr.nowAt - prev.at in 0..WEAK_STREAK_RESET_MS && Geo.distanceM(tr.fxLat, tr.fxLon, prev.lat, prev.lon) <= DEDUPE_M) prev.streak + 1 else 1
        flight.weak = PlaceFlight.Weak(tr.fxLat, tr.fxLon, tr.nowAt, streak)
        tr.weakWin = (weakWindowMs(streak) / 1000).toString()
        return o
    }

    /** A card was published (or suppressed as already shown): it consumes the card dedupe, records the place's card and ends the weak streak. */
    private fun noteCard(placeId: String) {
        if (tr.fxLat.isNaN()) return
        flight.lastAttempt = PlaceFlight.Attempt(tr.fxLat, tr.fxLon, tr.nowAt)
        flight.noteCard(placeId, PlaceFlight.CardAt(tr.fxLat, tr.fxLon, tr.nowAt))
        flight.weak = null
    }

    /** One card per place while the wearer stays: true (and the trace says so) when this place had a card under 30 min ago within 30 m + the fix accuracy of that card's fix (a seeded row without a position: by place and time alone). */
    private fun shownHere(c: PlaceFlight.CardAt?): Boolean {
        if (!tr.v2 || tr.fxLat.isNaN() || c == null) return false
        if (tr.nowAt - c.at !in 0..ONCE_PER_PLACE_MS) return false
        return c.lat.isNaN() || Geo.distanceM(tr.fxLat, tr.fxLon, c.lat, c.lon) <= DEDUPE_M + tr.fxAcc
    }

    private fun suppressedHere(placeId: String): Boolean {
        if (!shownHere(flight.cardAt(placeId))) return false
        tr.card = "suppressed"; tr.cardWhy = "already_shown_here"
        flight.lastAttempt = PlaceFlight.Attempt(tr.fxLat, tr.fxLon, tr.nowAt)
        flight.weak = null
        return true
    }

    private suspend fun seedFromHistory(now: Long) {
        if (flight.seeded || !historyOn()) return // history off: no rows were written, nothing to seed from
        flight.seeded = true
        try {
            for (r in seedRecent(now - PinFirst.VISIT_MAX_MS)) {
                // P2-7: only an ACTUAL card event seeds the dedupe: a POSSIBLE row, or a late sign-text row that was never sent to the glasses, showed nothing.
                if (!isCardEvent(r)) continue
                if (now - r.at <= ONCE_PER_PLACE_MS && flight.cardAt(r.placeId) == null) flight.noteCard(r.placeId, PlaceFlight.CardAt(Double.NaN, Double.NaN, r.at))
                if (r.kind == "PIN" || r.kind == "PIN_CLUSTER" || r.kind == "TEXT") flight.visits.begin((r.members.ifEmpty { listOf(r.placeId) }).map { PinVisits.Spot(it, Double.NaN, Double.NaN, 0) }, r.at)
            }
        } catch (c: CancellationException) { flight.seeded = false; throw c } catch (_: Exception) { }
    }

    private fun isCardEvent(r: PlaceSeed): Boolean = when (r.kind) {
        "PHOTO", "IMAGE", "NEARBY", "MAYBE", "PIN", "PIN_CLUSTER" -> !com.rokid.context.memory.place.CardDelivery.isHeld(r.delivery) // a held row showed nothing
        "TEXT" -> r.delivery != com.rokid.context.memory.place.CardDelivery.NOT_SENT_LATE
        else -> false
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
        /** The real age of a reused fix (F2): the buckets above plus le5min and le10min. */
        internal fun bucketRealAgeMs(ms: Long): String = if (ms <= 60_000) bucketAgeMs(ms) else if (ms <= 300_000) "le5min" else if (ms <= 600_000) "le10min" else "gt10min"
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
        private val PACK_MISS = setOf(com.rokid.context.memory.place.CellOutcome.FAILED, com.rokid.context.memory.place.CellOutcome.OFFLINE, com.rokid.context.memory.place.CellOutcome.PARTIAL)
        const val FIX_REUSE_MAX_MS = 10 * 60_000L
        const val REUSE_GROWTH_M_PER_MIN = 4f
        const val DEDUPE_M = 30.0
        const val ONCE_PER_PLACE_MS = 30 * 60_000L
        const val WEAK_STREAK_RESET_MS = 10 * 60_000L
        /** 30 s x 2^(streak-1), at most 120 s. */
        internal fun weakWindowMs(streak: Int): Long = minOf(30_000L shl (streak - 1).coerceIn(0, 4), 120_000L)
        const val NEARBY_M = 100.0
        const val MAX_LIST = 16
        const val SHADOW_BUDGET_MS = 2_500L
        init { require(SHADOW_BUDGET_MS < MIN_CALL_GAP_MS) { "the shadow of attempt 1 must end before attempt 2 may call: it shares the one loaded photo" } }
        const val NONE = "NONE"
        /** The place id and title of a NO_MATCH row (no place to name). */
        const val NO_MATCH_ID = "none"
        const val NO_MATCH_TITLE = "No match"
        private val GENUINE_KINDS = setOf(com.rokid.context.ai.FailureKind.TRANSIENT, com.rokid.context.ai.FailureKind.AUTH, com.rokid.context.ai.FailureKind.REJECTED)
        internal const val ROLLBACK_TRIES = 3
        internal const val ROLLBACK_RETRY_MS = 200L
        private const val HANDLED_MAX = 32
    }
}
