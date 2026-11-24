package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.PlaceCardLogEntity
import com.rokid.context.memory.place.PlacesDao
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
) {
    private val lastPruneDay = AtomicLong(-1)
    @Volatile private var opened = false

    private suspend fun <T> safe(what: String, fallback: T, body: suspend () -> T): T =
        try { body() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("place history $what failed"); fallback }

    /** Stores [e]; returns the row id, or null when the history is off or the write failed. Prunes at most once a day. */
    suspend fun add(e: PlaceHistoryEvent): String? {
        if (!enabled()) return null
        return safe("add", null as String?) {
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
    suspend fun onFramesForgotten(frameIds: List<String>) = safe("forget", Unit) {
        val removed = frameIds.distinct().chunked(CHUNK).sumOf { dao.deleteCardLogForFrames(it) }
        log("place history forget frames=${frameIds.size} removed=$removed")
        bump()
    }

    /** Photo retention: only `photoGone` = 1; the row stays WITH its `frameId` (private lineage, so a later Forget of the photo still deletes it; the UI never loads a photo of a gone row). */
    suspend fun onPhotosExpired(frameIds: List<String>) = safe("expire", Unit) {
        val marked = frameIds.distinct().chunked(CHUNK).sumOf { dao.markCardLogPhotoGone(it) }
        log("place history expire frames=${frameIds.size} marked=$marked")
        bump()
    }

    suspend fun onRangeForgotten(fromMs: Long, toMs: Long) = safe("range", Unit) {
        log("place history range removed=${dao.deleteCardLogBetween(fromMs, toMs)}")
        bump()
    }

    /** Delete all: every row goes. */
    suspend fun clearAll(reason: String = "delete_all") = safe("clear", Unit) {
        log("place history cleared reason=$reason removed=${dao.deleteAllCardLog()}")
        bump()
    }

    /** Pins were removed: their rows and the cluster rows that list them go. */
    suspend fun onPinRemoved(pinIds: Collection<String>) = safe("pin", Unit) {
        val removed = pinIds.distinct().sumOf { dao.deleteCardLogForPin(it) }
        log("place history pin removed=$removed")
        bump()
    }

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
    }
}
