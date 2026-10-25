package com.rokid.context.orchestration.nudge

import com.rokid.context.core.log.PcLog
import androidx.room.withTransaction
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.contract.NudgeOutput
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.forget.ForgetFence
import com.rokid.context.orchestration.AskSources
import com.rokid.context.orchestration.AskText
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface NudgeRun {
    data object NoHud : NudgeRun
    data object NoMemories : NudgeRun
    data object NotNeeded : NudgeRun
    data object Failed : NudgeRun
    /** A memory in the prompt or cited was forgotten while the model ran: nothing is written. */
    data object Forgotten : NudgeRun
    data class Dropped(val gate: Gate) : NudgeRun
    data class Shown(val nudgeId: String) : NudgeRun
}

/**
 * R-61: one nudge decision. No HUD settings → skip (fail-closed); OFF/quiet hours/cap → no model call; no recent
 * memories → no model call; else the NUDGE role (sourceIds ⊆ the memory ids) and, when it nudges and the gates
 * pass, one `nudge` row, then its proposed action to [intake] with origin NUDGE. A model failure is traced, not thrown.
 */
class NudgeEngine(
    private val inputs: NudgeInputs,
    private val runner: RoleRunner,
    private val gates: NudgeGates,
    private val memory: MemoryGraph,
    private val intake: ProposalIntake,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hud: () -> NudgeHud? = { NudgeEnvironment.hud?.invoke() },
) {
    /** Serialized with every other run through [NudgeRunLock] (the daily cap is read-then-write). */
    suspend fun runOnce(): NudgeRun = NudgeRunLock.mutex.withLock { runLocked() }

    private suspend fun runLocked(): NudgeRun {
        val now = clock()
        val h = hud() ?: return NudgeRun.NoHud
        gates.precheck(h, now).takeIf { it != Gate.PASS }?.let { return NudgeRun.Dropped(it) }
        val prepared = inputs.build(now)
        if (prepared.inputIds.isEmpty()) return NudgeRun.NoMemories
        val out = try {
            runner.run(RoleCall(PromptKey.NUDGE, prepared.vars, inputIds = prepared.inputIds, callClass = com.rokid.context.model.CallClass.NUDGE)).output as NudgeOutput
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            PcLog.i(TAG, "nudge failed: ${SafeLog.describe(e)}")
            memory.traces.upsert(SummaryTraceEntity("nudge_run_$now", "NUDGE_FAILED", now, SafeLog.describe(e)))
            return NudgeRun.Failed
        }
        val text = AskText.collapse(out.text).take(MAX_TEXT)
        if (!out.shouldNudge || text.isEmpty()) return NudgeRun.NotNeeded
        val gate = gates.check(text, h, now)
        if (gate != Gate.PASS) { recordDropped(memory, gate, now); return NudgeRun.Dropped(gate) }
        val id = "nudge_${UUID.randomUUID()}"
        // Forget fence (integration carry 1): a memory in the prompt or cited forgotten while the model ran means the
        // text may hold forgotten content, so the nudge is dropped (no row, no proposal). Checked in the insert transaction.
        val live = memory.db.withTransaction {
            if (!ForgetFence.allLive(memory.db, prepared.inputIds + out.sourceIds)) return@withTransaction false
            memory.nudges.addIgnore(
                NudgeEntity(
                    id = id, ts = now, text = text, why = AskText.collapse(out.why).take(MAX_WHY), sourceIdsJson = AskSources.encode(out.sourceIds),
                    priority = out.priority, hash = NudgeGates.hash(text), shownOn = shownOn(h.verbosity), redacted = false,
                ),
            )
            true
        }
        if (!live) return NudgeRun.Forgotten
        out.proposedAction?.let { intake.onProposed(ProposalOrigin.NUDGE, id, it, now) }
        return NudgeRun.Shown(id)
    }

    companion object {
        private const val TAG = "NudgeEngine"
        private const val MAX_TEXT = 200
        private const val MAX_WHY = 300

        /** A "nudge" decision the notify throttle held back: recorded with its reason (Diagnostics), no row, no text. */
        suspend fun recordDropped(memory: MemoryGraph, gate: Gate, now: Long) {
            memory.traces.upsert(SummaryTraceEntity("nudge_drop_$now", "NUDGE_DROPPED", now, gate.name))
        }

        fun shownOn(v: Verbosity): String = when (v) {
            Verbosity.SILENT -> "phone"
            Verbosity.MEDIUM -> "glance"
            Verbosity.MAX -> "toast"
        }
    }
}
