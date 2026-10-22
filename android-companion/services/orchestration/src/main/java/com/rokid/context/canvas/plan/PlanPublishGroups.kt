package com.rokid.context.canvas.plan

import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.db.CanvasGroupStateEntity
import com.rokid.context.memory.db.CanvasSlice3Dao
import java.util.concurrent.atomic.AtomicInteger

/** The persisted row per publish group (`canvas_group_state`, S62). */
interface GroupStore {
    suspend fun get(groupId: String): CanvasGroupStateEntity?
    suspend fun all(): List<CanvasGroupStateEntity>
    suspend fun put(s: CanvasGroupStateEntity)
}

class DaoGroupStore(private val dao: CanvasSlice3Dao) : GroupStore {
    override suspend fun get(groupId: String) = dao.groupState(groupId)
    override suspend fun all() = dao.groupStates()
    override suspend fun put(s: CanvasGroupStateEntity) = dao.upsertGroupState(s)
}

/** Where the plan JSON of a publish lives (the `canvas_publish` row of the group), keyed by the publish id so a crashed PREPARED group can be sent again. */
interface PlanSource {
    suspend fun save(groupId: String, publishId: Long, planJson: String)
    suspend fun load(groupId: String, publishId: Long): String?
}

data class SendOutcome(val gen: Long, val sent: Boolean)

/** The phone's way to the glasses (`PlanSender` in `:phone`): allocates the durable gen and sends; `sent = false` means stored, sent again when a session opens. */
interface PlanTransport {
    suspend fun send(mode: PlanMode, planJson: String?, configHash: String?, publishId: String, trialMaxMs: Long?): SendOutcome
}

/** The desired state a compensation puts back (`mode` SETTINGS = use settings). */
data class PreviousDesired(val mode: PlanMode, val planJson: String?, val configHash: String?)

/**
 * The publish state machine of the capture plan component (S25, S62), one persisted row per group:
 * DESIRED -> PREPARED -> SENT -> EFFECTIVE | FAILED | UNKNOWN_REMOTE (10 min without an answer); UNKNOWN_REMOTE -> EFFECTIVE on a later matching ack, or
 * compensated (a NEW gen carrying the previous desired state); REVERTED(gen) is terminal for that gen.
 *
 * An ack matches only on gen, publishId, mode, configHash (EFFECTIVE) and phoneInstall; anything else, an ack for a superseded gen, or one after a terminal revert
 * is ignored and counted. REJECTED `persist` (the glasses could not write the activation; the gen is not terminal there) is RETRYABLE: the row stays SENT and
 * the desired state is sent again on the next session. Every transition is written before its side effect, so a crash is recovered by [recover]: PREPARED is sent,
 * SENT keeps waiting (or times out), EFFECTIVE activates the phone halves again (the activator is idempotent).
 */
class PlanPublishGroups(
    private val store: GroupStore,
    private val transport: PlanTransport,
    private val install: () -> String,
    private val clock: () -> Long,
    /** Activates the phone halves of an EFFECTIVE group (null = use settings). Idempotent. */
    private val activate: suspend (groupId: String, planJson: String?) -> Unit,
    private val plans: PlanSource,
) {
    private val ignored = AtomicInteger()
    private val retries = AtomicInteger()
    val ignoredAcks: Int get() = ignored.get()
    val persistRetries: Int get() = retries.get()

    private suspend fun write(r: CanvasGroupStateEntity, state: String, gen: Long = r.gen, sentAtMs: Long? = r.sentAtMs): CanvasGroupStateEntity =
        r.copy(state = state, gen = gen, sentAtMs = sentAtMs, updatedAt = clock()).also { store.put(it) }

    /** A validated desired state for [groupId]. A group left UNKNOWN_REMOTE is compensated first when [previous] is given. */
    suspend fun publish(groupId: String, publishId: Long, mode: PlanMode, planJson: String?, configHash: String?, trialMaxMs: Long?, previous: PreviousDesired? = null) {
        if (previous != null && store.get(groupId)?.state == STATE_UNKNOWN) compensate(groupId, previous)
        val row = CanvasGroupStateEntity(groupId, store.get(groupId)?.gen ?: 0L, publishId, configHash.orEmpty(), COMPONENT, "DESIRED", clock(), mode.name, install(), null)
        store.put(row)
        if (planJson != null) plans.save(groupId, publishId, planJson)
        val prepared = write(row, "PREPARED")
        send(prepared, planJson, trialMaxMs)
    }

    private suspend fun send(prepared: CanvasGroupStateEntity, planJson: String?, trialMaxMs: Long?) {
        val out = transport.send(PlanMode.valueOf(prepared.mode!!), planJson, prepared.configHash.ifEmpty { null }, prepared.publishId.toString(), trialMaxMs)
        write(prepared, "SENT", gen = out.gen, sentAtMs = clock())
    }

    /** UNKNOWN_REMOTE only: a new gen with [previous]. False when the group is in another state. */
    suspend fun compensate(groupId: String, previous: PreviousDesired): Boolean {
        val r = store.get(groupId)?.takeIf { it.state == STATE_UNKNOWN } ?: return false
        val publishId = maxOf(r.publishId + 1, clock())
        previous.planJson?.let { plans.save(groupId, publishId, it) }
        val next = r.copy(publishId = publishId, configHash = previous.configHash.orEmpty(), mode = previous.mode.name, phoneInstall = install(), state = "PREPARED", updatedAt = clock())
        store.put(next)
        send(next, previous.planJson, null)
        return true
    }

    /** An ack the hub already classified as this install and session. */
    suspend fun onAck(a: CapturePlanAck) {
        val r = store.all().firstOrNull { it.gen == a.gen && it.gen != 0L }
        if (r == null || a.phoneInstall != r.phoneInstall || a.publishId != r.publishId.toString() || a.mode.name != r.mode) { ignored.incrementAndGet(); return }
        if (r.state == STATE_REVERTED) { ignored.incrementAndGet(); return }
        when (a.state) {
            PlanAckState.STAGED -> Unit
            PlanAckState.EFFECTIVE -> when {
                r.state == "EFFECTIVE" -> Unit
                r.state != "SENT" && r.state != STATE_UNKNOWN -> ignored.incrementAndGet()
                a.effectiveHash != r.configHash.ifEmpty { null } -> ignored.incrementAndGet()
                else -> { write(r, "EFFECTIVE"); activateOf(r) }
            }
            PlanAckState.REJECTED -> when {
                r.state != "SENT" && r.state != STATE_UNKNOWN -> ignored.incrementAndGet()
                a.reason == "persist" -> retries.incrementAndGet()   // retryable, never a verdict: the same gen is applied on the next send
                else -> write(r, "FAILED")
            }
            PlanAckState.REVERTED -> write(r, STATE_REVERTED)
        }
    }

    private suspend fun activateOf(r: CanvasGroupStateEntity) {
        val mode = PlanMode.valueOf(r.mode!!)
        if (mode == PlanMode.SETTINGS) { activate(r.groupId, null); return }
        val json = plans.load(r.groupId, r.publishId) ?: return   // the plan is gone (wiped): nothing to activate
        activate(r.groupId, json)
    }

    /** SENT for 10 minutes without an answer becomes UNKNOWN_REMOTE (a later matching EFFECTIVE still activates). */
    suspend fun tick() {
        val now = clock()
        store.all().filter { it.state == "SENT" && (it.sentAtMs ?: now) + UNKNOWN_AFTER_MS <= now }.forEach { write(it, STATE_UNKNOWN) }
    }

    /** After a restart: finish what a crash interrupted. */
    suspend fun recover() {
        for (r in store.all().sortedBy { it.updatedAt }) when (r.state) {
            "DESIRED" -> write(r, "FAILED")
            "PREPARED" -> {
                val mode = PlanMode.valueOf(r.mode!!)
                val json = if (mode == PlanMode.SETTINGS) null else plans.load(r.groupId, r.publishId)
                if (mode == PlanMode.TRIAL || (mode != PlanMode.SETTINGS && json == null)) write(r, "FAILED") else send(r, json, null)
            }
            "EFFECTIVE" -> activateOf(r)
        }
        tick()
    }

    companion object {
        const val COMPONENT = "capture_plan"
        const val UNKNOWN_AFTER_MS = 10 * 60_000L
        private const val STATE_UNKNOWN = "UNKNOWN_REMOTE"
        private const val STATE_REVERTED = "REVERTED"
    }
}
