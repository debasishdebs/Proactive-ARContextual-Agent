package com.rokid.context.work.frames.place

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.memory.db.PlaceLinkEntity
import com.rokid.context.provenance.ProvenanceGate
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.UserPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** What kind of card a history row records. */
/**
 * [MAYBE] (V2b): an offline guess card. [IMAGE] (layered confirmation): a Layer 1 accept, the photo matched a reference photo on the phone. [NO_MATCH]: an attempt that reached the candidate stage and showed nothing and
 * had no single nearby place to name. They live in the existing `kind` text column; no schema change.
 */
enum class HistoryKind { PHOTO, NEARBY, PIN, PIN_CLUSTER, TEXT, POSSIBLE, MAYBE, IMAGE, NO_MATCH }

/**
 * The authority an attempt acts under, captured at its START (before the frame, fix or pins are read): the durable `frame_forget_rev` (moved by every photo Forget, range Forget and Delete all), the wipe
 * generation and the Place Guide fence epoch. [PlaceCardHistory.add] refuses the row when any of them moved (v18, CH 6).
 */
data class HistoryAuthority(val rev: Long, val generation: Long, val fenceEpoch: Long) {
    companion object { /** Only for tests of the pure flow whose sink is a fake; a real history refuses it as soon as any Forget has run. */ val NONE = HistoryAuthority(0L, 0L, 0L) }
}

/**
 * One card event for the phone history. Holds no coordinates and no card line. [delivery] null = derived from the kind: POSSIBLE is never sent to the glasses
 * (`NOT_SENT_POSSIBLE`), everything else starts `PENDING` until the Hub reports it. [authority] is NOT optional: an unguarded add does not compile.
 */
data class PlaceHistoryEvent(
    val kind: HistoryKind,
    val placeId: String,
    val title: String,
    val members: List<String> = emptyList(),
    val frameId: String? = null,
    val cardId: String? = null,
    val confPct: Int? = null,
    val delivery: String? = null,
    val authority: HistoryAuthority,
    /** Developer IMPORT only: the ORIGINAL row id and time of a restored card (null = a new card: a fresh id and now). The same fences apply; the 90-day retention still prunes it. */
    val importedId: String? = null,
    val importedAt: Long? = null,
    /** Developer IMPORT only: asked INSIDE the add transaction (after the authority fences); false refuses the row (`reason=moment`), so a Moment Forget cannot land between the check and the write. */
    val momentGuard: (suspend () -> Boolean)? = null,
)

/**
 * The phone-only place card history over `place_card_log` in `context.db` (Room v18). It is independent of the memory tiers: nudges and answers expire with SHORT 24 h and place memories with LONG 30 d, history
 * lives 90 days / 500 rows. One guarded writer ([add]), one reader ([recent] / [get] / [photoIds]). Every Forget source deletes or unlinks its rows INSIDE the transaction that deletes the photo, the range or
 * everything else, so no deletion is ever owed. Every failure is logged (a literal line) and swallowed: history never blocks a card. Log lines carry kinds, states and counts only, never a title, an id or a position.
 */
class PlaceCardHistory(
    private val db: ContextDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** The Developer flag; OFF = no row is written. Deletions ignore it. */
    private val enabled: () -> Boolean = { true },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Is this user pin still active? Asked INSIDE the insert transaction. */
    private val pinActive: suspend (String) -> Boolean = { true },
    /** Pins whose removal is in flight ([PlaceGraph.savePins] step 2): an insert that names one is refused. In memory, ids only, never logged. */
    private val removing: Set<String> = emptySet(),
    private val generation: () -> Long = { 0L },
    private val fenceEpoch: () -> Long = { 0L },
) {
    /** Whether the history writes at all (the Developer flag); the import reports `history_off` instead of a silent refusal. */
    val isEnabled: Boolean get() = enabled()
    private val dao = db.placeCardLogDao()
    private val links = db.placeLinkDao()
    private val gate = ProvenanceGate(db)
    private val lastPruneDay = AtomicLong(-1)
    @Volatile private var opened = false

    private suspend fun <T> safe(what: String, fallback: T, body: suspend () -> T): T =
        try { body() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("place history $what failed"); fallback }

    /** The authority to act under: capture it at the START of an attempt, before anything is read. Throws when the database cannot be read (the attempt then writes no history). */
    suspend fun capture(): HistoryAuthority = HistoryAuthority(db.canvasSlice3Dao().frameForgetRev(), generation(), fenceEpoch())

    /**
     * Stores [e]; returns the row id, or null when the history is off, the write failed, or one of these no longer holds, all checked inside ONE `context.db` transaction (SQLite serialises it against every Forget):
     * `rev` (`frame_forget_rev` moved), `gen` (Delete all), `fence` (zone / switch / Forget fence), `frame` (the photo is forgotten; an expired photo is written with `photoGone = 1`), `pin` (a user pin it names is
     * being removed or gone). Prunes at most once a day, in the same transaction.
     */
    suspend fun add(e: PlaceHistoryEvent): String? {
        if (!enabled()) return null
        return safe("add", null as String?) {
            var count = 0
            val id = db.withTransaction {
                val a = e.authority
                val reason = when {
                    db.canvasSlice3Dao().frameForgetRev() != a.rev -> "rev"
                    generation() != a.generation -> "gen"
                    fenceEpoch() != a.fenceEpoch -> "fence"
                    else -> null
                }
                if (reason != null) { log("place history add refused reason=$reason"); return@withTransaction null }
                if (e.momentGuard != null && !e.momentGuard.invoke()) { log("place history add refused reason=moment"); return@withTransaction null }
                var photoGone = 0
                if (e.frameId != null) {
                    val ids = setOf(e.frameId)
                    if (gate.goneFrames(ids, requirePresent = false).isNotEmpty()) { // tombstoned: expired keeps the row (photo link gone), forgotten refuses it
                        if (gate.forgottenFrames(ids).isEmpty()) photoGone = 1 else { log("place history add refused reason=frame"); return@withTransaction null }
                    }
                }
                val pins = (listOf(e.placeId) + e.members).filter { UserPlace.isUserId(it) }.distinct()
                if (pins.any { it in removing || !pinActive(it) }) { log("place history add refused reason=pin"); return@withTransaction null }
                readyLine()
                val delivery = e.delivery ?: takeEarly(e.cardId) ?: if (e.kind == HistoryKind.POSSIBLE) CardDelivery.NOT_SENT_POSSIBLE else CardDelivery.PENDING // a held row carries its HELD_* token in [PlaceHistoryEvent.delivery]
                val row = PlaceCardLogEntity(
                    id = e.importedId ?: newId(), at = e.importedAt ?: clock(), placeId = e.placeId, title = e.title.take(TITLE_MAX), kind = e.kind.name,
                    members = e.members.takeIf { it.isNotEmpty() }?.joinToString(","), frameId = e.frameId, confPct = e.confPct?.coerceIn(0, 100),
                    cardId = e.cardId, delivery = delivery, photoGone = photoGone,
                )
                dao.put(row)
                linkMoment(e, photoGone)
                count = dao.cardLogCount()
                pruneNow(onlyOncePerDay = true)
                log("place history add kind=${e.kind.name} delivery=$delivery rows=$count")
                row.id
            }
            if (id != null) bump()
            id
        }
    }

    /**
     * Place object S1: a single-place card for a user pin links the pin to the Moment of its photo (`place_link`, INSERT OR IGNORE, role CARD), in the caller's transaction and so behind every fence the card row
     * passed. No link for a cluster card, a `wd:` or `osm:` place, a card with no photo, an expired photo or a Moment a Forget already tombstoned. Never throws for a bad time: no link is written then.
     */
    private suspend fun linkMoment(e: PlaceHistoryEvent, photoGone: Int) {
        val frame = e.frameId ?: return
        if (photoGone != 0 || e.members.isNotEmpty() || !UserPlace.isUserId(e.placeId)) return
        val at = links.capturedAtOf(frame) ?: return
        val moment = try { com.rokid.context.memory.moment.MomentId.ofMs(at) } catch (_: IllegalArgumentException) { return }
        if (links.momentTombstoned(moment) != 0) return
        links.insertIfAbsent(PlaceLinkEntity(e.placeId, moment, Math.floorDiv(at, 1000L) * 1000L, frame, "CARD", clock()))
    }

    /** Place object S2: the Moment ids a pin is linked to by cards (for the delete confirmation's count). */
    suspend fun linkedMomentIds(pinId: String): List<String> = links.momentIdsOf(pinId)

    /**
     * The rows of removed pins and the cluster rows that list them: ONE transaction, run by [PlaceGraph.savePins] BEFORE the pin store is written. Throws when it fails (the caller then refuses the pin removal).
     */
    suspend fun deletePins(pinIds: Collection<String>): Int {
        if (pinIds.isEmpty()) return 0
        val n = db.withTransaction { links.deleteLinksForPlaces(pinIds.distinct()); dao.deleteCardLogForPins(pinIds.distinct()) }
        log("place history pin removed=$n"); bump()
        return n
    }

    /**
     * Sets the delivery state of the row of [cardId] (a card the Hub sent): one UPDATE, so a row a Forget deleted is never brought back. The Hub can report a card before the sampler has written its row (publish
     * comes first): the state is then remembered (bounded, in memory, ids only) and used by the guarded [add] for that [cardId].
     */
    suspend fun markDelivery(cardId: String, state: String): Boolean = if (!enabled()) false else safe("mark", false) {
        val ok = dao.setCardDelivery(cardId, state) > 0
        if (!ok) remember(cardId, state) else bump()
        ok
    }

    /** Sets [state] only when the row is still PENDING (the Hub's expiry or replacement path). Remembered like [markDelivery] when no row exists yet. */
    suspend fun markIfPending(cardId: String, state: String): Boolean = if (!enabled()) false else safe("mark", false) {
        val ok = dao.setCardDeliveryIfPending(cardId, state) > 0
        if (!ok && dao.cardLogByCard(cardId) == null) remember(cardId, state)
        if (ok) bump()
        ok
    }

    private val early = object : LinkedHashMap<String, String>() { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > EARLY_MAX }
    private fun remember(cardId: String, state: String) { synchronized(early) { early[cardId] = state } }
    private fun takeEarly(cardId: String?): String? = cardId?.let { synchronized(early) { early.remove(it) } }

    suspend fun recent(limit: Int = MAX_ROWS): List<PlaceCardLogEntity> = safe("read", emptyList()) { dao.recentCardLog(limit.coerceIn(1, MAX_ROWS)) }
    suspend fun get(id: String): PlaceCardLogEntity? = safe("read", null as PlaceCardLogEntity?) { dao.cardLog(id) }
    suspend fun delete(id: String): Boolean = safe("delete", false) { (dao.deleteCardLog(id) > 0).also { if (it) bump() } }

    /** Rows since [sinceMs] (wall clock), newest first: seeds the in-memory dedupe and visits at process start. */
    suspend fun seedRecent(sinceMs: Long): List<PlaceCardLogEntity> = safe("read", emptyList()) { dao.cardLogSince(sinceMs) }

    /** Deletes rows older than 90 days and keeps the newest 500. [onlyOncePerDay] makes a second call the same day a no-op. */
    suspend fun prune(onlyOncePerDay: Boolean = false): Int = safe("prune", 0) { db.withTransaction { pruneNow(onlyOncePerDay) }.also { if (it > 0) bump() } }

    private suspend fun pruneNow(onlyOncePerDay: Boolean): Int {
        val today = clock() / DAY_MS
        if (onlyOncePerDay && lastPruneDay.get() == today) return 0
        lastPruneDay.set(today)
        val removed = dao.deleteCardLogBefore(clock() - RETAIN_MS) + dao.trimCardLogTo(MAX_ROWS)
        if (removed > 0) log("place history prune removed=$removed")
        return removed
    }

    /** Quota eviction (K6): the photos the history names. Throws when the database cannot be read (the caller then evicts nothing: fail closed). */
    suspend fun photoIds(): List<String> = dao.cardLogFrameIds()

    private suspend fun readyLine() { if (!opened) { opened = true; log("place history ready rows=${dao.cardLogCount()}") } }

    private fun bump() { _changes.value = _changes.value + 1 }

    companion object {
        private val _changes = MutableStateFlow(0L)
        /**
         * Process-wide change counter: bumped after every committed write of this class (add, delivery update, delete, prune, pin removal). Event-driven UIs (the Place Guide list, Home's "Last place card")
         * collect it instead of polling; a new collector gets the current value at once, which doubles as the refresh on resume. A Forget deletes inside its own transaction and does not bump it: the
         * Forget listeners refresh the screens.
         */
        val changes: StateFlow<Long> get() = _changes
        /** Tells the screens to re-read (a Forget deleted rows inside its own transaction). */
        fun notifyChanged() { _changes.value = _changes.value + 1 }
        const val TITLE_MAX = 80
        const val MAX_ROWS = 500
        const val RETAIN_MS = 90L * 24 * 3_600_000
        private const val DAY_MS = 24L * 3_600_000
        private const val EARLY_MAX = 32
    }
}
