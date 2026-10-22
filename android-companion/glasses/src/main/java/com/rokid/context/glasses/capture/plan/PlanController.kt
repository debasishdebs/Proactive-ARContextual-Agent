package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanPersistence
import com.rokid.context.capture.plan.PlanReceiver
import com.rokid.context.capture.plan.ReceiverState
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.CapturePlanMessage
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState

/** What the controller needs from the live capture objects (null host = no Smart session: nothing is running, every swap is safe). */
interface PlanHost {
    /** The detector is QUIET (no bout, no pending glance) and no camera episode is open. */
    fun quiet(): Boolean
    /** Drops the running bout (the 10 s forced swap). */
    fun abortBout()
    /** Swaps the classifier's glance config (the gate and the session read [PlanController.active] themselves). */
    fun install(cfg: ActiveConfig)
}

/**
 * Glasses side of the capture plan (S59, S67, S69). Wraps the pure [PlanReceiver] and owns the one thing it cannot: when a staged
 * plan becomes the real capture inputs.
 *
 * - **Single-threaded by construction:** the receiver is private and every entry point takes [lock]; acks are sent after the lock is
 *   released. Messages arrive on a link thread, [tick] on the capture supervisor, [onPhoneSettings] on the IO scope.
 * - **Staged, then atomic:** a message is persisted as staged. The immutable [ActiveConfig] is installed (and EFFECTIVE acked) only
 *   when the detector is QUIET and no camera episode is open, or after 10 s by aborting the running bout. The glance config, the
 *   gate values and the policy rows change in the same call, never one by one. A crash in between leaves SETTINGS (the persisted
 *   state is still staged and is re-activated at the first safe tick).
 * - **LIVE is never applied** (the receiver refuses it; a persisted LIVE state is reverted `unsupported:live` on load). TRIAL ends by
 *   itself on the local clock and on a reboot; the check runs on load before anything persisted is applied.
 * - SHADOW and SETTINGS map to [ActiveConfig.SETTINGS]: real capture is untouched (S67).
 * - No timers: [tick] rides the capture supervisor's existing 1 s loop and returns at once when nothing is pending.
 */
class PlanController(
    persist: PlanPersistence,
    private val elapsed: () -> Long,
    bootId: () -> String,
    private val appliedState: () -> ContextState,
    private val send: (CapturePlanAck) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val receiver = PlanReceiver(persist, elapsed, bootId)

    /** The config capture reads right now. Changes only inside [lock], at a safe boundary. */
    @Volatile var active: ActiveConfig = ActiveConfig.SETTINGS
        private set
    @Volatile var host: PlanHost? = null

    /** The session host appears (a Smart session starts): installs the config in force under the lock, so an activation racing the start is never missed. */
    fun attach(h: PlanHost) = synchronized(lock) { host = h; h.install(active) }

    /** The phone's `sessionStartMs` of the latest plan message: every ack echoes it, or the phone's PlanAckGate counts the ack STALE. */
    private var session = 0L

    private var pendingCfg: ActiveConfig? = null
    private var pendingGen = -1L
    private var stagedAt = 0L

    init { synchronized(lock) { bootstrap() } }

    /** Load: end an expired or foreign-boot TRIAL FIRST, then resume what is still valid. A staged state waits for the first tick. */
    private fun bootstrap() {
        try {
            receiver.tick(appliedState(), elapsed())
            val st = receiver.current ?: return
            if (st.appliedGen in st.terminal) return
            if (st.mode == PlanMode.LIVE) { receiver.revert("unsupported:live"); return }
            val cfg = configOf(st) ?: run { receiver.revert("auto:rejected"); return }
            if (st.effective) active = cfg else { pendingCfg = cfg; pendingGen = st.appliedGen; stagedAt = elapsed() }
        } catch (e: Exception) {
            active = ActiveConfig.SETTINGS; dropPending()   // never crash the app on start: no plan applied
            log("plan load failed: ${e.javaClass.simpleName}")
        }
    }

    /** The plan message from the phone. Returns the immediate ack (STAGED, REJECTED or a re-ack); EFFECTIVE follows when activated. */
    fun onMessage(m: CapturePlanMessage): CapturePlanAck = locked { out ->
        session = m.sessionStartMs
        try {
            val ack = receiver.onMessage(m)
            out += ack
            val st = receiver.current
            if (ack.state == PlanAckState.STAGED && st != null && st.appliedGen == m.gen && !st.effective && pendingGen != m.gen) {
                val cfg = configOf(st)
                if (cfg == null) { out += receiver.revert("auto:rejected"); dropPending() }
                else { pendingCfg = cfg; pendingGen = m.gen; stagedAt = elapsed(); tryActivate(stagedAt, out) }
            }
            ack.copy(sessionStartMs = session)
        } catch (e: Exception) {
            // The state could not be persisted: nothing is staged, nothing changes, and the throw never reaches the link callback.
            log("plan message not applied: ${e.javaClass.simpleName}")
            CapturePlanAck(gen = m.gen, publishId = m.publishId, mode = m.mode, state = PlanAckState.REJECTED, reason = "persist", caps = PlanReceiver.CAPS, phoneInstall = m.phoneInstall).also { out += it }
        }
    }

    /** The capture supervisor's 1 s beat ([now] = elapsedRealtime): ends a TRIAL, activates a staged plan at its boundary. */
    fun tick(now: Long) {
        if (synchronized(lock) { pendingCfg == null && receiver.current?.mode != PlanMode.TRIAL && !receiver.persistPending }) return   // the common idle case: one uncontended lock, no IO
        locked { out ->
            try {
                receiver.tick(appliedState(), now)?.let { reverted(it, out) }
                tryActivate(now, out)
            } catch (e: Exception) { log("plan tick failed: ${e.javaClass.simpleName}") }
        }
    }

    /** A phone that does not speak plans (planAware false in a fresh `pc_settings`) while a plan is held: back to settings. */
    fun onPhoneSettings(planAware: Boolean) {
        if (planAware) return
        locked { out ->
            try {
                val st = receiver.current
                if (st != null && st.mode != PlanMode.SETTINGS && st.appliedGen !in st.terminal) reverted(receiver.revert("auto:old_phone"), out)
            } catch (e: Exception) { failClosed(); log("plan revert failed: ${e.javaClass.simpleName}") }
        }
    }

    /** Local auto-revert (ceiling, opens, heat: Task 12). Null when there is no plan to revert. */
    fun revert(reason: String): CapturePlanAck? = locked { out ->
        if (receiver.current == null) return@locked null
        try { receiver.revert(reason).also { reverted(it, out) } } catch (e: Exception) { failClosed(); log("plan revert failed: ${e.javaClass.simpleName}"); null }
    }

    /** What the phone should know on a session open: the caps, and the state of the current gen (gen 0 = no plan ever). */
    fun statusAck(): CapturePlanAck = synchronized(lock) {
        // A TRIAL past its limit is ended before it is reported, even while the capture service (and its tick) is not running.
        try { receiver.tick(appliedState(), elapsed())?.let { reverted(it, ArrayList()) } } catch (e: Exception) { failClosed(); log("plan tick failed: ${e.javaClass.simpleName}") }
        val st = receiver.current ?: return CapturePlanAck(gen = 0, caps = PlanReceiver.CAPS)
        val reason = st.terminal[st.appliedGen]
        val was = st.terminalMeta[st.appliedGen]
        CapturePlanAck(
            gen = st.appliedGen, publishId = was?.publishId ?: st.publishId, mode = was?.mode ?: st.mode, effectiveHash = if (reason == null) st.configHash else null,
            state = if (reason != null) PlanAckState.REVERTED else if (st.effective) PlanAckState.EFFECTIVE else PlanAckState.STAGED,
            reason = reason, caps = PlanReceiver.CAPS, phoneInstall = st.phoneInstall, sessionStartMs = session,
        )
    }

    // ---- under the lock ----

    private fun configOf(st: ReceiverState): ActiveConfig? = when (st.mode) {
        PlanMode.SETTINGS, PlanMode.SHADOW -> ActiveConfig.SETTINGS
        PlanMode.TRIAL -> st.planJson?.let { PlanJson.decodeStrict(it).getOrNull() }?.let { ActiveConfig.of(it) }
        else -> null   // LIVE: never applied
    }

    private fun tryActivate(now: Long, out: MutableList<CapturePlanAck>) {
        val cfg = pendingCfg ?: return
        val h = host
        if (cfg != active && h != null && !h.quiet()) {
            if (now - stagedAt < FORCE_AFTER_MS) return
            h.abortBout()
        }
        val prev = active
        swap(cfg)
        try {
            val ack = receiver.activated(pendingGen)
            if (ack.state == PlanAckState.EFFECTIVE) out += ack else swap(prev)
            dropPending()
        } catch (e: Exception) {
            swap(prev)   // not persisted as effective: stay on the old inputs and retry
            stagedAt = now   // back off: a failing write forces (aborts a bout) at most once per FORCE_AFTER_MS
            log("plan activation not persisted: ${e.javaClass.simpleName}")
        }
    }

    /** A revert is always safe, so it does not wait for a boundary: a running bout is dropped and the settings come back at once. */
    private fun reverted(ack: CapturePlanAck, out: MutableList<CapturePlanAck>) {
        dropPending()
        if (ack.state == PlanAckState.REVERTED) {
            if (active != ActiveConfig.SETTINGS) {
                host?.let { if (!it.quiet()) it.abortBout() }
                swap(ActiveConfig.SETTINGS)
            }
            out += ack
        }
    }

    /** The settings path, in memory, whatever the disk says: used when a revert cannot be recorded. */
    private fun failClosed() {
        dropPending()
        if (active != ActiveConfig.SETTINGS) { host?.let { if (!it.quiet()) it.abortBout() }; swap(ActiveConfig.SETTINGS) }
    }

    private fun swap(cfg: ActiveConfig) { active = cfg; host?.install(cfg) }

    private fun dropPending() { pendingCfg = null; pendingGen = -1L }

    private fun <T> locked(body: (MutableList<CapturePlanAck>) -> T): T {
        val out = ArrayList<CapturePlanAck>(2)
        val r = synchronized(lock) { body(out).also { for (i in out.indices) out[i] = out[i].copy(sessionStartMs = session) } }
        for (a in out) try { send(a) } catch (e: Exception) { log("plan ack not sent: ${e.javaClass.simpleName}") }
        return r
    }

    companion object { const val FORCE_AFTER_MS = 10_000L }
}
