package com.rokid.context.orchestration.nudge

import com.rokid.context.core.log.PcLog
import androidx.room.withTransaction
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.core.model.DeliveryRoute
import com.rokid.context.core.model.GlassesReach
import com.rokid.context.orchestration.DeliveryEnvironment
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
import com.rokid.context.orchestration.FlowPlans
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
    /** v0.9.8 slice C: the run was started by a trigger the published Nudge flow does not use. Nothing was read or called. */
    data object NotThisTrigger : NudgeRun
    /** A pause is active (or Battery Saver): a trigger other than the summarizer pass does nothing. */
    data object Paused : NudgeRun
    /** `trigger.signal`: the condition does not newly hold. */
    data object NoSignal : NudgeRun
    /** The trigger started more runs this hour than its `maxPerHour`; no model call. */
    data object RateLimited : NudgeRun
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
    /** The published Nudge pipeline (v0.9.8 slice B); none = the built-in plan. */
    private val flows: FlowPlans = FlowPlans.None,
    /** Slice C: the state and seams of the non-summarizer triggers; the defaults give no signal, no photo, no pause and the glasses "not connected". */
    private val triggers: TriggerSeams = TriggerSeams(),
) {
    /**
     * Serialized with every other run through [NudgeRunLock] (the daily cap is read-then-write). [trigger] is the node type that started the run: a published Nudge flow runs
     * only on its own root, so any other trigger returns [NudgeRun.NotThisTrigger]; the built-in flow's root is the summarizer pass.
     */
    suspend fun runOnce(trigger: String = NodeTypes.TRIGGER_SUMMARIZER): NudgeRun = NudgeRunLock.mutex.withLock { runLocked(trigger) }

    private suspend fun runLocked(trigger: String): NudgeRun {
        val now = clock()
        val plan = lookupPlan()
        if (trigger != (plan?.trigger ?: NodeTypes.TRIGGER_SUMMARIZER)) return NudgeRun.NotThisTrigger
        val h = hud() ?: return NudgeRun.NoHud
        val event = trigger != NodeTypes.TRIGGER_SUMMARIZER
        if (event && triggers.paused()) return NudgeRun.Paused
        // the edge state is updated even when a gate below drops the run
        plan?.signal?.let { if (!SignalGate(triggers.meta, triggers.signals).fires(it, now)) return NudgeRun.NoSignal }
        gates.precheck(h, now).takeIf { it != Gate.PASS }?.let { return NudgeRun.Dropped(it) }
        val prepared = buildInputs(now, plan, if (plan != null && plan.imageQuery && trigger in PHOTO_TRIGGERS) imageVector(now) else null)
        if (prepared.inputIds.isEmpty()) return NudgeRun.NoMemories
        if (plan != null && !triggers.limiter.admit(trigger, plan.maxPerHour)) {
            memory.traces.upsert(SummaryTraceEntity("nudge_drop_$now", "NUDGE_DROPPED", now, "TRIGGER_RATE"))
            return NudgeRun.RateLimited
        }
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
        val route = routeOf(plan, h.verbosity)
        // Forget fence (integration carry 1): a memory in the prompt or cited forgotten while the model ran means the
        // text may hold forgotten content, so the nudge is dropped (no row, no proposal). Checked in the insert transaction.
        val live = memory.db.withTransaction {
            if (!ForgetFence.allLive(memory.db, prepared.inputIds + prepared.fenceIds + out.sourceIds)) return@withTransaction false
            memory.nudges.addIgnore(
                NudgeEntity(
                    id = id, ts = now, text = text, why = AskText.collapse(out.why).take(MAX_WHY), sourceIdsJson = AskSources.encodeWithDeps(out.sourceIds, prepared.fenceIds),
                    priority = out.priority, hash = NudgeGates.hash(text), shownOn = route.shownOn, redacted = false,
                ),
            )
            true
        }
        if (!live) return NudgeRun.Forgotten
        route.fallback?.let { memory.traces.upsert(SummaryTraceEntity("nudge_deliver_$id", "NUDGE_DELIVERY_FALLBACK", now, it)) } // never silent: the reason token, no text
        out.proposedAction?.let { intake.onProposed(ProposalOrigin.NUDGE, id, it, now) }
        return NudgeRun.Shown(id)
    }

    /** The published Nudge plan (null = the built-in flow); a lookup that throws is the built-in flow. */
    private suspend fun lookupPlan(): FlowPlan? = try { flows.plan(FlowPlan.Kind.NUDGE)?.takeIf { it != FlowPlan.BUILT_IN_NUDGE } } catch (c: CancellationException) { throw c } catch (e: Exception) {
        PcLog.i(TAG, "nudge pipeline lookup failed (${e.javaClass.simpleName}); built-in flow"); null
    }

    private suspend fun imageVector(now: Long): FloatArray? = try { triggers.images.vector(now) } catch (c: CancellationException) { throw c } catch (e: Exception) {
        PcLog.i(TAG, "trigger image failed (${e.javaClass.simpleName})"); null
    }

    /** Where the nudge shows. `both` (and the built-in flow) is today's verbosity routing; `phone` stays in the feed; `glasses` is the HUD overlay, or the phone with a recorded reason when they are not connected. */
    private fun routeOf(plan: FlowPlan?, verbosity: Verbosity): NudgeRoute = when (plan?.target) {
        "phone" -> NudgeRoute("phone")
        "glasses" -> if (triggers.reach() == GlassesReach.UP) NudgeRoute("toast") else NudgeRoute("phone", DeliveryRoute.GLASSES_NOT_CONNECTED)
        else -> NudgeRoute(shownOn(verbosity))
    }

    private class NudgeRoute(val shownOn: String, val fallback: String? = null)

    /** The published Nudge pipeline's inputs; any problem with it (retrieval) runs the built-in inputs instead. The gates around it are the engine's own. */
    private suspend fun buildInputs(now: Long, plan: FlowPlan?, photoVector: FloatArray?): NudgeInputs.Prepared {
        if (plan == null) return inputs.build(now)
        return try { inputs.build(now, photoVector = photoVector, plan = plan) } catch (c: CancellationException) { throw c } catch (e: Exception) {
            PcLog.i(TAG, "nudge pipeline failed (${e.javaClass.simpleName}); built-in flow"); inputs.build(now)
        }
    }

    companion object {
        private const val TAG = "NudgeEngine"
        private const val MAX_TEXT = 200
        /** The triggers that come with a photo: a Moment landing, a Scene closing. */
        private val PHOTO_TRIGGERS = setOf(NodeTypes.TRIGGER_MOMENT, NodeTypes.TRIGGER_SCENE)
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

/**
 * The seams of the slice C triggers. [meta] holds the rate-limit stamps and the signal's last state ([limiter] shares it); [signals], [images], [paused] and [reach] come from the app
 * (Scenes, the newest photo's embedding, a pause or Battery Saver, the glasses link).
 */
class TriggerSeams(
    val meta: TriggerMeta = MemoryTriggerMeta(),
    val signals: SignalValues = SignalValues { _, _, _ -> emptyList() },
    val images: TriggerImages = TriggerImages { null },
    val paused: suspend () -> Boolean = { false },
    val reach: () -> GlassesReach = { DeliveryEnvironment.reach() },
    clock: () -> Long = System::currentTimeMillis,
) {
    val limiter = TriggerRateLimiter(meta, clock)
}
