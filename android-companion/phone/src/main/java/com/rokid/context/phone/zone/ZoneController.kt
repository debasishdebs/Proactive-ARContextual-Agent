package com.rokid.context.phone.zone

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.zone.FixCadence
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.TripCadence
import com.rokid.context.core.model.zone.ZoneEvaluation
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.core.model.zone.ZoneFix
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

enum class ZoneChange { OK, BLOCKED_INSIDE, INVALID, NOT_FOUND }

data class ZoneSnapshot(val zones: List<PauseZone>, val evaluation: ZoneEvaluation) {
    /** The phone is within a zone's approach band or inside it (also by fail-closed): sent to the glasses as `HudSettings.zoneApproach`. */
    val approach: Boolean get() = evaluation.overall >= ZoneState.APPROACH
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
) {
    private val mutex = Mutex()
    private val evaluator = ZoneEvaluator()
    private var lastFixAtMs: Long? = null
    private var published: Boolean? = null
    private var notified: Set<String> = emptySet()
    @Volatile private var ready = false

    private val _snapshot = MutableStateFlow(ZoneSnapshot(emptyList(), ZoneEvaluation(emptyList())))
    val snapshot: StateFlow<ZoneSnapshot> = _snapshot
    private val _cadence = MutableStateFlow<FixCadence?>(null)
    /** How fixes are wanted now (null = no zone, no fixes). */
    val cadence: StateFlow<FixCadence?> = _cadence

    /**
     * The value for `HudSettings.zoneApproach`: within a zone's approach band or inside it. Before the first reconcile the zones are unjudged, so any enabled zone counts (fail closed).
     */
    val approach: Boolean get() = if (ready) _snapshot.value.approach else wantsLocation()

    /** Any enabled zone exists: location fixes are wanted (the service holds the location type for it). Cheap; safe from any thread. */
    fun wantsLocation(): Boolean = store.zones().any { it.enabled }

    /** Loads the stored zones and reconciles the pause records with them (hub start, process start). Until the first fix every enabled zone reads INSIDE. */
    suspend fun start() = locked { evaluator.setZones(store.zones(), elapsed()); reconcile() }

    suspend fun onFix(fix: ZoneFix) = locked { if (evaluator.onFix(fix, elapsed())) lastFixAtMs = fix.timeMs; reconcile() }

    suspend fun onLocationAvailable(ok: Boolean) = locked { evaluator.onLocationAvailable(ok); reconcile() }

    /** Time passes with no fix: staleness is judged, records are repaired if a write failed. */
    suspend fun tick() = locked { reconcile() }

    fun zones(): List<PauseZone> = store.zones()

    /** Adds or replaces a zone. An existing zone that reads INSIDE cannot be changed (it could be shrunk or moved out from under the phone). */
    suspend fun upsert(zone: PauseZone): ZoneChange = locked {
        if (!zone.isValid()) return@locked ZoneChange.INVALID
        val all = store.zones()
        if (all.any { it.id == zone.id } && insideNow(zone.id)) return@locked ZoneChange.BLOCKED_INSIDE
        store.write(all.filter { it.id != zone.id } + zone)
        changed()
        ZoneChange.OK
    }

    suspend fun setEnabled(id: String, enabled: Boolean): ZoneChange = locked {
        val z = store.zones().firstOrNull { it.id == id } ?: return@locked ZoneChange.NOT_FOUND
        if (z.enabled == enabled) return@locked ZoneChange.OK
        if (!enabled && insideNow(id)) return@locked ZoneChange.BLOCKED_INSIDE
        store.write(store.zones().map { if (it.id == id) it.copy(enabled = enabled) else it })
        changed()
        ZoneChange.OK
    }

    suspend fun remove(id: String): ZoneChange = locked {
        if (store.zones().none { it.id == id }) return@locked ZoneChange.NOT_FOUND
        if (insideNow(id)) return@locked ZoneChange.BLOCKED_INSIDE
        store.write(store.zones().filter { it.id != id })
        changed()
        ZoneChange.OK
    }

    /** Adds a [com.rokid.context.core.model.zone.ZonePresets] zone unless one with its id exists. */
    suspend fun addPreset(preset: PauseZone): ZoneChange = if (store.zones().any { it.id == preset.id }) ZoneChange.OK else upsert(preset)

    private fun insideNow(id: String): Boolean = evaluator.evaluate(elapsed()).state(id) == ZoneState.INSIDE

    private suspend fun changed() {
        evaluator.setZones(store.zones(), elapsed())
        reconcile()
        runCatching { onZonesChanged() }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { block() }

    /** Makes the stored records equal to the zones that read INSIDE; publishes when anything (records or the approach flag) changed. A failed write is logged and retried by the next [tick]. */
    private suspend fun reconcile() {
        val now = elapsed()
        val ev = evaluator.evaluate(now)
        var changed = false
        try {
            val records = sink.activeZoneRecords()
            val covered = records.mapNotNull { ZonePause.zoneIdOf(it.pauseId) }.toSet()
            val inside = ev.inside
            for (s in inside) if (s.zoneId !in covered) {
                // A stale or location-off reading dates back to the last fix we trusted: frames since then are inside the interval too.
                val back = if (s.reason == ZoneReason.STALE_FIX || s.reason == ZoneReason.LOCATION_OFF) lastFixAtMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L else 0L
                sink.add(PauseRecord(ZonePause.pauseId(s.zoneId, store.nextSeq()), PauseOrigin.PHONE, wall() - back, null))
                log("zone pause added state=${s.state.name} reason=${s.reason.name}")
                changed = true
            }
            val insideIds = inside.map { it.zoneId }.toSet()
            val ending = records.filter { ZonePause.zoneIdOf(it.pauseId) !in insideIds }.map { it.pauseId }.toSet()
            if (ending.isNotEmpty()) { sink.end(ending); log("zone pause ended n=${ending.size}"); changed = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("zone reconcile failed cls=${e.javaClass.simpleName}")
        }
        val zones = store.zones()
        _snapshot.value = ZoneSnapshot(zones, ev)
        ready = true
        _cadence.value = TripCadence.select(ev)
        notify(ev, zones)
        val approach = ev.overall >= ZoneState.APPROACH
        if (changed || published != approach) {
            published = approach
            try { sink.publish() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("zone publish failed cls=${e.javaClass.simpleName}") }
        }
    }

    private fun notify(ev: ZoneEvaluation, zones: List<PauseZone>) {
        val ids = ev.inside.map { it.zoneId }.toSet()
        val unknown = ev.inside.any { it.reason != ZoneReason.NONE }
        val key = ids + if (unknown) setOf("?") else emptySet()
        if (key == notified) return
        notified = key
        if (ids.isEmpty()) notifier.cleared()
        else notifier.paused(ids.map { id -> zones.firstOrNull { it.id == id }?.name ?: "pause zone" }, unknown)
    }
}
