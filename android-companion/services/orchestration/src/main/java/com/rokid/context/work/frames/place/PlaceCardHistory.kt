package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.PlaceCardLogEntity
import com.rokid.context.memory.place.PlacesDao
import com.rokid.context.memory.place.UserPlace
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** What kind of card a history row records. */
enum class HistoryKind { PHOTO, NEARBY, PIN, PIN_CLUSTER, TEXT, POSSIBLE }

/**
 * One card event for the phone history (spec 2026-11-24 B1). Holds no coordinates and no card line. [delivery] null = derived from the kind: POSSIBLE is never sent to the glasses
 * (`NOT_SENT_POSSIBLE`), everything else starts `PENDING` until the Hub reports it.
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
)

/**
 * The phone-only place card history over `place_card_log` in the encrypted `places.db` (spec 2026-11-24 B1). It is independent of the memory tiers: nudges and answers expire with SHORT 24 h and
 * place memories with LONG 30 d, history lives 90 days / 500 rows. Every failure is logged (a literal line) and swallowed: history never blocks a card. Log lines carry kinds, states and counts only,
 * never a title, an id or a position.
 */
class PlaceCardHistory(
    private val dao: PlacesDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val enabled: () -> Boolean = { true },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** The durable record of deletions this history still owes (P1-5). Production: a key in the `place_guide` prefs; the default is in memory (tests of the pure flow). */
    private val owed: HistoryOwed = InMemoryHistoryOwed(),
    /** Is this user pin still active (P1-4)? Asked INSIDE the write lock, so an insertion can never land after the deletion of its pin. */
    private val pinActive: suspend (String) -> Boolean = { true },
) {
    private val lastPruneDay = AtomicLong(-1)
    @Volatile private var opened = false

    /** Serialises every insertion and every deletion (a pin's removal cannot interleave with a row that names the pin). */
    private val writeLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun <T> safe(what: String, fallback: T, body: suspend () -> T): T =
        try { body() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("place history $what failed"); fallback }

    /**
     * Stores [e]; returns the row id, or null when the history is off, the write failed, a user pin the row names is no longer active, or [guard] (asked under the same lock, last) says the authority it was
     * captured under is gone (a Forget / Delete all in between). Prunes at most once a day.
     */
    suspend fun add(e: PlaceHistoryEvent, guard: (suspend () -> Boolean)? = null): String? {
        if (!enabled()) return null
        return safe("add", null as String?) {
            writeLock.withLock {
                val pins = (listOf(e.placeId) + e.members).filter { UserPlace.isUserId(it) }.distinct()
                if (pins.any { !pinActive(it) } || (guard != null && !guard())) { log("place history add refused"); return@withLock null }
                readyLine()
                val delivery = e.delivery ?: takeEarly(e.cardId) ?: if (e.kind == HistoryKind.POSSIBLE) CardDelivery.NOT_SENT_POSSIBLE else CardDelivery.PENDING
                val row = PlaceCardLogEntity(
                    id = newId(), at = clock(), placeId = e.placeId, title = e.title.take(TITLE_MAX), kind = e.kind.name,
                    members = e.members.takeIf { it.isNotEmpty() }?.joinToString(","), frameId = e.frameId, confPct = e.confPct?.coerceIn(0, 100),
                    cardId = e.cardId, delivery = delivery,
                )
                dao.putCardLog(row)
                log("place history add kind=${e.kind.name} delivery=$delivery rows=${dao.cardLogCount()}")
                bump()
                prune(onlyOncePerDay = true)
                row.id
            }
        }
    }

    /**
     * Records every op in [ops] as OWED (durable) BEFORE running it, runs them under the write lock, and clears each op only when it succeeded. If any failed, the others still ran, the failed ones stay owed
     * and [HistoryDeletionPending] is thrown: a Forget / Delete all must not report done while private rows may remain. [retryOwed] finishes them at the next start.
     */
    private suspend fun oweAll(what: String, ops: List<String>) {
        if (ops.isEmpty()) return
        ops.forEach { owed.add(it) }
        var failed = false
        for (op in ops) {
            try { writeLock.withLock { exec(op) }; owed.remove(op) }
            catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (_: Exception) { failed = true; log("place history $what failed") }
        }
        bump()
        if (failed) throw HistoryDeletionPending()
    }

    private suspend fun exec(op: String) {
        val kind = op.substringBefore(':'); val rest = op.substringAfter(':')
        when (kind) {
            OP_FRAMES -> { val ids = rest.split(',').filter { it.isNotEmpty() }; val n = ids.chunked(CHUNK).sumOf { dao.deleteCardLogForFrames(it) }; log("place history forget frames=${ids.size} removed=$n") }
            OP_RANGE -> { val (a, b) = rest.split(':'); log("place history range removed=${dao.deleteCardLogBetween(a.toLong(), b.toLong())}") }
            OP_ALL -> log("place history cleared reason=delete_all removed=${dao.deleteCardLogBetween(0L, rest.toLong())}") // rows made after the Delete all are never taken by a later retry
            OP_PIN -> log("place history pin removed=${dao.deleteCardLogForPin(rest)}")
            else -> Unit // an op of a later build: dropped
        }
    }

    /** True when a deletion is still owed (an earlier Forget / Delete all / pin removal could not finish). A flag read only. */
    fun hasOwed(): Boolean = owed.all().isNotEmpty()

    /** Runs every owed deletion again (at each start, after a failure). True = nothing is owed any more. Never throws except for cancellation. */
    suspend fun retryOwed(): Boolean {
        for (op in owed.all().sorted()) {
            try { writeLock.withLock { exec(op) }; owed.remove(op) }
            catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (_: Exception) { log("place history retry failed") }
        }
        bump()
        return !hasOwed()
    }

    /**
     * Sets the delivery state of the row of [cardId] (a card the Hub sent). The Hub can report a card before the sampler has written its row (publish comes first): the state is then remembered
     * (bounded, in memory, ids only) and used by [add] for that [cardId].
     */
    suspend fun markDelivery(cardId: String, state: String): Boolean = safe("mark", false) {
        val ok = dao.setCardDelivery(cardId, state) > 0
        if (!ok) remember(cardId, state) else bump()
        ok
    }

    /** Sets [state] only when the row is still PENDING (the Hub's expiry or replacement path). Remembered like [markDelivery] when no row exists yet. */
    suspend fun markIfPending(cardId: String, state: String): Boolean = safe("mark", false) {
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
    suspend fun prune(onlyOncePerDay: Boolean = false): Int = safe("prune", 0) {
        val today = clock() / DAY_MS
        if (onlyOncePerDay && lastPruneDay.get() == today) return@safe 0
        lastPruneDay.set(today)
        val removed = dao.deleteCardLogBefore(clock() - RETAIN_MS) + dao.trimCardLogTo(MAX_ROWS)
        if (removed > 0) { log("place history prune removed=$removed"); bump() }
        removed
    }

    /** Quota eviction (K6): the photos the history names. Throws when the database cannot be read (the caller then evicts nothing: fail closed). */
    suspend fun photoIds(): List<String> = dao.cardLogFrameIds()

    /** A user Forget of these photos: their rows go. */
    suspend fun onFramesForgotten(frameIds: List<String>) = oweAll("forget", frameIds.distinct().chunked(CHUNK).map { OP_FRAMES + ":" + it.joinToString(",") })

    /** Photo retention: only `photoGone` = 1; the row stays WITH its `frameId` (private lineage, so a later Forget of the photo still deletes it; the UI never loads a photo of a gone row). */
    suspend fun onPhotosExpired(frameIds: List<String>) = safe("expire", Unit) {
        val marked = frameIds.distinct().chunked(CHUNK).sumOf { dao.markCardLogPhotoGone(it) }
        log("place history expire frames=${frameIds.size} marked=$marked")
        bump()
    }

    suspend fun onRangeForgotten(fromMs: Long, toMs: Long) = oweAll("range", listOf("$OP_RANGE:$fromMs:$toMs"))

    /** Delete all: every row made up to now goes (a failure throws [HistoryDeletionPending] and stays owed). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun clearAll(reason: String = "delete_all") = oweAll("clear", listOf("$OP_ALL:${clock()}"))

    /** Pins were removed: their rows and the cluster rows that list them go. Serialised with [add] (which asks [pinActive] under the same lock). */
    suspend fun onPinRemoved(pinIds: Collection<String>) = oweAll("pin", pinIds.distinct().map { "$OP_PIN:$it" })

    private suspend fun readyLine() { if (!opened) { opened = true; log("place history ready rows=${dao.cardLogCount()}") } }

    private fun bump() { _changes.value = _changes.value + 1 }

    companion object {
        private val _changes = MutableStateFlow(0L)
        /**
         * Process-wide change counter: bumped after every committed write (add, delivery update, delete, Forget, retention, Delete all, pin removal). Event-driven UIs (the Place Guide list, Home's
         * "Last place card") collect it instead of polling; a new collector gets the current value at once, which doubles as the refresh on resume.
         */
        val changes: StateFlow<Long> get() = _changes
        const val TITLE_MAX = 80
        const val MAX_ROWS = 500
        const val RETAIN_MS = 90L * 24 * 3_600_000
        private const val DAY_MS = 24L * 3_600_000
        private const val CHUNK = 500
        private const val EARLY_MAX = 32
        private const val OP_FRAMES = "f"
        private const val OP_RANGE = "r"
        private const val OP_ALL = "a"
        private const val OP_PIN = "p"
    }
}

/** A deletion the history owes: it could not be completed, so private rows may remain. Thrown by the Forget / Delete all / pin-removal paths; retried at every start ([PlaceCardHistory.retryOwed]). */
class HistoryDeletionPending : RuntimeException("place history deletion pending")

/** The durable list of deletions still owed (op tokens carry frame ids, times or pin ids; never logged). */
interface HistoryOwed {
    fun add(op: String)
    fun remove(op: String)
    fun all(): Set<String>
}

class InMemoryHistoryOwed : HistoryOwed {
    private val ops = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override fun add(op: String) { ops.add(op) }
    override fun remove(op: String) { ops.remove(op) }
    override fun all(): Set<String> = ops.toSet()
}

/** Stored in the `place_guide` prefs (kept by Delete all, excluded from backup and device transfer), committed synchronously so an owed deletion survives the process. */
class PrefsHistoryOwed(private val prefs: android.content.SharedPreferences) : HistoryOwed {
    private val lock = Any()
    override fun add(op: String) { synchronized(lock) { prefs.edit().putStringSet(KEY, all() + op).commit() } }
    override fun remove(op: String) { synchronized(lock) { val a = all(); if (op in a) prefs.edit().putStringSet(KEY, a - op).commit() } }
    override fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    companion object { const val KEY = "history_deletions_owed" }
}
