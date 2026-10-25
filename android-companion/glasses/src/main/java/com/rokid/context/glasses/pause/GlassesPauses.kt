package com.rokid.context.glasses.pause

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** What a phone push changed: records newly stored, and ids removed (phone resumes, ended provisional records). */
data class MergeResult(val added: List<PauseRecord>, val removed: Set<String>)

sealed interface PauseToggle {
    data class Paused(val record: PauseRecord) : PauseToggle
    data class Resumed(val clears: Set<String>) : PauseToggle
}

/**
 * Glasses-local pause enforcement (spec §1.7 exception, §5.5). A double-tap pause applies at once and persists, linked
 * or not; capture runs only when no record is active on the glasses clock; before the store is loaded nothing is
 * allowed. A Resume names only the records the glasses know, and is repeated in each `pc_hello` until the phone stops
 * listing the ids. Phone pushes merge as [mergeFromPhone] describes; quiet hours pause on their own, offline, from the
 * last settings ([quiet]).
 */
class GlassesPauses(
    private val store: PauseStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = { PcLog.w(TAG, it) },
    private val quiet: QuietHoursClock? = null,
) {
    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<PauseRecord>>(emptyList())
    val records: StateFlow<List<PauseRecord>> = _records
    /**
     * Ids the wearer resumed locally (-> glasses-clock resume instant); a phone push that still lists one must not
     * re-pause capture. Each stays until a phone push no longer lists it (the phone has applied the Resume), and is
     * re-sent in every `pc_hello` until then, so a Resume made while the link was down is not lost.
     */
    @Volatile private var tombstones: Map<String, Long> = emptyMap()
    /** Phone timed records stored indefinite because their push was not clock-exact; never sent back in `pc_hello`. */
    @Volatile private var provisional: Set<String> = emptySet()
    /** Ids the phone resumed (-> glasses-clock instant it arrived), kept 7 days; never re-added. */
    @Volatile private var phoneResumed: Map<String, Long> = emptyMap()
    /** Quiet-hours occurrence start -> the record that covered it; a handled occurrence never pauses again. */
    @Volatile private var quietHandled: Map<Long, String> = emptyMap()
    @Volatile private var loaded = false
    /** pauseId -> who made it (log text only, persisted beside the records); an id without an entry is [PauseSource.PERSISTED]. */
    @Volatile private var sources: Map<String, PauseSource> = emptyMap()
    /** Ids that came back from the store at this process start. */
    @Volatile private var restored: Set<String> = emptySet()

    /** Loads the store and pauses for a quiet-hours occurrence that has started (CaptureService calls this per capture). */
    suspend fun ensureLoaded() = mutex.withLock {
        loadLocked()
        if (quietLocked(clock())) save(_records.value)
    }

    /** Resumes the phone has not confirmed yet (pauseId -> glasses-clock instant), for `Hello.resumedAtMs`. */
    fun resumedAtMs(): Map<String, Long> = tombstones

    /** Records for `Hello.pauses`: provisional ones are the phone's own (its clock); echoing them back would widen them. */
    fun helloRecords(): List<PauseRecord> = provisional.let { prov -> _records.value.filter { it.pauseId !in prov } }

    fun active(nowMs: Long = clock()): List<PauseRecord> = _records.value.filter { it.isActiveAt(nowMs) }

    private fun sourceOf(r: PauseRecord): PauseSource =
        if (r.pauseId == PauseStore.UNREADABLE_ID) PauseSource.STORE_UNREADABLE else sources[r.pauseId] ?: PauseSource.PERSISTED

    private fun labelOf(r: PauseRecord) = sourceOf(r).name + if (r.pauseId in restored) "/restored" else ""

    private fun labelOf(list: List<PauseRecord>) = list.map { labelOf(it) }.distinct().sorted().joinToString("+")

    /**
     * The word for WHO holds capture paused right now: "NOT_LOADED" before the store is read, "none" when nothing is, else the sources of the active records
     * (`USER_GLASSES`, `USER_PHONE`, `QUIET_HOURS`, `STORE_UNREADABLE`, `PERSISTED`), each with `/restored` when it came back from the store at process start. Memory reads only.
     */
    fun sourceLabel(nowMs: Long = clock()): String {
        if (!loaded) return "NOT_LOADED"
        val act = active(nowMs)
        if (act.isEmpty()) return if (unhandledQuiet(nowMs)) PauseSource.QUIET_HOURS.name else "none"
        return labelOf(act)
    }

    /** Also closed inside a quiet-hours occurrence not yet handled (not yet turned into a record). */
    fun captureAllowed(nowMs: Long = clock()): Boolean = loaded && active(nowMs).isEmpty() && !unhandledQuiet(nowMs)

    private fun unhandledQuiet(nowMs: Long): Boolean {
        val occ = quiet?.occurrenceAt(nowMs) ?: return false
        return occ.first !in quietHandled
    }

    suspend fun toggle(): PauseToggle = mutex.withLock {
        loadLocked()
        val now = clock()
        quietLocked(now) // inside quiet hours the tap resumes the occurrence instead of adding a pause
        val active = active(now)
        if (active.isNotEmpty()) {
            val clears = active.map { it.pauseId }.toSet()
            tombstones = tombstones + clears.associateWith { now }
            provisional = provisional - clears
            log("pause resumed n=${clears.size} src=${labelOf(active)}")
            save(_records.value.filter { it.pauseId !in clears && it.isActiveAt(now) })
            PauseToggle.Resumed(clears)
        } else {
            val r = PauseRecord(newId(), PauseOrigin.GLASSES, createdAtMs = now, untilMs = null)
            sources = sources + (r.pauseId to PauseSource.USER_GLASSES)
            log("pause added src=${PauseSource.USER_GLASSES} until=none")
            save(_records.value.filter { it.isActiveAt(now) } + r)
            PauseToggle.Paused(r)
        }
    }

    /**
     * One phone push (`pc_settings`, or a `pc_state` read as inexact):
     * - every id in `resumedPauseIds` is removed (any origin) and never re-added, nor is a local tombstone;
     * - exact (`pausesExact`): a known id merges most-restrictive, a known provisional id takes the phone's value, an
     *   unknown active id is added, and a provisional id the phone no longer lists has ended there and is removed;
     * - inexact: an unknown timed non-GLASSES record is stored indefinite and provisional (fail-closed); other unknown
     *   active records are added; a known id is never changed.
     */
    suspend fun mergeFromPhone(s: HudSettings): MergeResult = mutex.withLock {
        loadLocked()
        val now = clock()
        val resumedNow = s.resumedPauseIds.toSet()
        val newPhoneResumed = (phoneResumed + (resumedNow - phoneResumed.keys).associateWith { now })
            .filterValues { it >= now - RESUMED_KEEP_MS }
        val removed = mutableSetOf<String>()
        val recs = LinkedHashMap<String, PauseRecord>()
        _records.value.forEach { r -> if (r.pauseId in resumedNow) removed += r.pauseId else recs[r.pauseId] = r }
        var prov = provisional - removed
        val listed = s.pauses.map { it.pauseId }.toSet()
        // A tombstone ends only once the phone no longer lists the id. No time limit: after a long offline spell the
        // first push still lists it (the hello naming it is handled after), and must not re-pause capture.
        val liveTombs = tombstones.filterKeys { it in listed }
        val blocked = liveTombs.keys + newPhoneResumed.keys
        val added = mutableListOf<PauseRecord>()
        for (r in s.pauses) {
            if (r.pauseId in blocked) continue
            val known = recs[r.pauseId]
            when {
                known != null && s.pausesExact && r.pauseId in prov -> { recs[r.pauseId] = r; prov = prov - r.pauseId }
                known != null && s.pausesExact -> recs[r.pauseId] = known.copy(untilMs = mostRestrictive(known.untilMs, r.untilMs))
                known != null -> Unit
                !s.pausesExact && r.untilMs != null && r.origin != PauseOrigin.GLASSES -> {
                    val held = r.copy(untilMs = null)
                    recs[r.pauseId] = held; prov = prov + r.pauseId; added += held
                }
                r.isActiveAt(now) -> { recs[r.pauseId] = r; added += r }
            }
        }
        if (s.pausesExact) {
            val ended = prov.filter { it !in listed }.toSet()
            ended.forEach { recs.remove(it) }
            removed += ended
            prov = prov - ended
        }
        if (added.isNotEmpty()) sources = sources + added.associate { it.pauseId to PauseSource.of(it.origin) }
        if (added.isNotEmpty() || removed.isNotEmpty()) log("pause phone push added=${added.size} removed=${removed.size}${if (added.isNotEmpty()) " src=" + added.map { PauseSource.of(it.origin).name }.distinct().sorted().joinToString("+") else ""}")
        val before = PauseState(_records.value, tombstones, provisional, phoneResumed, quietHandled)
        tombstones = liveTombs
        provisional = prov
        phoneResumed = newPhoneResumed
        _records.value = recs.values.toList()
        quietLocked(now)
        if (PauseState(_records.value, tombstones, provisional, phoneResumed, quietHandled) != before) save(_records.value)
        MergeResult(added, removed)
    }

    /** Inside an unhandled quiet occurrence: adopt a live QUIET_HOURS record, or mint one. True if anything changed. */
    private fun quietLocked(now: Long): Boolean {
        val occ = quiet?.occurrenceAt(now) ?: return false
        if (occ.first in quietHandled) return false
        val live = active(now).firstOrNull { it.origin == PauseOrigin.QUIET_HOURS }
        val id = live?.pauseId ?: newId().also { id ->
            _records.value = _records.value + PauseRecord(id, PauseOrigin.QUIET_HOURS, occ.first, occ.last + 1)
            sources = sources + (id to PauseSource.QUIET_HOURS)
            log("pause added src=${PauseSource.QUIET_HOURS} until=occurrence-end")
        }
        quietHandled = (quietHandled + (occ.first to id)).entries.sortedByDescending { it.key }.take(QUIET_KEEP)
            .associate { it.key to it.value }
        return true
    }

    private fun mostRestrictive(a: Long?, b: Long?): Long? = if (a == null || b == null) null else maxOf(a, b)

    private suspend fun loadLocked() {
        if (loaded) return
        _records.value = try {
            store.read().also {
                tombstones = it.tombstones
                provisional = it.provisional
                phoneResumed = it.phoneResumed
                quietHandled = it.quietHandled
                sources = it.sources.mapValues { (_, v) -> PauseSource.parse(v) }
            }.records
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("pause store unreadable (${e.javaClass.simpleName}): staying paused")
            listOf(PauseRecord(PauseStore.UNREADABLE_ID, PauseOrigin.GLASSES, clock(), null))
        }
        restored = _records.value.map { it.pauseId }.toSet()
        loaded = true
        log("pause loaded active=${active().size} src=${sourceLabel()}")
    }

    /** Memory first, so enforcement holds even if the disk write fails. */
    private suspend fun save(list: List<PauseRecord>) {
        _records.value = list
        try {
            store.write(PauseState(list, tombstones, provisional, phoneResumed, quietHandled, list.map { it.pauseId }.toSet().let { ids -> sources.filterKeys { it in ids }.mapValues { it.value.name } }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("pause store write failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val TAG = "PcPause"
        const val RESUMED_KEEP_MS = 7 * 24 * 3_600_000L
        const val QUIET_KEEP = 14
    }
}
