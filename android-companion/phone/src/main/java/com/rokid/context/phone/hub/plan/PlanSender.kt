package com.rokid.context.phone.hub.plan

import android.content.SharedPreferences
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.CapturePlanMessage
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.Wire
import com.rokid.context.link.GlassesLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What a plan send did: the durable [gen] it was given and whether the link took it now (false = stored, sent again when a session opens). */
data class PlanSendResult(val gen: Long, val sent: Boolean)

/**
 * The only way the canvas server reaches the glasses with a plan. It can send SHADOW and nothing else: there is no LIVE or TRIAL entry point (M1 debug publish).
 * Task 22 is the only caller (the canvas debug route); the real publish path (gates, TRIAL, LIVE) is a later task and gets its own methods.
 */
interface PlanPublishPort {
    suspend fun sendShadow(planJson: String, configHash: String): PlanSendResult
}

/**
 * Plan reconciliation. It lives in the hub (built by `HubGraph`, not by the canvas graph), so stopping the canvas server never loses the desired plan: the
 * desired state (a durable [CapturePlanMessage] with its gen) is persisted in [prefs] and re-sent with a fresh `atMs` and the current session stamp every time
 * a glasses session opens ([onSessionReady]). Acks come back through [onAck] (already classified by the hub: this install, this session) and set [lastAck].
 * The glasses treat a re-sent gen they already hold as a re-ack, so a re-send is safe.
 */
class PlanSender(
    private val link: GlassesLink,
    private val prefs: SharedPreferences,
    private val install: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Later (Task 13): the phone half of an accepted plan is applied on the phone. Called with the plan JSON, or null when the desired state is "use settings". */
    private val onDesired: (String?) -> Unit = {},
    /** The persisted deletion counter NOW: a plan built before a Delete all must not be persisted after it (S57). */
    private val epochCounter: () -> Long = { 0L },
) : PlanPublishPort, com.rokid.context.canvas.plan.PlanTransport {
    private val lock = Any()
    private val _desired = MutableStateFlow(load())
    /** The `capturePlan` flow: the desired state, the message as last built (null = nothing was ever sent). */
    val capturePlan: StateFlow<CapturePlanMessage?> get() = _desired
    private val _ack = MutableStateFlow<CapturePlanAck?>(null)
    /** The newest ack that matched the outstanding gen. */
    val lastAck: StateFlow<CapturePlanAck?> get() = _ack
    @Volatile private var sessionStartMs = 0L

    override suspend fun sendShadow(planJson: String, configHash: String): PlanSendResult {
        val c0 = epochCounter()
        val msg = synchronized(lock) {
            val gen = prefs.getLong(KEY_GEN, 0L) + 1
            val m = CapturePlanMessage(gen = gen, publishId = "debug-$gen", mode = PlanMode.SHADOW, configHash = configHash, planJson = planJson, phoneInstall = install())
            persist(m, c0)
            _desired.value = m
            m
        }
        runCatching { onDesired(planJson) }
        return PlanSendResult(msg.gen, push(msg))
    }

    /**
     * The publish groups' way to the glasses (Task 26): any mode, with the group's publish id echoed in every ack. Like [sendShadow], the desired state is persisted and
     * sent again at each session open. TRIAL/LIVE entry points are the later publish task's to expose.
     */
    override suspend fun send(mode: PlanMode, planJson: String?, configHash: String?, publishId: String, trialMaxMs: Long?): com.rokid.context.canvas.plan.SendOutcome {
        val c0 = epochCounter()
        val msg = synchronized(lock) {
            val gen = prefs.getLong(KEY_GEN, 0L) + 1
            val m = CapturePlanMessage(gen = gen, publishId = publishId, mode = mode, configHash = configHash, planJson = planJson, trialMaxMs = trialMaxMs, phoneInstall = install())
            persist(m, c0)
            _desired.value = m
            m
        }
        runCatching { onDesired(if (mode == PlanMode.SETTINGS) null else planJson) }
        return com.rokid.context.canvas.plan.SendOutcome(msg.gen, push(msg))
    }

    /** Back to "use settings" (a new gen, so the glasses drop the plan). */
    suspend fun revertToSettings(): PlanSendResult {
        val c0 = epochCounter()
        val msg = synchronized(lock) {
            val gen = prefs.getLong(KEY_GEN, 0L) + 1
            val m = CapturePlanMessage(gen = gen, mode = PlanMode.SETTINGS, phoneInstall = install())
            persist(m, c0)
            _desired.value = m
            m
        }
        runCatching { onDesired(null) }
        return PlanSendResult(msg.gen, push(msg))
    }

    /** A glasses session opened: send the desired state again, stamped for this session. */
    suspend fun onSessionReady(sessionStartMs: Long) {
        this.sessionStartMs = sessionStartMs
        _ack.value = null
        _desired.value?.let { push(it) }
    }

    /** An ack of this install and session; only the outstanding gen counts. */
    fun onAck(ack: CapturePlanAck) {
        if (ack.gen == _desired.value?.gen) _ack.value = ack
    }

    private suspend fun push(m: CapturePlanMessage): Boolean {
        val out = m.copy(phoneInstall = install(), atMs = maxOf(clock(), sessionStartMs), sessionStartMs = sessionStartMs)
        return try { link.send(Channels.CAPTURE_PLAN, Wire.encode(out)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
    }

    /** Delete all: the desired plan and its ack are forgotten in memory (the prefs were cleared by the wipe), so a reconnect re-sends nothing; the glasses are told "use settings" by the next publish. */
    fun resetAfterWipe() {
        synchronized(lock) { _desired.value = null; _ack.value = null }
        runCatching { onDesired(null) }
    }

    private fun persist(m: CapturePlanMessage, c0: Long) {
        check(epochCounter() == c0) { "plan refused: a Delete all happened meanwhile" }
        check(prefs.edit().putLong(KEY_GEN, m.gen).putString(KEY_MSG, Wire.encode(m)).commit()) { "plan state not persisted" }
    }

    private fun load(): CapturePlanMessage? = prefs.getString(KEY_MSG, null)?.let { (Wire.decode<CapturePlanMessage>(it) as? com.rokid.context.core.model.Decoded.Ok)?.value }

    companion object {
        const val PREFS = com.rokid.context.orchestration.MemoryWipe.PLAN_STATE_PREFS // ruled on ahead of this task: excluded from backup and device transfer
        private const val KEY_GEN = "gen"
        private const val KEY_MSG = "msg"
    }
}
