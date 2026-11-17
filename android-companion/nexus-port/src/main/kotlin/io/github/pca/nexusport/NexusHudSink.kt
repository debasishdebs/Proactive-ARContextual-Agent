package io.github.pca.nexusport

import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    /** A history wipe the phone still owes the hub from before (used when no [wipeLedger] is given). */
    wipeOwedAtStart: Boolean = false,
    /** The hub accepted a history wipe (called outside the sink's lock, once per acknowledgement, after the ledger settled what it covers). */
    private val onForgetAcked: () -> Unit = {},
    /** The persisted wipe debt (generations). Default: in memory. */
    wipeLedger: WipeLedger? = null,
    /** Where binder calls run. Default: inline (tests); [connect] passes the threaded one. */
    private val dispatcher: NexusDispatcher = NexusDispatcher.direct(),
) {
    private class Handle(val slot: Slot, val sources: Set<String>, val pauseGated: Boolean, val ref: String, val expiresAt: Long, val actionIds: Set<String>, val seq: Long, val prev: Handle?) {
        val used = HashSet<String>()
        var withdrawing = false
    }

    private class HideReq(var bound: Long)

    private class ShowOp(val epoch: Long, val pass: NexusPass, val cmd: NexusCommand, val speech: String?, val h: Handle, val sh: Handle?, val pauseGated: Boolean, val done: CompletableDeferred<Outcome>)

    private val ledger: WipeLedger = wipeLedger ?: MemoryWipeLedger(wipeOwedAtStart)
    private val lock = Any()
    private val handles = EnumMap<Slot, Handle>(Slot::class.java)
    private val pendingHide = EnumMap<Slot, HideReq>(Slot::class.java)
    private var forgetQueued = false
    @Volatile private var epoch = 0L
    private var seq = 0L
    /** The last transport call of THIS thread timed out (the worker, a fresh privacy thread and the pool each have their own). */
    private val timedOutTl = ThreadLocal.withInitial { false }
    private var lastTimedOut: Boolean
        get() = timedOutTl.get()
        set(v) { timedOutTl.set(v) }
    /** Highest trip epoch the bridge confirmed (r3 P1-1); a tick re-sends the notice while it lags behind [epoch]. */
    private val tripAcked = AtomicLong(0)
    private val tripRetrying = AtomicBoolean(false)
    /** Highest wipe generation the hub acknowledged, and the wipe generation each show whose binder call has not returned started under (r3 P1-3). */
    private val ackedGen = AtomicLong(0)
    private class InFlight(val gen: Long, val atNanos: Long)
    private val inFlightShows = ConcurrentHashMap<Any, InFlight>()
    private val featuresQueued = AtomicBoolean(false)
    private val stopListenQueued = AtomicBoolean(false)

    private class Ask(val id: String, val expiresAt: Long) { var transcribed = false }
    private var ask: Ask? = null
    private var askCounter = 0L
    /** Ids of asks that were cancelled: a late answer for one is dropped. Small and bounded. */
    private val cancelledAsks = LinkedHashSet<String>()
    private var lastTrip = TripReason.PAUSE

    /** Live handles, including hides that failed and will be retried. Zero after any trip when the transport answers. */
    fun liveHandles(): Int = synchronized(lock) { handles.size }

    internal fun inFlightShowCount(): Int = inFlightShows.size
    internal fun dispatcherPending(): Int = dispatcher.pendingContent()

    fun liveSlots(): Set<Slot> = synchronized(lock) { handles.keys.toSet() }

    fun forgetOwed(): Boolean = try { ledger.owed() } catch (_: Exception) { true }

    internal fun wipeGeneration(): Long = try { ledger.generation() } catch (_: Exception) { 0L }

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
        // FINAL admission is ONE cheap step: the sink lock (a trip waits for it, never for a binder call), then the fences' locks (a hold waits), the gates read last, then the item is only ENQUEUED.
        // The binder call runs later on the dispatcher thread outside every lock, after a last ticket check.
        val done = CompletableDeferred<Outcome>()
        val early = synchronized(lock) { if (epoch != start) dropped(DropReason.TRIPPED) else admit(event, mapped, pass, done) }
        return early ?: done.await()
    }

    /** Lock held. A drop to report now, or null when the show was queued (its result completes [done]). The ask is over either way. */
    private fun admit(event: HudEvent, mapped: Mapped, pass: NexusPass, done: CompletableDeferred<Outcome>): Outcome? {
        var block: DropReason? = null
        var queued = true
        val ran = try {
            pass.act { block = syncBlock(event.pauseGated()); if (block != null) false else { queued = enqueueShow(event, mapped, pass, done); true } }
        } catch (_: Exception) {
            consumeAsk(event)
            return dropped(DropReason.GATE_ERROR)
        }
        consumeAsk(event)
        block?.let { return dropped(it) }
        if (ran == null) return dropped(DropReason.ZONE_HELD)
        return if (queued) null else dropped(DropReason.TRANSPORT)
    }

    /** Lock held. The answer of the pending Nexus ask (or its refusal) was handled: the ask is over. */
    private fun consumeAsk(event: HudEvent) {
        if (event is HudEvent.AskAnswer && ask?.id == event.ref) ask = null
    }

    /** Short fixed-text notice, synchronous (inbound events are not suspend); fire and forget. Same gates as [show]. */
    private fun showNow(event: HudEvent): Outcome {
        val mapped = HudMapper.map(event) ?: return dropped(DropReason.UNSUPPORTED)
        val pass = try { gates.pass() } catch (_: Exception) { return dropped(DropReason.GATE_ERROR) }
        synchronized(lock) { return admit(event, mapped, pass, CompletableDeferred()) ?: Outcome.Shown(false) }
    }

    /** Lock held. Registers the handles up front (a trip then hides them even if the call is in flight) and queues the show. False = the queue is full. */
    private fun enqueueShow(event: HudEvent, mapped: Mapped, pass: NexusPass, done: CompletableDeferred<Outcome>): Boolean {
        val cmd = mapped.command
        val now = clock()
        val h = Handle(cmd.slot, event.sources, event.pauseGated(), event.ref, now + cmd.ttlMs, cmd.actions.map { it.id }.toSet(), ++seq, handles[cmd.slot])
        val speech = mapped.speech?.takeIf { safeConfig().let { c -> c.enabled && c.ttsEnabled } }
        val sh = if (speech != null) Handle(Slot.SPEECH, event.sources, event.pauseGated(), event.ref, now + NexusLimits.SPEECH_MAX_MS, emptySet(), ++seq, handles[Slot.SPEECH]) else null
        handles[cmd.slot] = h
        sh?.let { handles[Slot.SPEECH] = it }
        val op = ShowOp(epoch, pass, cmd, speech, h, sh, event.pauseGated(), done)
        val ok = dispatcher.submit(NexusDispatcher.Item(true, { runShow(op) }, { why -> dropShow(op, why) }, h))
        if (!ok) { restoreLocked(sh); restoreLocked(h) }
        return ok
    }

    /** Lock held. An unsent (or refused) show leaves the registry as it was. */
    private fun restoreLocked(h: Handle?) {
        if (h == null || handles[h.slot] !== h) return
        val p = h.prev
        if (p != null) handles[h.slot] = p else handles.remove(h.slot)
    }

    private fun dropShow(op: ShowOp, why: DropReason) {
        synchronized(lock) { restoreLocked(op.sh); restoreLocked(op.h) }
        log("nexus dropped reason=${why.name}")
        op.done.complete(Outcome.Dropped(why, if (why == DropReason.TRANSPORT) NexusResult.ERROR else NexusResult.OK))
    }

    /** True while the ticket the show was admitted with is still good: no trip since, the fences open, the synchronous gates open. The LAST step before a binder call; no lock is held across the call that follows. */
    private fun stillValid(epochAtAdmission: Long, pass: NexusPass, pauseGated: Boolean): Boolean {
        if (epoch != epochAtAdmission) return false
        return try { pass.act { syncBlock(pauseGated) == null } == true && epoch == epochAtAdmission } catch (_: Exception) { false }
    }

    /** Lock held. True while [h] is still registered and neither it nor a newer handle of its slot was withdrawn: a withdrawn handle's show is never drawn (r3 P1-2). */
    private fun registeredLocked(h: Handle?): Boolean {
        if (h == null) return true
        var c = handles[h.slot]
        while (c != null) {
            if (c.withdrawing) return false
            if (c === h) return true
            c = c.prev
        }
        return false
    }

    private fun wipeGenSafe(): Long = try { ledger.generation() } catch (_: Exception) { 0L }

    /** Runs [send] with its wipe generation registered while the binder call is unfinished; a send that returns OK after a wipe covering that generation was acknowledged re-owes the wipe (r3 P1-3). */
    private fun trackedShow(startGen: Long, send: () -> Int): Int {
        val tok = Any()
        inFlightShows[tok] = InFlight(startGen, System.nanoTime())
        try {
            val x = send()
            if (x == NexusResult.OK && ackedGen.get() > startGen) historyDirty()
            return x
        } finally { inFlightShows.remove(tok) }
    }

    /** A notice was created after the wipe that should have covered it was acknowledged: the history holds it, so a new wipe is owed. */
    private fun historyDirty() {
        log("nexus history dirty")
        owe()
        synchronized(lock) { enqueueForgetLocked() }
    }

    /** Dispatcher thread. */
    private fun runShow(op: ShowOp) {
        if (!synchronized(lock) { registeredLocked(op.h) }) { dropShow(op, DropReason.TRIPPED); return }
        if (!stillValid(op.epoch, op.pass, op.pauseGated)) { dropShow(op, DropReason.TRIPPED); return }
        if (!dispatcher.healthy()) { dropShow(op, DropReason.TRANSPORT); return }
        val cmd = op.cmd
        var startGen = 0L
        // Everything is re-checked AFTER the delivery lease is held (no trip can complete between this check and the call), and the wipe record is persisted before the first notice of a generation is sent.
        val r = callT({
            if (!stillValid(op.epoch, op.pass, op.pauseGated) || !synchronized(lock) { registeredLocked(op.h) }) false
            else { try { ledger.noteShown() } catch (_: Exception) { log("nexus wipe record failed") }; startGen = wipeGenSafe(); true }
        }) { trackedShow(startGen) { transport.show(cmd, op.epoch) } }
        if (r != NexusResult.OK) {
            // A timeout may still draw the notice later: keep the handle (unhealthy() marked it withdrawing, so the retry hides it).
            if (!lastTimedOut) synchronized(lock) { restoreLocked(op.sh); restoreLocked(op.h) }
            log("nexus show refused slot=${cmd.slot.name} code=${NexusResult.name(r)}")
            op.done.complete(if (r == NexusResult.STALE) Outcome.Dropped(DropReason.TRIPPED) else Outcome.Dropped(DropReason.TRANSPORT, r))
            return
        }
        log("nexus shown slot=${cmd.slot.name}")
        var spoken = false
        val speech = op.speech
        if (speech != null) {
            var speechGen = 0L
            val live = safeConfig().let { it.enabled && it.ttsEnabled }
            if (live) {
                callT { transport.setFeatures(safeConfig().featureMask()) }
                val sr = callT({ safeConfig().let { it.enabled && it.ttsEnabled } && stillValid(op.epoch, op.pass, op.pauseGated) && synchronized(lock) { registeredLocked(op.sh) }.also { speechGen = wipeGenSafe() } }) { trackedShow(speechGen) { transport.speak(speech, op.epoch) } }
                if (sr == NexusResult.OK) { spoken = true; log("nexus shown slot=SPEECH") }
                else {
                    log("nexus speech refused code=${NexusResult.name(sr)}")
                    if (!lastTimedOut) synchronized(lock) { restoreLocked(op.sh) }
                }
            } else synchronized(lock) { restoreLocked(op.sh) }
        }
        op.done.complete(Outcome.Shown(spoken))
    }

    /**
     * One transport call, outside every lock, with the hard timeout. A timeout or an exception is the bridge being unhealthy; the result is then ERROR. A CONTENT call passes [guard]: it holds the SHARED delivery lease for
     * the whole binder call and [guard] (the last validity check) runs after the lease is held; false (or no lease within its bound) = nothing is sent and the result is [NexusResult.STALE].
     */
    private fun callT(guard: (() -> Boolean)? = null, block: () -> Int): Int {
        if (!dispatcher.healthy()) { lastTimedOut = false; return NexusResult.ERROR }     // backing off: no call is made, nothing is forgotten (hides and the wipe are retried by the ticks)
        val leased = guard != null
        if (leased && !dispatcher.acquireShared()) { lastTimedOut = false; return NexusResult.STALE }
        val r: Int?
        try {
            if (guard != null && !guard()) { lastTimedOut = false; return NexusResult.STALE }
            r = dispatcher.call(block)
        } finally { if (leased) dispatcher.releaseShared() }
        lastTimedOut = r == null
        if (r == null) { unhealthy(); return NexusResult.ERROR }
        if (dispatcher.markOk()) queueFeatures()
        return r
    }

    /** The bridge did not answer in time (or threw): trip, drop what is pending, owe a wipe, back off. Nothing here waits for the bridge. */
    private fun unhealthy() {
        dispatcher.markFailure()
        var cancelled: String? = null
        val pending: List<NexusDispatcher.Item>
        val e: Long
        synchronized(lock) {
            e = ++epoch
            cancelled = cancelAskLocked()
            for (h in handles.values) h.withdrawing = true
            pending = dispatcher.takeGuarded()
            log("nexus bridge unhealthy")
        }
        pending.forEach { it.discard(DropReason.TRANSPORT) }
        owe()
        deliverTrip(e, wait = false)
        cancelled?.let { notifyRefused(it, AskRefusal.CANCELLED) }
    }

    /** Records a new wipe debt generation (persisted before returning). Never throws. */
    private fun owe(): Long = try { ledger.owe() } catch (_: Exception) { log("nexus wipe record failed"); wipeGeneration() }

    /** Queues a transport call that is not a show on the PRIVACY lane (never discarded by a trip, never filled by content). [callT] makes no call while the bridge is backing off. */
    private fun submitRaw(body: () -> Unit): Boolean = dispatcher.submit(NexusDispatcher.Item(false, body))

    /** A privacy operation that must not be lost: when the lane rejects it, it runs on a fresh thread (its own call timeout applies). */
    private fun submitOrFresh(body: () -> Unit) { if (!submitRaw(body)) dispatcher.runFresh(body) }

    /** One pending feature push at most. */
    private fun queueFeatures() {
        if (!featuresQueued.compareAndSet(false, true)) return
        if (!submitRaw { featuresQueued.set(false); callT { transport.setFeatures(safeConfig().featureMask()) } }) featuresQueued.set(false)
    }

    /** One pending STT stop at most; never lost (r3 P1-4): a rejected submit runs the stop on a fresh thread. Safe under the sink lock. */
    private fun requestStopListening() {
        if (!stopListenQueued.compareAndSet(false, true)) return
        val body = { stopListenQueued.set(false); callT { transport.stopListening() }; Unit }
        if (!submitRaw(body)) dispatcher.runFresh(body)
    }

    /** Tells the bridge the trip epoch [e]. [wait]: on the trip's own thread, at most the trip-call bound; otherwise best effort in the background. A notice that is not confirmed is re-sent by the next tick. */
    private fun deliverTrip(e: Long, wait: Boolean) {
        val send = { try { transport.tripped(e) } catch (_: Throwable) { NexusResult.ERROR } }
        if (wait) {
            val r = dispatcher.tripCall(send)
            if (r == NexusResult.OK) tripAcked.accumulateAndGet(e) { a, b -> if (a > b) a else b } else log("nexus trip notice unconfirmed")
        } else dispatcher.async { if (send() == NexusResult.OK) tripAcked.accumulateAndGet(e) { a, b -> if (a > b) a else b } }
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

    /**
     * Lock held. Asks for the slot to be hidden: every handle of the slot admitted so far is marked withdrawing, the shows still queued for them are cancelled (a withdrawn show is never drawn, r3 P1-2) and a hide is queued on
     * the privacy lane; the handle leaves the registry when the bridge says it is gone. Never calls the bridge.
     */
    private fun withdrawLocked(slot: Slot) {
        val top = handles[slot] ?: return
        var c: Handle? = top
        while (c != null) {
            c.withdrawing = true
            dispatcher.takeTagged(c).forEach { it.discard(DropReason.TRIPPED) }
            c = c.prev
        }
        enqueueHideLocked(slot)
    }

    /** Lock held. One pending hide per slot: a second request only raises its bound (every handle admitted up to now goes). */
    private fun enqueueHideLocked(slot: Slot) {
        val req = pendingHide[slot]
        if (req != null) { req.bound = seq; return }
        pendingHide[slot] = HideReq(seq)
        if (!submitRaw { runHide(slot) }) {      // the privacy lane is full: run it on a fresh thread (when none is free the handle stays withdrawing and the next tick asks again)
            val bound = pendingHide.remove(slot)?.bound ?: return
            dispatcher.runFresh { doHide(slot, bound) }
        }
    }

    private fun runHide(slot: Slot) {
        val bound = synchronized(lock) { pendingHide.remove(slot)?.bound } ?: return
        doHide(slot, bound)
    }

    private fun doHide(slot: Slot, bound: Long) {
        val r = callT {
            when (slot) {
                Slot.SPEECH -> transport.stopSpeech()
                Slot.LISTEN -> transport.stopListening()
                else -> transport.hide(slot)
            }
        }
        synchronized(lock) {
            val c = handles[slot] ?: return
            if (c.seq > bound) return                     // admitted after this hide was asked for: not ours
            if (!lastTimedOut && !NexusResult.hideMustRetry(r)) handles.remove(slot)
            else { c.withdrawing = true; log("nexus withdraw retry slot=${slot.name} code=${NexusResult.name(r)}") }
        }
    }

    private fun withdrawAllLocked(): Int {
        for (slot in handles.keys.toList()) withdrawLocked(slot)
        return handles.size
    }

    /**
     * A close event: a zone hold, Forget, Delete all, pause, mode off or not worn. Never calls the bridge and never waits for it: it bumps the epoch (every show still queued is discarded unsent and a call already in
     * flight is hidden again by the hide queued right behind it), queues the hides, the listening stop and, for Forget, Delete all and mode off, the history wipe of a NEW debt generation, and tells the bridge the epoch
     * on its own thread (the bridge drops a late show). Call it from the same place that closes PCA's own HUD. Never throws.
     */
    fun trip(reason: TripReason) {
        val wipe = reason == TripReason.FORGET || reason == TripReason.DELETE_ALL || reason == TripReason.MODE_OFF
        if (wipe) owe()                  // persisted FIRST, whatever happens to the bridge
        val cancelled: String?
        val pending: List<NexusDispatcher.Item>
        val e: Long
        // (a) the phone-side closed flag: from here every show that is not yet inside its binder call fails its last check.
        synchronized(lock) {
            e = ++epoch
            lastTrip = reason
            cancelled = cancelAskLocked()
            pending = dispatcher.takeGuarded()
            pending.forEach { it.discard(DropReason.TRIPPED) }
            val left = withdrawAllLocked()
            requestStopListening()      // a session may be opening (authorized, no handle yet): it must not outlive the trip, and a full queue never loses this stop
            log("nexus trip reason=${reason.name} left=${left}")
            if (wipe) enqueueForgetLocked()
            if (reason == TripReason.MODE_OFF) submitOrFresh { callT { transport.setFeatures(NexusFeatures.NONE) } }
        }
        // (b) + (c) the bridge learns the epoch BEFORE any further content is sent: wait (at most EXCLUSIVE_WAIT_MS) until no send is inside its binder call, and deliver the epoch on its own thread (at most TRIP_CALL_MS) while no
        // new send can start. When the wait runs out the trip goes on anyway: the bridge refuses every show stamped with an older epoch before it renders anything. Worst case here: 250 + 500 ms, whatever the bridge does.
        val held = dispatcher.tryExclusive()
        try { deliverTrip(e, wait = true) } finally { if (held) dispatcher.releaseExclusive() }
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

    /** Lock held. One queued wipe request at most; it carries the newest generation when it runs. */
    private fun enqueueForgetLocked() {
        if (forgetQueued) return
        forgetQueued = true
        if (!submitRaw { runForget() }) { forgetQueued = false; dispatcher.runFresh { runForget() } }
    }

    private fun runForget() {
        synchronized(lock) { forgetQueued = false }
        val g = try { if (ledger.owed()) ledger.generation() else return } catch (_: Exception) { return }
        val r = callT { transport.forgetHistory(g) }
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
                if (h.withdrawing) enqueueHideLocked(h.slot)
                else if (now >= h.expiresAt) { if (h.slot == Slot.LISTEN) withdrawLocked(h.slot) else handles.remove(h.slot) }
            }
            if (forgetOwed()) enqueueForgetLocked()
            handles.values.toList()
        }
        retryTripNotice()
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

    /** The bridge has not confirmed the latest trip epoch: tell it again (background, one at a time). */
    private fun retryTripNotice() {
        val e = epoch
        if (tripAcked.get() >= e || !dispatcher.healthy() || !tripRetrying.compareAndSet(false, true)) return
        dispatcher.async { try { deliverTrip(e, wait = false) } finally { tripRetrying.set(false) } }
    }

    /** The bridge process is back (or new): it holds nothing, and whatever the old one drew is unknown. Hide every slot we can and re-push the switches. Queued, never waits for the bridge. */
    fun onBridgeConnected() {
        val pending: List<NexusDispatcher.Item>
        synchronized(lock) {
            tripAcked.set(++epoch)          // a new bridge process starts at epoch 0: nothing is owed to it
            handles.clear(); pendingHide.clear()
            ask = null
            pending = dispatcher.takeGuarded()
            pending.forEach { it.discard(DropReason.TRIPPED) }
            submitOrFresh { callT { transport.hide(Slot.NOTICE) }; callT { transport.hide(Slot.PIN) } }
            submitOrFresh { callT { transport.stopSpeech() } }
            requestStopListening()
            queueFeatures()
            submitOrFresh { callT { transport.setSkillHost(skills != null || asks != null) } }     // the host also answers `authorizeListen` for the assist ask
            if (forgetOwed()) enqueueForgetLocked()
        }
        dispatcher.markOk()
    }

    /** Hands the sink's inbound events to the bridge (queued: a hung bridge cannot block the connecting thread), then [onBridgeConnected]. */
    internal fun attach() {
        submitRaw { callT { transport.setEvents(events) } }
        onBridgeConnected()
    }

    /** The provider lets go of this sink: stops the dispatcher; whatever was queued is discarded unsent. */
    fun close() {
        dispatcher.close().forEach { it.discard(DropReason.TRIPPED) }
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
            queueFeatures()
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
        override fun onForgetAccepted(generation: Long) {
            // An acknowledgement settles the debt only up to the generation its request carried: a late one for an older wipe never clears a newer debt.
            ackedGen.accumulateAndGet(generation) { a, b -> if (a > b) a else b }
            val now = System.nanoTime()
            val late = inFlightShows.values.any { it.gen < generation && now - it.atNanos < IN_FLIGHT_MAX_NS }     // a show that began before this wipe was requested has not returned yet: it may create its notice after the wipe (r3 P1-3)
            try { ledger.settle(generation) } catch (_: Exception) { log("nexus wipe ack failed") }
            if (late) { historyDirty(); return }
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
                    requestStopListening()
                    refusal = id to refusalFor(block)
                } else {
                    superseded = cancelAskLocked()
                    ask = Ask(id, clock() + NexusLimits.ASK_MAX_MS)
                    handles[Slot.LISTEN] = Handle(Slot.LISTEN, emptySet(), false, id, clock() + NexusLimits.LISTEN_MAX_MS, emptySet(), ++seq, null)
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
        /** A show whose binder call has not returned after this long no longer holds a wipe acknowledgement back (a call that never returns must not loop the wipe forever); when it does return the check on return still applies. */
        private const val IN_FLIGHT_MAX_NS = 120_000_000_000L

        /** Binds the sink to the bridge's port binder (the only public way to get a sink). */
        fun connect(
            binder: IBinder, gates: NexusGates, config: () -> NexusConfig, actions: NexusActionHandler, log: (String) -> Unit = {},
            asks: NexusAskHandler? = null, skills: NexusSkillProvider? = null,
            ledger: WipeLedger = MemoryWipeLedger(), onForgetAcked: () -> Unit = {},
            /** Hard timeout of ONE binder call. */
            callTimeoutMs: Long = NexusLimits.CALL_TIMEOUT_MS,
            /** Tests only: run every transport call on the caller's thread, without a timeout. */
            direct: Boolean = false,
        ): NexusHudSink {
            val t = AidlNexusTransport.from(binder)
            val d = if (direct) NexusDispatcher.direct() else NexusDispatcher.threaded(callTimeoutMs)
            val sink = NexusHudSink(t, gates, config, actions, log = log, asks = asks, skills = skills, onForgetAcked = onForgetAcked, wipeLedger = ledger, dispatcher = d)
            sink.attach()
            return sink
        }
    }
}
