package com.rokid.context.phone.zone

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.zone.FixCadence
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.TripCadence
import com.rokid.context.core.model.zone.ZoneEvaluation
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.core.model.zone.ZoneFix
import com.rokid.context.core.model.zone.ZoneMemo
import com.rokid.context.core.model.zone.ZonePause
import com.rokid.context.core.model.zone.ZoneReason
import com.rokid.context.core.model.zone.ZoneState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the controller needs of the hub: the stored `zone:` pause records and a way to add, end and publish them. */
interface ZoneSink {
    suspend fun activeZoneRecords(): List<PauseRecord>
    suspend fun add(record: PauseRecord)
    /** Ends exactly these `zone:` records now. */
    suspend fun end(ids: Set<String>)
    /** The zone state changed: push the settings (`zoneApproach`, the pauses) and a frame. */
    suspend fun publish()
}

/** The phone notification that says why capture is paused: zone NAMES only, never a place or a coordinate. */
interface ZoneNotifier {
    fun paused(zoneNames: List<String>, locationUnknown: Boolean)
    fun cleared()
}

/** [PROTECTED]: the stored zones cannot be read (all zones unknown). [NOT_READY]: the zones are not judged yet (before start or while recovering). [STORE_FAILED]: the change was not stored. All three refuse (fail closed). */
enum class ZoneChange { OK, BLOCKED_INSIDE, INVALID, NOT_FOUND, PROTECTED, NOT_READY, STORE_FAILED }

data class ZoneSnapshot(
    val zones: List<PauseZone>, val evaluation: ZoneEvaluation, val protectedMode: Boolean = false,
    /** False until the hub has started the controller and judged the zones once: [zones] is then empty although zones may be saved (the map screen reads the store instead). */
    val monitoring: Boolean = true,
) {
    /** The phone is within a zone's approach band or inside it (also by fail-closed): sent to the glasses as `HudSettings.zoneApproach`. */
    val approach: Boolean get() = evaluation.overall >= ZoneState.APPROACH
    /** The zones cannot be read ([ZoneStore.corrupt]): all zones are unknown and capture is held. */
}

/**
 * Runs the pause zones on the phone: feeds fixes to the [ZoneEvaluator] and keeps the `zone:<id>:<n>` PHONE pause records equal to the zones that read INSIDE (adds on entry, ends by
 * name on exit). A zone cannot be disabled, edited or deleted while it reads INSIDE (fail-closed readings included). Everything runs under one mutex. No coordinates are kept beyond the
 * evaluator's last fix; logs carry counts and state names only.
 *
 * The map UI (next package) uses [snapshot] (state per zone), [upsert], [setEnabled], [remove] and [addPreset]; it never writes pauses itself.
 */
class ZoneController(
    private val store: ZoneStore,
    private val sink: ZoneSink,
    private val notifier: ZoneNotifier,
    /** Monotonic ms (elapsed realtime): the clock of fixes and of staleness. */
    private val elapsed: () -> Long,
    /** Wall clock ms: the pause records. */
    private val wall: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** The set of zones changed: the hub service re-evaluates its foreground location type. */
    private val onZonesChanged: () -> Unit = {},
    /** The persisted zone state (latched state, last time a fix proved us clear, last fix time). Null = kept in memory only (tests). */
    private val stateStore: ZoneStateStore? = null,
) {
    private val mutex = Mutex()
    private val evaluator = ZoneEvaluator()
    private var published: Boolean? = null
    private var notified: Set<String> = emptySet()
    @Volatile private var ready = false
    /** Per zone: the memo that survives a process death (wall times only). */
    private val memo = LinkedHashMap<String, ZoneMemo>()
    private var stateBlind = false
    private var memoSavedAtMs = Long.MIN_VALUE
    private var memoDirty = false
    private var lastEnforce: Pair<Boolean, Boolean>? = null

    private val _snapshot = MutableStateFlow(ZoneSnapshot(emptyList(), ZoneEvaluation(emptyList()), monitoring = false))
    val snapshot: StateFlow<ZoneSnapshot> = _snapshot
    private val _cadence = MutableStateFlow<FixCadence?>(null)
    /** How fixes are wanted now (null = no zone, no fixes). */
    val cadence: StateFlow<FixCadence?> = _cadence

    /**
     * The value for `HudSettings.zoneApproach`: within a zone's approach band or inside it. Before the first reconcile the zones are unjudged, so any enabled zone counts (fail closed).
     */
    val approach: Boolean get() = store.corrupt || ZoneHold.held || if (ready) _snapshot.value.approach else wantsLocation()

    /** Capture is held in memory because a zone pause could not be stored or the zones cannot be read: sent to the glasses as `HudSettings.zoneEnforce`. */
    val enforcing: Boolean get() = ZoneHold.held || store.corrupt

    init { ZoneHold.set(null) }

    /** Called synchronously when the hub (re)starts: until [start] has judged the zones again, the flag fails closed. */
    fun beginRestart() { ready = false }

    /** Any enabled zone exists: location fixes are wanted (the service holds the location type for it). Cheap; safe from any thread. */
    fun wantsLocation(): Boolean = store.zones().any { it.enabled }

    /** Loads the stored zones and reconciles the pause records with them (hub start, process start). Until the first fix every enabled zone reads INSIDE. */
    suspend fun start() = locked {
        evaluator.setZones(store.zones(), elapsed())
        evaluator.restart() // nothing is known after a start or a recovery, even in a process that survived: every zone reads INSIDE until two qualifying fixes
        memo.clear()
        stateBlind = false
        stateStore?.let { memo.putAll(it.load()); stateBlind = it.unreadable }
        reconcile()
    }

    suspend fun onFix(fix: ZoneFix) = locked {
        if (evaluator.onFix(fix, elapsed())) {
            val ev = evaluator.evaluate(elapsed())
            val fixWall = (wall() - (elapsed() - fix.timeMs).coerceAtLeast(0L))
            for (st in ev.zones) {
                val old = memo[st.zoneId]
                val clear = if (st.lastClearMs == fix.timeMs) fixWall else old?.clearWallMs
                val m = ZoneMemo(st.measured.name, clear, fixWall)
                if (m.state != old?.state || m.clearWallMs != old.clearWallMs) memoDirty = true
                memo[st.zoneId] = m
            }
            saveMemo()
        }
        reconcile()
    }

    suspend fun onLocationAvailable(ok: Boolean) = locked { evaluator.onLocationAvailable(ok, elapsed()); reconcile() }

    /** Time passes with no fix: staleness is judged, records are repaired if a write failed. */
    suspend fun tick() = locked { reconcile() }

    fun zones(): List<PauseZone> = store.zones()

    /** The newest fix, for the map's "you are here" dot only (in memory, never stored or logged); null when none has arrived since the process started. */
    fun lastFix(): ZoneFix? = evaluator.lastFix

    /** Adds or replaces a zone. An existing zone that reads INSIDE cannot be changed (it could be shrunk or moved out from under the phone). */
    suspend fun upsert(zone: PauseZone): ZoneChange = locked {
        if (!zone.isValid()) return@locked ZoneChange.INVALID
        val all = store.zones()
        if (store.corrupt) return@locked ZoneChange.PROTECTED
        if (all.any { it.id == zone.id }) blocked(zone.id)?.let { return@locked it }
        if (!store.write(all.filter { it.id != zone.id } + zone)) return@locked ZoneChange.STORE_FAILED
        changed()
        ZoneChange.OK
    }

    /** Stores [zones] as the whole list: the explicit way out of the protected (unreadable) state. */
    suspend fun replaceAll(zones: List<PauseZone>): ZoneChange = locked {
        if (!store.replaceAll(zones)) return@locked ZoneChange.STORE_FAILED
        changed()
        ZoneChange.OK
    }

    suspend fun setEnabled(id: String, enabled: Boolean): ZoneChange = locked {
        if (store.corrupt) return@locked ZoneChange.PROTECTED
        val z = store.zones().firstOrNull { it.id == id } ?: return@locked ZoneChange.NOT_FOUND
        if (z.enabled == enabled) return@locked ZoneChange.OK
        if (!enabled) blocked(id)?.let { return@locked it }
        if (!store.write(store.zones().map { if (it.id == id) it.copy(enabled = enabled) else it })) return@locked ZoneChange.STORE_FAILED
        changed()
        ZoneChange.OK
    }

    suspend fun remove(id: String): ZoneChange = locked {
        if (store.corrupt) return@locked ZoneChange.PROTECTED
        if (store.zones().none { it.id == id }) return@locked ZoneChange.NOT_FOUND
        blocked(id)?.let { return@locked it }
        if (!store.write(store.zones().filter { it.id != id })) return@locked ZoneChange.STORE_FAILED
        changed()
        ZoneChange.OK
    }

    /** Adds a [com.rokid.context.core.model.zone.ZonePresets] zone unless one with its id exists. */
    suspend fun addPreset(preset: PauseZone): ZoneChange = if (store.zones().any { it.id == preset.id }) ZoneChange.OK else upsert(preset)

    private fun insideNow(id: String): Boolean = evaluator.evaluate(elapsed()).state(id) == ZoneState.INSIDE

    /** Why an existing zone may not be weakened (disabled, replaced, removed) now, or null. Not judged yet (before start, while recovering) counts as unknown: fail closed. */
    private fun blocked(id: String): ZoneChange? = when {
        !ready -> ZoneChange.NOT_READY
        insideNow(id) -> ZoneChange.BLOCKED_INSIDE
        else -> null
    }

    private suspend fun changed() {
        evaluator.setZones(store.zones(), elapsed())
        val ids = store.zones().map { it.id }.toSet()
        if (memo.keys.retainAll(ids)) memoDirty = true
        saveMemo(force = true)
        reconcile()
        runCatching { onZonesChanged() }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { block() }

    /** Persists the memo: at once when a state or a clear time changed, else at most once a minute (an older stored time only widens the window). A failed save keeps the memo in memory and retries. */
    private fun saveMemo(force: Boolean = false) {
        val st = stateStore ?: return
        val now = elapsed()
        if (!memoDirty && !force && now - memoSavedAtMs < MEMO_SAVE_MS) return
        if (st.save(memo)) { memoSavedAtMs = now; memoDirty = false } else { memoDirty = true; log("zone state save failed") }
    }

    /** The start of a zone's pause: the last time a fix proved the phone was not inside (the uncertainty window), never after now, widened by any clock rollback. */
    private fun startOf(id: String): Long {
        val now = wall()
        val clear = if (id == PROTECTED_ID) memo.values.mapNotNull { it.clearWallMs }.minOrNull() else memo[id]?.clearWallMs
        val from = clear ?: if (stateBlind) now - HISTORY_MS else now
        ClockGuard.observe(now)
        return minOf(from, now) - ClockGuard.widenMs()
    }

    /**
     * Makes the stored records equal to the zones that read INSIDE; publishes when anything (records, the approach flag, the enforcement flag) changed. A record that cannot be stored
     * (the disk, the database, the counter) is retried by every [tick] and meanwhile held in memory ([ZoneHold]): admission and the glasses' sync enforce it. A new record starts at the
     * zone's last clear time ([startOf]), so frames taken during an outage are inside it.
     */
    private suspend fun reconcile() {
        val now = elapsed()
        val ev = evaluator.evaluate(now)
        val insideIds = ev.inside.map { it.zoneId }.toMutableSet()
        if (store.corrupt) insideIds += PROTECTED_ID
        var changed = false
        var uncovered = insideIds.toSet()
        var holdSince: Long? = null
        try {
            val records = sink.activeZoneRecords()
            val covered = records.mapNotNull { ZonePause.zoneIdOf(it.pauseId) }.toSet()
            uncovered = insideIds - covered
            for (id in uncovered.toList()) {
                val start = startOf(id)
                try {
                    val state = ev.zones.firstOrNull { it.zoneId == id }
                    sink.add(PauseRecord(ZonePause.pauseId(id, store.nextSeq()), PauseOrigin.PHONE, start, null))
                    log("zone pause added state=${(state?.state ?: ZoneState.INSIDE).name} reason=${(state?.reason ?: ZoneReason.NO_FIX).name}")
                    uncovered = uncovered - id
                    changed = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("zone pause not stored cls=${e.javaClass.simpleName}")
                    holdSince = minOf(holdSince ?: start, start)
                }
            }
            val ending = records.filter { ZonePause.zoneIdOf(it.pauseId) !in insideIds }.map { it.pauseId }.toSet()
            if (ending.isNotEmpty()) { sink.end(ending); log("zone pause ended n=${ending.size}"); changed = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("zone reconcile failed cls=${e.javaClass.simpleName}")
            if (uncovered.isNotEmpty() && holdSince == null) holdSince = uncovered.minOf { startOf(it) }
        }
        ZoneHold.set(holdSince)
        val zones = store.zones()
        _snapshot.value = ZoneSnapshot(zones, ev, store.corrupt)
        ready = true
        _cadence.value = TripCadence.select(ev)
        notify(ev, zones, insideIds)
        val key = (ev.overall >= ZoneState.APPROACH) to enforcing
        if (changed || lastEnforce != key) {
            lastEnforce = key
            try { sink.publish() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("zone publish failed cls=${e.javaClass.simpleName}") }
        }
        saveMemo()
    }

    private fun notify(ev: ZoneEvaluation, zones: List<PauseZone>, insideIds: Set<String>) {
        val ids = insideIds
        val unknown = ev.inside.any { it.reason != ZoneReason.NONE } || PROTECTED_ID in ids
        val key = ids + if (unknown) setOf("?") else emptySet()
        if (key == notified) return
        notified = key
        if (ids.isEmpty()) notifier.cleared()
        else notifier.paused(ids.map { id -> if (id == PROTECTED_ID) "pause zones unreadable" else zones.firstOrNull { it.id == id }?.name ?: "pause zone" }, unknown)
    }

    companion object {
        /** The id of the pseudo zone that holds capture while the stored zones cannot be read. */
        const val PROTECTED_ID = "_unreadable"
        private const val MEMO_SAVE_MS = 60_000L
        private const val HISTORY_MS = 7 * 24 * 3_600_000L
    }
}
