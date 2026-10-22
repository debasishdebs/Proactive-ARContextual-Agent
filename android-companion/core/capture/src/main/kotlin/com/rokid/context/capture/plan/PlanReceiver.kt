package com.rokid.context.capture.plan

import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.CapturePlanMessage
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

interface PlanPersistence { fun load(): ReceiverState?; fun commit(s: ReceiverState) }

/** [effective] is false while a plan is only staged (S69): it becomes true at [PlanReceiver.activated]. */
data class ReceiverState(
    val appliedGen: Long, val phoneInstall: String, val mode: PlanMode, val planJson: String?, val configHash: String?,
    val trialStartElapsed: Long?, val trialMaxMs: Long?, val bootId: String, val previous: ReceiverState?, val terminal: Map<Long, String>,
    val effective: Boolean = false,
)

/**
 * Pure plan receiver for the glasses (S59). Stale and duplicate gens are answered without re-applying; a REVERTED gen is final; a
 * TRIAL is bounded on the glasses' own elapsed clock and ends on a reboot. Not thread-safe.
 */
class PlanReceiver(private val persist: PlanPersistence, private val elapsed: () -> Long, private val bootId: () -> String) {
    private var st: ReceiverState? = persist.load()

    val current: ReceiverState? get() = st

    fun onMessage(m: CapturePlanMessage): CapturePlanAck {
        val cur = st?.takeIf { it.phoneInstall == m.phoneInstall }   // gens count per phone install
        cur?.terminal?.get(m.gen)?.let { return ack(m.gen, cur.mode, PlanAckState.REVERTED, it, null, m.phoneInstall, m.publishId) }
        if (cur != null && m.gen < cur.appliedGen) return reject(m, "stale")
        if (cur != null && m.gen == cur.appliedGen) {
            return ack(m.gen, cur.mode, if (cur.effective) PlanAckState.EFFECTIVE else PlanAckState.STAGED, null, cur.configHash, m.phoneInstall, m.publishId)
        }
        if (m.mode == PlanMode.TRIAL && (m.trialMaxMs == null || m.trialMaxMs !in 1L..MAX_TRIAL_MS)) return reject(m, "bounds")
        var planJson: String? = null
        if (m.mode != PlanMode.SETTINGS) {
            planJson = m.planJson
            if (planJson == null || m.configHash == null) return reject(m, "schema")
            val bytes = planJson.toByteArray(Charsets.UTF_8).size
            if (bytes > PLAN_MAX_BYTES) return reject(m, "too_big")
            val raw = runCatching { Json.parseToJsonElement(planJson) as? JsonObject }.getOrNull() ?: return reject(m, "schema")
            val plan = PlanJson.decodeStrict(planJson).getOrNull()
            val problems = PlanValidator.problems(raw, plan, bytes)
            if (problems.any { it.startsWith("plan schema ") }) return reject(m, "version")
            if (problems.isNotEmpty()) return reject(m, "bounds")
            if (plan == null) return reject(m, "schema")
            if (plan.configHash != m.configHash || PlanJson.configHash(plan.config) != m.configHash) return reject(m, "schema")
        }
        val next = ReceiverState(
            appliedGen = m.gen, phoneInstall = m.phoneInstall, mode = m.mode, planJson = planJson, configHash = m.configHash.takeIf { planJson != null },
            trialStartElapsed = null, trialMaxMs = m.trialMaxMs.takeIf { m.mode == PlanMode.TRIAL }, bootId = bootId(),
            previous = (cur ?: st)?.copy(previous = null), terminal = cur?.terminal ?: emptyMap(), effective = false,
        )
        persist.commit(next); st = next
        return ack(m.gen, m.mode, PlanAckState.STAGED, null, next.configHash, m.phoneInstall, m.publishId)
    }

    /** The staged plan became the applied one at the safe boundary. A TRIAL starts its clock here, once. */
    fun activated(gen: Long): CapturePlanAck {
        val cur = st
        if (cur == null || cur.appliedGen != gen || gen in cur.terminal) {
            return CapturePlanAck(gen = gen, state = PlanAckState.REJECTED, reason = "stale", phoneInstall = cur?.phoneInstall ?: "", caps = CAPS)
        }
        if (!cur.effective) {
            val next = cur.copy(
                effective = true, bootId = bootId(),
                trialStartElapsed = if (cur.mode == PlanMode.TRIAL) cur.trialStartElapsed ?: elapsed() else null,
            )
            persist.commit(next); st = next
        }
        val s = st!!
        return ack(gen, s.mode, PlanAckState.EFFECTIVE, null, s.configHash, s.phoneInstall, null)
    }

    /** [now] is the glasses' `elapsedRealtime`. Returns the REVERTED ack when the TRIAL ended, else null. */
    @Suppress("UNUSED_PARAMETER")
    fun tick(state: ContextState, now: Long): CapturePlanAck? {
        val cur = st ?: return null
        if (cur.mode != PlanMode.TRIAL || cur.appliedGen in cur.terminal) return null
        if (cur.bootId != bootId()) return revert("auto:reboot")
        val start = cur.trialStartElapsed ?: return null
        val max = cur.trialMaxMs ?: return revert("auto:trial_end")
        if (now < start) return revert("auto:reboot")      // elapsed went backwards: treat as a reboot
        return if (now - start >= max) revert("auto:trial_end") else null
    }

    /** Back to the settings path; the gen is terminal and is never applied again. */
    fun revert(reason: String): CapturePlanAck {
        val cur = st ?: return CapturePlanAck(gen = 0, state = PlanAckState.REJECTED, reason = "stale", caps = CAPS)
        val terminal = (cur.terminal + (cur.appliedGen to reason)).entries.sortedBy { it.key }.takeLast(MAX_TERMINAL).associate { it.key to it.value }
        val next = ReceiverState(
            appliedGen = cur.appliedGen, phoneInstall = cur.phoneInstall, mode = PlanMode.SETTINGS, planJson = null, configHash = null,
            trialStartElapsed = null, trialMaxMs = null, bootId = bootId(), previous = null, terminal = terminal, effective = true,
        )
        persist.commit(next); st = next
        return ack(cur.appliedGen, cur.mode, PlanAckState.REVERTED, reason, null, cur.phoneInstall, null)
    }

    private fun reject(m: CapturePlanMessage, reason: String) = ack(m.gen, m.mode, PlanAckState.REJECTED, reason, null, m.phoneInstall, m.publishId)

    private fun ack(gen: Long, mode: PlanMode, state: PlanAckState, reason: String?, hash: String?, install: String, publishId: String?) =
        CapturePlanAck(gen = gen, publishId = publishId, mode = mode, effectiveHash = hash, state = state, reason = reason, phoneInstall = install, caps = CAPS)

    companion object {
        const val MAX_TRIAL_MS = 3_600_000L
        private const val MAX_TERMINAL = 16
        val CAPS = listOf("plan1", "shadow", "trial")
    }
}
