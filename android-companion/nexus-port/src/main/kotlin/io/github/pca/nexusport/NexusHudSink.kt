package io.github.pca.nexusport

import android.os.IBinder
import kotlinx.coroutines.CancellationException
import java.util.EnumMap

/**
 * THE choke point of everything PCA sends to Nexus (spec 2026-11-17 section 3, gates 1-5). Every notice, pin and speech line passes [show]; it is the ONLY caller of [NexusTransport] (and so of [NexusPort]);
 * `NexusChokePointTest` fails when any other main source touches the port. In order, an item is dropped (never delayed, never retried, never partly shown) when:
 *  1. the user's Nexus switch is off (everything is OFF by default);
 *  2. the glasses are not worn;
 *  3. a pause-zone fence is held (ZoneFence or AskZoneFence): nothing is sent;
 *  4. PCA is paused (proactive items only: nudges and place cards);
 *  5. a source it was built from is forgotten, tombstoned or wiped (ProvenanceGate), or a Nudge/PlaceCard declares no source at all.
 * ANY error in a gate drops the item (fail closed). The checks run again, under the sink lock, immediately before the call (check-then-act), and a [trip] that ran in between wins ([DropReason.TRIPPED]).
 *
 * Gate 4, withdraw on close: the sink keeps a handle per live Nexus slot. [trip] (zone hold, Forget, Delete all, pause, mode off, not worn) hides every one of them and stops the speech at once, and [tick] is the
 * safety net that re-reads every gate (and the provenance of each live handle) and expires what ran out. A hide that fails is kept and retried each tick; [liveHandles] counts those too.
 * Forget, Delete all and mode off also owe the glasses hub a history wipe ([NexusPort.forgetHistory]): Nexus keeps shown notices in its notification history for up to 24 h.
 *
 * Logs: literal event names, reason names and counts only. No title, text, answer or transcript ever reaches a log.
 */
class NexusHudSink internal constructor(
    private val transport: NexusTransport,
    private val gates: NexusGates,
    private val config: () -> NexusConfig,
    private val actions: NexusActionHandler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private class Handle(val slot: Slot, val sources: Set<String>, val proactive: Boolean, val ref: String, val expiresAt: Long, val actionIds: Set<String>) {
        val used = HashSet<String>()
        var withdrawing = false
    }

    private val lock = Any()
    private val handles = EnumMap<Slot, Handle>(Slot::class.java)
    private var epoch = 0L
    private var forgetOwed = false

    /** Live handles, including hides that failed and will be retried. Zero after any trip when the transport answers. */
    fun liveHandles(): Int = synchronized(lock) { handles.size }

    fun liveSlots(): Set<Slot> = synchronized(lock) { handles.keys.toSet() }

    fun forgetOwed(): Boolean = synchronized(lock) { forgetOwed }

    // ---- show --------------------------------------------------------------------------------------------

    suspend fun show(event: HudEvent): Outcome {
        val mapped = HudMapper.map(event) ?: return dropped(DropReason.UNSUPPORTED)
        val start = synchronized(lock) { epoch }
        syncBlock(event.proactive)?.let { return dropped(it) }
        if (event.sources.isEmpty()) {
            if (event.requiresSources()) return dropped(DropReason.PROVENANCE)
        } else {
            val live = try { gates.sourcesLive(event.sources) } catch (c: CancellationException) { throw c } catch (_: Exception) { return dropped(DropReason.GATE_ERROR) }
            if (!live) return dropped(DropReason.PROVENANCE)
        }
        synchronized(lock) {
            if (epoch != start) return dropped(DropReason.TRIPPED)
            syncBlock(event.proactive)?.let { return dropped(it) }
            return commit(event, mapped)
        }
    }

    /** Lock held. */
    private fun commit(event: HudEvent, mapped: Mapped): Outcome {
        val cmd = mapped.command
        val now = clock()
        val r = transport.show(cmd)
        if (r != NexusResult.OK) {
            log("nexus show refused slot=${cmd.slot.name} code=${NexusResult.name(r)}")
            return Outcome.Dropped(DropReason.TRANSPORT, r)
        }
        handles[cmd.slot] = Handle(cmd.slot, event.sources, event.proactive, event.ref, now + cmd.ttlMs, cmd.actions.map { it.id }.toSet())
        log("nexus shown slot=${cmd.slot.name}")
        var spoken = false
        val speech = mapped.speech
        if (speech != null && safeConfig().let { it.enabled && it.ttsEnabled }) {
            transport.setFeatures(safeConfig().featureMask())
            val sr = transport.speak(speech)
            if (sr == NexusResult.OK) {
                handles[Slot.SPEECH] = Handle(Slot.SPEECH, event.sources, event.proactive, event.ref, now + NexusLimits.SPEECH_MAX_MS, emptySet())
                spoken = true
                log("nexus shown slot=SPEECH")
            } else {
                log("nexus speech refused code=${NexusResult.name(sr)}")
            }
        }
        return Outcome.Shown(spoken)
    }

    // ---- gates -------------------------------------------------------------------------------------------

    private fun safeConfig(): NexusConfig = try { config() } catch (_: Exception) { NexusConfig() }

    /** The synchronous gates. A throw is a block. */
    private fun syncBlock(proactive: Boolean): DropReason? {
        if (!safeConfig().enabled) return DropReason.MODE_OFF
        return try {
            when {
                !gates.worn() -> DropReason.NOT_WORN
                gates.zoneFenceBlocked() || gates.askFenceBlocked() -> DropReason.ZONE_HELD
                proactive && gates.paused() -> DropReason.PAUSED
                else -> null
            }
        } catch (_: Exception) {
            DropReason.GATE_ERROR
        }
    }

    private fun HudEvent.requiresSources() = this is HudEvent.Nudge || this is HudEvent.PlaceCard

    private fun dropped(reason: DropReason): Outcome {
        log("nexus dropped reason=${reason.name}")
        return Outcome.Dropped(reason)
    }

    // ---- withdraw ----------------------------------------------------------------------------------------

    /** Lock held. True = the handle is gone. */
    private fun withdrawLocked(slot: Slot): Boolean {
        val h = handles[slot] ?: return true
        val r = if (slot == Slot.SPEECH) transport.stopSpeech() else transport.hide(slot)
        if (NexusResult.hideMustRetry(r)) {
            h.withdrawing = true
            log("nexus withdraw retry slot=${slot.name} code=${NexusResult.name(r)}")
            return false
        }
        handles.remove(slot)
        return true
    }

    private fun withdrawAllLocked(): Int {
        var left = 0
        for (slot in handles.keys.toList()) if (!withdrawLocked(slot)) left++
        return left
    }

    /**
     * A close event: a zone hold, Forget, Delete all, pause, mode off or not worn. Hides every live handle and stops the speech NOW (synchronously), and ends every show still in flight. Call it from the same
     * place that closes PCA's own HUD. Never throws.
     */
    fun trip(reason: TripReason) {
        synchronized(lock) {
            epoch++
            val left = withdrawAllLocked()
            log("nexus trip reason=${reason.name} left=${left}")
            if (reason == TripReason.FORGET || reason == TripReason.DELETE_ALL || reason == TripReason.MODE_OFF) {
                forgetOwed = true
                sendForgetLocked()
            }
            if (reason == TripReason.MODE_OFF) transport.setFeatures(NexusFeatures.NONE)
        }
    }

    private fun sendForgetLocked() {
        val r = transport.forgetHistory()
        log("nexus forget history code=${NexusResult.name(r)}")
    }

    /**
     * One poll: expires what ran out, retries hides that failed, re-reads every gate for every live handle (so a gate that changed without a [trip] still withdraws within one tick) and re-checks the
     * provenance of each live handle's sources. A gate error withdraws. Also re-sends an owed history wipe.
     */
    suspend fun tick() {
        val now = clock()
        val snapshot = synchronized(lock) {
            for (h in handles.values.toList()) {
                if (h.withdrawing) withdrawLocked(h.slot)
                else if (now >= h.expiresAt) handles.remove(h.slot)
            }
            if (forgetOwed) sendForgetLocked()
            handles.values.toList()
        }
        for (h in snapshot) {
            val block = syncBlock(h.proactive)
            val live = block != null || if (h.sources.isEmpty()) true else try { gates.sourcesLive(h.sources) } catch (c: CancellationException) { throw c } catch (_: Exception) { false }
            if (block != null || !live) synchronized(lock) { if (handles[h.slot] === h) withdrawLocked(h.slot) }
        }
    }

    /** The bridge process is back (or new): it holds nothing, and whatever the old one drew is unknown. Hide every slot we can and re-push the switches. */
    fun onBridgeConnected() {
        synchronized(lock) {
            epoch++
            handles.clear()
            for (s in listOf(Slot.NOTICE, Slot.PIN)) transport.hide(s)
            transport.stopSpeech()
            transport.setFeatures(safeConfig().featureMask())
            if (forgetOwed) sendForgetLocked()
        }
    }

    // ---- inbound -----------------------------------------------------------------------------------------

    internal val events = object : NexusEvents {
        override fun onAction(slot: Int, actionId: String) {
            val s = Slot.of(slot) ?: return
            var ref: String? = null
            synchronized(lock) {
                val h = handles[s] ?: return
                if (h.withdrawing || actionId !in h.actionIds || !h.used.add(actionId)) return
                // Mark and More act on PCA data: they need the gates to still be open. Dismiss never does.
                if (actionId != HudMapper.DISMISS && syncBlock(h.proactive) != null) { withdrawLocked(s); return }
                withdrawLocked(s)
                if (actionId == HudMapper.MARK || actionId == HudMapper.MORE) ref = h.ref
            }
            val r = ref ?: return
            try {
                if (actionId == HudMapper.MARK) actions.mark(r) else actions.more(r)
            } catch (_: Exception) {
                log("nexus action failed")
            }
        }

        override fun onSpeechDone() { synchronized(lock) { handles.remove(Slot.SPEECH) } }
        override fun onForgetAccepted() { synchronized(lock) { forgetOwed = false } }
    }

    companion object {
        /** Binds the sink to the bridge's port binder (the only public way to get a sink). */
        fun connect(binder: IBinder, gates: NexusGates, config: () -> NexusConfig, actions: NexusActionHandler, log: (String) -> Unit = {}): NexusHudSink {
            val t = AidlNexusTransport.from(binder)
            val sink = NexusHudSink(t, gates, config, actions, log = log)
            t.setEvents(sink.events)
            sink.onBridgeConnected()
            return sink
        }
    }
}
