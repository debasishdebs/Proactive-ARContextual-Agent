package com.rokid.context.capture.plan

import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.CapturePlanMessage
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

interface PlanPersistence { fun load(): ReceiverState?; fun commit(s: ReceiverState) }

/** [effective] is false while a plan is only staged (S69): it becomes true at [PlanReceiver.activated]. */
data class ReceiverState(
    val appliedGen: Long, val phoneInstall: String, val mode: PlanMode, val planJson: String?, val configHash: String?,
    val trialStartElapsed: Long?, val trialMaxMs: Long?, val bootId: String, val previous: ReceiverState?, val terminal: Map<Long, String>,
    val effective: Boolean = false,
    /** The phone's publish id for [appliedGen] (echoed in every ack so the phone can match it, S62). */
    val publishId: String? = null,
    /** The original mode and publish id of each terminal gen, so a re-ack of a REVERTED gen reports what was reverted. */
    val terminalMeta: Map<Long, TerminalMeta> = emptyMap(),
)

/** What a REVERTED gen was before it reverted. */
data class TerminalMeta(val mode: PlanMode, val publishId: String?)

/**
 * Pure plan receiver for the glasses (S59). Stale and duplicate gens are answered without re-applying; a REVERTED gen is final; a
 * TRIAL is bounded on the glasses' own elapsed clock and ends on a reboot. Not thread-safe.
 */
class PlanReceiver(
    private val persist: PlanPersistence, private val elapsed: () -> Long, bootId: () -> String,
    /** The capabilities this build advertises and enforces. Tests and the expression runner (X3) pass [CAPS_EXPR]. */
    private val caps: List<String> = CAPS,
) {
    /** Read once: a boot id cannot change inside a process. A persisted state from another boot is detected by comparing it to this. */
    private val boot: String = bootId()
    private var st: ReceiverState? = persist.load()

    val current: ReceiverState? get() = st

    /**
     * Acks to a message echo that message's `sessionStartMs`; the phone matches acks by gen, install and session (PlanAckGate). Acks the
     * glasses originate (activated, tick, revert) are stamped with the latest phone session start seen in a message; the adapter that
     * runs a receiver across a phone session change must keep sending the current gen so this value is fresh.
     */
    private var lastSessionStartMs = 0L

    /** A revert whose write failed: the in-memory state is already reverted (fail closed); [tick] retries the write. */
    var persistPending: Boolean = false
        private set

    fun onMessage(m: CapturePlanMessage): CapturePlanAck {
        lastSessionStartMs = maxOf(lastSessionStartMs, m.sessionStartMs)
        val cur = st?.takeIf { it.phoneInstall == m.phoneInstall }   // gens count per phone install
        cur?.terminal?.get(m.gen)?.let {
            val was = cur.terminalMeta[m.gen]
            return ack(m.gen, was?.mode ?: cur.mode, PlanAckState.REVERTED, it, null, m.phoneInstall, was?.publishId ?: m.publishId, m.sessionStartMs)
        }
        if (cur != null && m.gen < cur.appliedGen) return reject(m, "stale")
        if (cur != null && m.gen == cur.appliedGen) {
            return ack(m.gen, cur.mode, if (cur.effective) PlanAckState.EFFECTIVE else PlanAckState.STAGED, null, cur.configHash, m.phoneInstall, m.publishId, m.sessionStartMs)
        }
        // Unrestricted LIVE is not a capability of this build: a buggy or rogue phone must not get an unbounded plan staged (round 3).
        if (m.mode == PlanMode.LIVE && "live" !in caps) return reject(m, "unsupported:live")
        if (m.mode == PlanMode.TRIAL && (m.trialMaxMs == null || m.trialMaxMs !in 1L..MAX_TRIAL_MS)) return reject(m, "bounds")
        var planJson: String? = null
        if (m.mode != PlanMode.SETTINGS) {
            planJson = m.planJson
            if (planJson == null || m.configHash == null) return reject(m, "schema")
            val bytes = planJson.toByteArray(Charsets.UTF_8).size
            if (bytes > PLAN_MAX_BYTES) return reject(m, "too_big")
            val raw = runCatching { Json.parseToJsonElement(planJson) as? JsonObject }.getOrNull() ?: return reject(m, "schema")
            // S24: a plan that needs a capability this build lacks is refused as such, before any version or bounds judgement.
            val hasExprs = ((raw["config"] as? JsonObject)?.get("exprs") as? JsonArray)?.isNotEmpty() == true
            if (hasExprs) for (cap in listOf(CAP_EXPR, CAP_SIG1)) if (cap !in caps) return reject(m, "unsupported:$cap")
            val plan = PlanJson.decodeStrict(planJson).getOrNull()
            val problems = PlanValidator.problems(raw, plan, bytes)
            if (problems.any { it.startsWith("plan schema ") }) return reject(m, "version")
            if (problems.isNotEmpty()) return reject(m, "bounds")
            if (plan == null) return reject(m, "schema")
            if (plan.configHash != m.configHash || PlanJson.configHash(plan.config) != m.configHash) return reject(m, "schema")
        }
        val next = ReceiverState(
            appliedGen = m.gen, phoneInstall = m.phoneInstall, mode = m.mode, planJson = planJson, configHash = m.configHash.takeIf { planJson != null },
            trialStartElapsed = null, trialMaxMs = m.trialMaxMs.takeIf { m.mode == PlanMode.TRIAL }, bootId = boot,
            previous = (cur ?: st)?.copy(previous = null), terminal = cur?.terminal ?: emptyMap(), effective = false, publishId = m.publishId,
            terminalMeta = cur?.terminalMeta ?: emptyMap(),
        )
        persist.commit(next); st = next
        return ack(m.gen, m.mode, PlanAckState.STAGED, null, next.configHash, m.phoneInstall, m.publishId, m.sessionStartMs)
    }

    /** The staged plan became the applied one at the safe boundary. A TRIAL starts its clock here, once. */
    fun activated(gen: Long): CapturePlanAck {
        val cur = st
        if (cur == null || cur.appliedGen != gen || gen in cur.terminal) {
            return CapturePlanAck(gen = gen, state = PlanAckState.REJECTED, reason = "stale", phoneInstall = cur?.phoneInstall ?: "", caps = caps)
        }
        if (!cur.effective) {
            val next = cur.copy(
                effective = true, bootId = boot,
                trialStartElapsed = if (cur.mode == PlanMode.TRIAL) cur.trialStartElapsed ?: elapsed() else null,
            )
            persist.commit(next); st = next
        }
        val s = st!!
        return ack(gen, s.mode, PlanAckState.EFFECTIVE, null, s.configHash, s.phoneInstall, s.publishId)
    }

    /** [now] is the glasses' `elapsedRealtime`. Returns the REVERTED ack when the TRIAL ended, else null. */
    @Suppress("UNUSED_PARAMETER")
    fun tick(state: ContextState, now: Long): CapturePlanAck? {
        retryPersist()
        val cur = st ?: return null
        if (cur.mode != PlanMode.TRIAL || cur.appliedGen in cur.terminal) return null
        if (cur.bootId != boot) return revert("auto:reboot")
        val start = cur.trialStartElapsed ?: return null
        val max = cur.trialMaxMs ?: return revert("auto:trial_end")
        if (now < start) return revert("auto:reboot")      // elapsed went backwards: treat as a reboot
        return if (now - start >= max) revert("auto:trial_end") else null
    }

    /** Back to the settings path; the gen is terminal and is never applied again. The first reason stays: a second revert re-acks it. */
    fun revert(reason: String): CapturePlanAck {
        val cur = st ?: return CapturePlanAck(gen = 0, state = PlanAckState.REJECTED, reason = "stale", caps = caps)
        cur.terminal[cur.appliedGen]?.let { first ->
            val was = cur.terminalMeta[cur.appliedGen]
            return ack(cur.appliedGen, was?.mode ?: cur.mode, PlanAckState.REVERTED, first, null, cur.phoneInstall, was?.publishId ?: cur.publishId)
        }
        val terminal = (cur.terminal + (cur.appliedGen to reason)).entries.sortedBy { it.key }.takeLast(MAX_TERMINAL).associate { it.key to it.value }
        val meta = (cur.terminalMeta + (cur.appliedGen to TerminalMeta(cur.mode, cur.publishId))).filterKeys { it in terminal }
        val next = ReceiverState(
            appliedGen = cur.appliedGen, phoneInstall = cur.phoneInstall, mode = PlanMode.SETTINGS, planJson = null, configHash = null,
            trialStartElapsed = null, trialMaxMs = null, bootId = boot, previous = null, terminal = terminal, effective = true,
            publishId = cur.publishId, terminalMeta = meta,
        )
        // A revert never fails open: the state is reverted in memory even if the write fails (the write is retried by tick).
        st = next
        try { persist.commit(next); persistPending = false } catch (e: Exception) { persistPending = true }
        return ack(cur.appliedGen, cur.mode, PlanAckState.REVERTED, reason, null, cur.phoneInstall, cur.publishId)
    }

    private fun retryPersist() {
        if (!persistPending) return
        val s = st ?: return
        try { persist.commit(s); persistPending = false } catch (e: Exception) { /* still failing: try again at the next tick */ }
    }

    private fun reject(m: CapturePlanMessage, reason: String) = ack(m.gen, m.mode, PlanAckState.REJECTED, reason, null, m.phoneInstall, m.publishId, m.sessionStartMs)

    private fun ack(gen: Long, mode: PlanMode, state: PlanAckState, reason: String?, hash: String?, install: String, publishId: String?, session: Long = lastSessionStartMs) =
        CapturePlanAck(gen = gen, publishId = publishId, mode = mode, effectiveHash = hash, state = state, reason = reason, phoneInstall = install, caps = caps, sessionStartMs = session)

    companion object {
        const val MAX_TRIAL_MS = 3_600_000L
        private const val MAX_TERMINAL = 16
        const val CAP_EXPR = "expr"
        const val CAP_SIG1 = "sig1"
        val CAPS = listOf("plan1", "shadow", "trial")
        /** What a build with the expression runner advertises (X3 switches the glasses to it). */
        val CAPS_EXPR = CAPS + listOf(CAP_EXPR, CAP_SIG1)
    }
}
