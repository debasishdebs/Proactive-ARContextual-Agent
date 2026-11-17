package io.github.pca.nexusport

import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.EnumMap

/**
 * THE choke point of everything PCA sends to Nexus (spec 2026-11-17 section 3, gates 1-5). Every notice, pin and speech line passes [show]; it is the ONLY caller of [NexusTransport] (and so of [NexusPort]);
 * `NexusChokePointTest` fails when any other main source touches the port. In order, an item is dropped (never delayed, never retried, never partly shown) when:
 *  1. the user's Nexus switch is off (everything is OFF by default);
 *  2. the glasses are not worn;
 *  3. a pause-zone fence is held (ZoneFence or AskZoneFence): nothing is sent;
 *  4. PCA is paused (every item with content: nudges, place cards and ask answers with their speech; the content-free capture pin and ask notices still show);
 *  5. a source it was built from is forgotten, tombstoned or wiped (ProvenanceGate), or a Nudge/PlaceCard declares no source at all.
 * ANY error in a gate drops the item (fail closed). The checks run again, under the sink lock, immediately before the call (check-then-act), and a [trip] that ran in between wins ([DropReason.TRIPPED]).
 *
 * Gate 4, withdraw on close: the sink keeps a handle per live Nexus slot. [trip] (zone hold, Forget, Delete all, pause, mode off, not worn) hides every one of them and stops the speech at once, and [tick] is the
 * safety net that re-reads every gate (and the provenance of each live handle) and expires what ran out. A hide that fails is kept and retried each tick; [liveHandles] counts those too.
 * Forget, Delete all and mode off also owe the glasses hub a history wipe ([NexusPort.forgetHistory]): Nexus keeps shown notices in its notification history for up to 24 h.
 *
 * Assist-button ask (T5): the bridge opens a Nexus speech session when the user pressed the assist button (only while [NexusConfig.askEnabled]); its final transcript comes back through [events] and goes to
 * [NexusAskHandler.ask]; the answer returns through [show] (same gates, TTS only with the TTS switch). A [trip] mid-ask (zone, pause, not worn, mode off...) stops the listening, cancels the ask, tells the handler
 * [AskRefusal] and delivers NOTHING; a late answer is dropped ([DropReason.TRIPPED]). No retry. The transcript is never logged. Skills (T6): [events] answers the four action-only skills through [skills] after the
 * same gates; a pause or zone fence is the domain failure code `paused` / `pause_zone`.
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
    private val asks: NexusAskHandler? = null,
    private val skills: NexusSkillProvider? = null,
    private val skillTimeoutMs: Long = NexusLimits.SKILL_HOST_TIMEOUT_MS,
    /** A history wipe the phone still owes the hub from before (persisted across disconnects and restarts by the phone): sent on connect and on every tick until acknowledged. */
    wipeOwedAtStart: Boolean = false,
    /** The hub accepted the history wipe (called outside the sink's lock, once per acknowledgement). */
    private val onForgetAcked: () -> Unit = {},
) {
    private class Handle(val slot: Slot, val sources: Set<String>, val pauseGated: Boolean, val ref: String, val expiresAt: Long, val actionIds: Set<String>) {
        val used = HashSet<String>()
        var withdrawing = false
    }

    private val lock = Any()
    private val handles = EnumMap<Slot, Handle>(Slot::class.java)
    private var epoch = 0L
    private var forgetOwed = wipeOwedAtStart

    private class Ask(val id: String, val expiresAt: Long) { var transcribed = false }
    private var ask: Ask? = null
    private var askCounter = 0L
    /** Ids of asks that were cancelled: a late answer for one is dropped. Small and bounded. */
    private val cancelledAsks = LinkedHashSet<String>()
    private var lastTrip = TripReason.PAUSE

    /** Live handles, including hides that failed and will be retried. Zero after any trip when the transport answers. */
    fun liveHandles(): Int = synchronized(lock) { handles.size }

    fun liveSlots(): Set<Slot> = synchronized(lock) { handles.keys.toSet() }

    fun forgetOwed(): Boolean = synchronized(lock) { forgetOwed }

    // ---- show --------------------------------------------------------------------------------------------

    suspend fun show(event: HudEvent): Outcome {
        val mapped = HudMapper.map(event) ?: return dropped(DropReason.UNSUPPORTED)
        // The pass is taken BEFORE the first suspension: a zone hold or trip from here on closes it and the delivery is dropped (the fence wins).
        val pass = try { gates.pass() } catch (_: Exception) { return dropped(DropReason.GATE_ERROR) }
        val start = synchronized(lock) { if (event is HudEvent.AskAnswer && event.ref in cancelledAsks) return dropped(DropReason.TRIPPED); epoch }
        syncBlock(event.pauseGated())?.let { return dropped(it) }
        if (event.sources.isEmpty()) {
            if (event.requiresSources()) return dropped(DropReason.PROVENANCE)
        } else {
            val live = try { gates.sourcesLive(event.sources) } catch (c: CancellationException) { throw c } catch (_: Exception) { return dropped(DropReason.GATE_ERROR) }
            if (!live) return dropped(DropReason.PROVENANCE)
        }
        // FINAL admission and dispatch are ONE step: the sink lock (a trip waits), then the fences' locks (a hold waits), the gates read last, then the transport calls. No suspension in between.
        synchronized(lock) {
            if (epoch != start) return dropped(DropReason.TRIPPED)
            return dispatch(event, mapped, pass)
        }
    }

    /** Lock held. The gates are read and the transport called inside the pass (the fences' locks); the ask is over either way. */
    private fun dispatch(event: HudEvent, mapped: Mapped, pass: NexusPass): Outcome {
        var block: DropReason? = null
        val out = try {
            pass.act { block = syncBlock(event.pauseGated()); block.let { b -> if (b == null) commit(event, mapped) else Outcome.Dropped(b) } }
        } catch (_: Exception) {
            consumeAsk(event)
            return dropped(DropReason.GATE_ERROR)
        }
        consumeAsk(event)
        block?.let { return dropped(it) }
        return out ?: dropped(DropReason.ZONE_HELD)
    }

    /** Lock held. The answer of the pending Nexus ask (or its refusal) was handled: the ask is over. */
    private fun consumeAsk(event: HudEvent) {
        if (event is HudEvent.AskAnswer && ask?.id == event.ref) ask = null
    }

    /** Short fixed-text notice, synchronous (inbound events are not suspend). Same gates as [show]. */
    private fun showNow(event: HudEvent): Outcome {
        val mapped = HudMapper.map(event) ?: return dropped(DropReason.UNSUPPORTED)
        val pass = try { gates.pass() } catch (_: Exception) { return dropped(DropReason.GATE_ERROR) }
        synchronized(lock) { return dispatch(event, mapped, pass) }
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
        handles[cmd.slot] = Handle(cmd.slot, event.sources, event.pauseGated(), event.ref, now + cmd.ttlMs, cmd.actions.map { it.id }.toSet())
        log("nexus shown slot=${cmd.slot.name}")
        var spoken = false
        val speech = mapped.speech
        if (speech != null && safeConfig().let { it.enabled && it.ttsEnabled }) {
            transport.setFeatures(safeConfig().featureMask())
            val sr = transport.speak(speech)
            if (sr == NexusResult.OK) {
                handles[Slot.SPEECH] = Handle(Slot.SPEECH, event.sources, event.pauseGated(), event.ref, now + NexusLimits.SPEECH_MAX_MS, emptySet())
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
    private fun syncBlock(pauseGated: Boolean): DropReason? {
        if (!safeConfig().enabled) return DropReason.MODE_OFF
        return try {
            when {
                !gates.worn() -> DropReason.NOT_WORN
                gates.zoneFenceBlocked() || gates.askFenceBlocked() -> DropReason.ZONE_HELD
                pauseGated && gates.paused() -> DropReason.PAUSED
                else -> null
            }
        } catch (_: Exception) {
            DropReason.GATE_ERROR
        }
    }

    /** Pause gates every item that carries content: proactive items AND answers (an AskAnswer, and the speech built from it). Only the content-free state items (capture pin, ask notice, mark token) show while paused. */
    private fun HudEvent.pauseGated() = proactive || this is HudEvent.AskAnswer

    private fun HudEvent.requiresSources() = this is HudEvent.Nudge || this is HudEvent.PlaceCard

    private fun dropped(reason: DropReason): Outcome {
        log("nexus dropped reason=${reason.name}")
        return Outcome.Dropped(reason)
    }

    // ---- withdraw ----------------------------------------------------------------------------------------

    /** Lock held. True = the handle is gone. */
    private fun withdrawLocked(slot: Slot): Boolean {
        val h = handles[slot] ?: return true
        val r = when (slot) {
            Slot.SPEECH -> transport.stopSpeech()
            Slot.LISTEN -> transport.stopListening()
            else -> transport.hide(slot)
        }
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
        val cancelled: String?
        synchronized(lock) {
            epoch++
            lastTrip = reason
            cancelled = cancelAskLocked()
            val hadListen = Slot.LISTEN in handles
            val left = withdrawAllLocked()
            if (!hadListen) transport.stopListening()     // a session may be opening (authorized, no handle yet): it must not outlive the trip
            log("nexus trip reason=${reason.name} left=${left}")
            if (reason == TripReason.FORGET || reason == TripReason.DELETE_ALL || reason == TripReason.MODE_OFF) {
                forgetOwed = true
                sendForgetLocked()
            }
            if (reason == TripReason.MODE_OFF) transport.setFeatures(NexusFeatures.NONE)
        }
        cancelled?.let { notifyRefused(it, refusalFor(reason)) }
    }

    /** Lock held. Ends the current ask without delivering anything; returns its id when there was one. */
    private fun cancelAskLocked(): String? {
        val a = ask ?: return null
        ask = null
        cancelledAsks += a.id
        while (cancelledAsks.size > MAX_CANCELLED_ASKS) cancelledAsks.remove(cancelledAsks.first())
        return a.id
    }

    private fun refusalFor(r: TripReason) = when (r) {
        TripReason.ZONE_HOLD -> AskRefusal.ZONE_PAUSED
        TripReason.PAUSE -> AskRefusal.PAUSED
        TripReason.NOT_WORN -> AskRefusal.NOT_WORN
        TripReason.MODE_OFF -> AskRefusal.DISABLED
        TripReason.FORGET, TripReason.DELETE_ALL, TripReason.LINK_LOST -> AskRefusal.CANCELLED
    }

    private fun refusalFor(d: DropReason) = when (d) {
        DropReason.ZONE_HELD, DropReason.GATE_ERROR -> AskRefusal.ZONE_PAUSED
        DropReason.PAUSED -> AskRefusal.PAUSED
        DropReason.NOT_WORN -> AskRefusal.NOT_WORN
        DropReason.MODE_OFF -> AskRefusal.DISABLED
        else -> AskRefusal.CANCELLED
    }

    /** Never under the lock. */
    private fun notifyRefused(id: String, why: AskRefusal) {
        log("nexus ask refused reason=${why.name}")
        try { asks?.refused(id, why) } catch (c: CancellationException) { throw c } catch (_: Exception) { log("nexus ask handler failed") }
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
                else if (now >= h.expiresAt) { if (h.slot == Slot.LISTEN) withdrawLocked(h.slot) else handles.remove(h.slot) }
            }
            if (forgetOwed) sendForgetLocked()
            handles.values.toList()
        }
        val askGone = synchronized(lock) {
            val a = ask
            if (a == null) null
            else {
                val block = syncBlock(true)
                if (block != null || now >= a.expiresAt) { cancelAskLocked(); a.id to (block?.let(::refusalFor) ?: AskRefusal.CANCELLED) } else null
            }
        }
        askGone?.let { notifyRefused(it.first, it.second) }
        for (h in snapshot) {
            val block = syncBlock(h.pauseGated)
            val live = block != null || if (h.sources.isEmpty()) true else try { gates.sourcesLive(h.sources) } catch (c: CancellationException) { throw c } catch (_: Exception) { false }
            if (block != null || !live) synchronized(lock) { if (handles[h.slot] === h) withdrawLocked(h.slot) }
        }
    }

    /** The bridge process is back (or new): it holds nothing, and whatever the old one drew is unknown. Hide every slot we can and re-push the switches. */
    fun onBridgeConnected() {
        synchronized(lock) {
            epoch++
            handles.clear()
            ask = null
            for (s in listOf(Slot.NOTICE, Slot.PIN)) transport.hide(s)
            transport.stopSpeech()
            transport.stopListening()
            transport.setFeatures(safeConfig().featureMask())
            transport.setSkillHost(skills != null || asks != null)     // the host also answers `authorizeListen` for the assist ask
            if (forgetOwed) sendForgetLocked()
        }
    }

    /**
     * Call when the user changes a Nexus switch in PCA (not only on connect): pushes the feature bits. Turning the assist ask off while a session is open stops the listening and cancels the ask.
     * Mode off is a [trip] ([TripReason.MODE_OFF]), not this.
     */
    fun refreshFeatures() {
        var cancelled: String? = null
        synchronized(lock) {
            val cfg = safeConfig()
            if (!cfg.askEnabled()) { cancelled = cancelAskLocked(); withdrawLocked(Slot.LISTEN) }
            transport.setFeatures(cfg.featureMask())
        }
        cancelled?.let { notifyRefused(it, AskRefusal.DISABLED) }
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
                if (actionId != HudMapper.DISMISS && syncBlock(h.pauseGated) != null) { withdrawLocked(s); return }
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

        /**
         * The bridge asks BEFORE it opens a Nexus speech session (the microphone is Nexus's): only while the ask switches are on and every gate (mode, worn, zone fences, pause) is open, read last under the
         * fences. Anything else, a throw included, is a no. Synchronous, on the binder thread of the bridge's worker.
         */
        override fun authorizeListen(): Boolean {
            val pass = try { gates.pass() } catch (_: Exception) { log("nexus listen refused"); return false }
            val ok = synchronized(lock) {
                if (!safeConfig().askEnabled()) null
                else try { pass.act { syncBlock(true) == null } } catch (_: Exception) { null }
            }
            if (ok == true) log("nexus listen authorized") else log("nexus listen refused")
            return ok == true
        }

        override fun onSpeechDone() { synchronized(lock) { handles.remove(Slot.SPEECH) } }
        override fun onForgetAccepted() {
            synchronized(lock) { forgetOwed = false }
            try { onForgetAcked() } catch (_: Exception) { log("nexus wipe ack failed") }
        }

        // ---- T5: the assist-button ask --------------------------------------------------------------

        override fun onListening(active: Boolean) {
            if (!active) {
                synchronized(lock) {
                    handles.remove(Slot.LISTEN)
                    if (ask?.transcribed == false) ask = null     // the session ended with nothing said (the failure, if any, comes through onAskFailed)
                }
                return
            }
            var refusal: Pair<String, AskRefusal>? = null
            var superseded: String? = null
            synchronized(lock) {
                val id = "ask-${++askCounter}"
                val block = if (safeConfig().askEnabled()) syncBlock(true) else DropReason.MODE_OFF
                if (block != null) {
                    transport.stopListening()
                    refusal = id to refusalFor(block)
                } else {
                    superseded = cancelAskLocked()
                    ask = Ask(id, clock() + NexusLimits.ASK_MAX_MS)
                    handles[Slot.LISTEN] = Handle(Slot.LISTEN, emptySet(), false, id, clock() + NexusLimits.LISTEN_MAX_MS, emptySet())
                    log("nexus listening started")
                }
            }
            superseded?.let { notifyRefused(it, AskRefusal.CANCELLED) }
            refusal?.let { notifyRefused(it.first, it.second) }
        }

        override fun onAskTranscript(text: String) {
            var run: String? = null
            var refusal: Pair<String, AskRefusal>? = null
            var notice: HudEvent.AskNotice.Kind? = null
            synchronized(lock) {
                val a = ask
                if (a == null || a.transcribed) { log("nexus ask ignored reason=NO_ASK"); return }
                handles.remove(Slot.LISTEN)
                if (!safeConfig().askEnabled()) { cancelAskLocked(); refusal = a.id to AskRefusal.DISABLED; return@synchronized }
                val block = syncBlock(true)
                if (block != null) { cancelAskLocked(); refusal = a.id to refusalFor(block); return@synchronized }
                if (text.isBlank()) { ask = null; notice = HudEvent.AskNotice.Kind.NO_SPEECH; return@synchronized }
                a.transcribed = true
                run = a.id
            }
            refusal?.let { notifyRefused(it.first, it.second) }
            notice?.let { showNow(HudEvent.AskNotice(it)) }
            val id = run ?: return
            log("nexus ask start")
            try { asks?.ask(id, HudMapper.clipText(text, NexusLimits.ASK_TEXT_CHARS)) } catch (c: CancellationException) { throw c } catch (_: Exception) { log("nexus ask handler failed") }
        }

        override fun onAskFailed(reason: Int) {
            val kind = when (reason) {
                AskFail.DENIED_BUSY -> HudEvent.AskNotice.Kind.BUSY
                AskFail.NO_SPEECH -> HudEvent.AskNotice.Kind.NO_SPEECH
                else -> HudEvent.AskNotice.Kind.UNAVAILABLE
            }
            synchronized(lock) {
                if (!safeConfig().askEnabled()) return
                if (ask?.transcribed != true) ask = null
                handles.remove(Slot.LISTEN)
            }
            log("nexus ask failed kind=${kind.name}")
            showNow(HudEvent.AskNotice(kind))
        }

        // ---- T6: the four action-only skills --------------------------------------------------------

        override fun invokeSkill(opId: String): String = SkillWire.encode(runSkill(opId))
    }

    /** The skill gates and the provider call, with the 12 s ceiling. Public for tests; the bridge calls it through [events]. Never throws. */
    internal fun runSkill(opId: String): SkillResult {
        val op = SkillOp.of(opId) ?: return SkillResult.Refused(SkillCodes.UNSUPPORTED_OPERATION)
        val result = skillGated(op) ?: skillCall(op)
        val answered = result is SkillResult.Done
        log("nexus skill op=${op.name} ok=${answered}")
        return result
    }

    /** A refusal from the sink's own gates, or null to go on. Fails closed: a throwing gate refuses. */
    private fun skillGated(op: SkillOp): SkillResult? {
        val cfg = safeConfig()
        if (!cfg.enabled || !cfg.skillsEnabled) return SkillResult.Refused(SkillCodes.DISABLED)
        if (skills == null) return SkillResult.Refused(SkillCodes.UNAVAILABLE)
        return try {
            val zone = gates.zoneFenceBlocked() || gates.askFenceBlocked()
            when (op) {
                SkillOp.STATUS -> if (zone) SkillResult.Done(SkillState.ZONE_PAUSED) else null     // content-free, and the provider is not even asked
                SkillOp.PAUSE_CAPTURE -> null                                                      // pausing is always safe
                SkillOp.RESUME_CAPTURE -> if (zone) SkillResult.Refused(SkillCodes.PAUSE_ZONE) else null
                SkillOp.MARK_MOMENT -> when {
                    zone -> SkillResult.Refused(SkillCodes.PAUSE_ZONE)
                    !gates.worn() -> SkillResult.Refused(SkillCodes.NOT_WORN)
                    gates.paused() -> SkillResult.Refused(SkillCodes.PAUSED)
                    else -> null
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            SkillResult.Refused(SkillCodes.UNAVAILABLE)
        }
    }

    private fun skillCall(op: SkillOp): SkillResult {
        val p = skills ?: return SkillResult.Refused(SkillCodes.UNAVAILABLE)
        return try {
            val r = runBlocking { withTimeoutOrNull(skillTimeoutMs) { p.invoke(op) } }
            when (r) {
                null -> SkillResult.Refused(SkillCodes.DEADLINE_EXCEEDED)
                is SkillResult.Done -> if (r.state in SkillState.ALL) r else SkillResult.Refused(SkillCodes.INVALID_RESULT)
                is SkillResult.Refused -> SkillResult.Refused(SkillCodes.sanitize(r.code))
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            SkillResult.Refused(SkillCodes.FAILED)
        }
    }

    companion object {
        private const val MAX_CANCELLED_ASKS = 16

        /** Binds the sink to the bridge's port binder (the only public way to get a sink). */
        fun connect(
            binder: IBinder, gates: NexusGates, config: () -> NexusConfig, actions: NexusActionHandler, log: (String) -> Unit = {},
            asks: NexusAskHandler? = null, skills: NexusSkillProvider? = null,
            wipeOwed: Boolean = false, onForgetAcked: () -> Unit = {},
        ): NexusHudSink {
            val t = AidlNexusTransport.from(binder)
            val sink = NexusHudSink(t, gates, config, actions, log = log, asks = asks, skills = skills, wipeOwedAtStart = wipeOwed, onForgetAcked = onForgetAcked)
            t.setEvents(sink.events)
            sink.onBridgeConnected()
            return sink
        }
    }
}
