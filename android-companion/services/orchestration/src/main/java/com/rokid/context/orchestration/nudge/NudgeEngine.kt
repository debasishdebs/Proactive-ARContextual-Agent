package com.rokid.context.orchestration.nudge

import com.rokid.context.core.log.PcLog
import androidx.room.withTransaction
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.core.model.DeliveryRoute
import com.rokid.context.core.model.DeviceCapabilities
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
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
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
    /** A pause is active (or Battery Saver): no trigger, the summarizer pass included, reads anything or calls the model. */
    data object Paused : NudgeRun
    /** `trigger.signal`: the condition does not newly hold. */
    data object NoSignal : NudgeRun
    /** The trigger started more runs this hour than its `maxPerHour`; no model call. */
    data object RateLimited : NudgeRun
    /** A queued schedule occurrence of a publication that is no longer the live plan: dropped (counted as NUDGE_DROPPED / STALE_PUBLICATION), nothing read or called. */
    data object StalePublication : NudgeRun
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
    suspend fun runOnce(trigger: String = NodeTypes.TRIGGER_SUMMARIZER, builtInOnly: Boolean = false, scope: String? = null): NudgeRun = NudgeRunLock.mutex.withLock { runLocked(trigger, builtInOnly, scope) }

    private suspend fun runLocked(trigger: String, builtInOnly: Boolean, scope: String?): NudgeRun {
        val now = clock()
        val lookup = if (builtInOnly) Lookup(null, failed = true) else lookupPlan() // builtInOnly: a lookup failed when the run was queued; no later recovery may change what it runs
        val plan = lookup.plan
        // a plan lookup that failed runs the built-in flow, whatever started the run (the fallback must not depend on the trigger)
        if (!lookup.failed && trigger != (plan?.trigger ?: NodeTypes.TRIGGER_SUMMARIZER)) return NudgeRun.NotThisTrigger
        // a queued occurrence carries the publication it was queued for: a replacement publication's plan must not run it
        if (scope != null && !lookup.failed && (plan?.scope ?: "") != scope) {
            memory.traces.upsert(SummaryTraceEntity("nudge_drop_$now", "NUDGE_DROPPED", now, STALE_PUBLICATION))
            return NudgeRun.StalePublication
        }
        val h = hud() ?: return NudgeRun.NoHud
        if (triggers.paused()) return NudgeRun.Paused // every trigger, the summarizer pass included: a pause (or Battery Saver) stops the model call, before anything is read
        // the edge state is updated even when a gate below drops the run
        plan?.signal?.let { sig -> if (!SignalGate(triggers.meta, plan.scope, triggers.signals).fires(sig, now)) return NudgeRun.NoSignal }
        gates.precheck(h, now).takeIf { it != Gate.PASS }?.let { return NudgeRun.Dropped(it) }
        val photo = if (plan != null && plan.imageQuery && trigger in PHOTO_TRIGGERS) triggerPhoto(now) else null
        val built = buildInputs(now, plan, photo)
        // the query photo is a source of the nudge: Forget of it (while the model runs) drops the nudge, and it is stored as a dependency
        val prepared = if (photo == null) built else built.copy(fenceIds = built.fenceIds + (PHOTO_CITE_PREFIX + photo.frameId))
        if (prepared.inputIds.isEmpty()) return NudgeRun.NoMemories
        if (plan != null && !triggers.limiter.admit(trigger, plan.maxPerHour, plan.scope)) {
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

    private class Lookup(val plan: FlowPlan?, val failed: Boolean = false)

    /** The published Nudge plan (null plan = the built-in flow); a lookup that throws is the built-in flow too, flagged so any trigger may run it. */
    private suspend fun lookupPlan(): Lookup = try { Lookup(flows.plan(FlowPlan.Kind.NUDGE)?.takeIf { it != FlowPlan.BUILT_IN_NUDGE }) } catch (c: CancellationException) { throw c } catch (e: Exception) {
        PcLog.i(TAG, "nudge pipeline lookup failed (${e.javaClass.simpleName}); built-in flow"); Lookup(null, failed = true)
    }

    /** The trigger's photo, only while it is not forgotten (it was read just now; the commit fence checks it again). */
    private suspend fun triggerPhoto(now: Long): TriggerPhoto? = try {
        triggers.images.photo(now)?.takeIf { com.rokid.context.memory.forget.ForgetFence.allLive(memory.db, setOf(PHOTO_CITE_PREFIX + it.frameId)) }
    } catch (c: CancellationException) { throw c } catch (e: Exception) {
        PcLog.i(TAG, "trigger image failed (${e.javaClass.simpleName})"); null
    }

    /** Where the nudge shows. `both` (and the built-in flow) is today's verbosity routing; `phone` stays in the feed; `glasses` is the HUD overlay, or the phone with a recorded reason when they are not connected. */
    private fun routeOf(plan: FlowPlan?, verbosity: Verbosity): NudgeRoute {
        val caps = triggers.capabilities()
        val pd = caps.phoneDisplay; val gd = caps.glassesDisplay
        val none = if (pd) DeviceCapabilities.GLASSES_DISPLAY_OFF else DeviceCapabilities.NO_DISPLAY
        val up = triggers.reach() == GlassesReach.UP
        // no display anywhere: still one stored row on the phone, and the reason is always recorded (whatever the verbosity or target)
        if (!pd && !gd) return NudgeRoute("phone", DeviceCapabilities.NO_DISPLAY)
        val base = shownOn(verbosity)
        return when (plan?.target) {
            "phone" -> when {
                pd -> NudgeRoute("phone")
                gd && up && base != "phone" -> NudgeRoute(base, DeviceCapabilities.PHONE_DISPLAY_OFF) // the verbosity ceiling holds: SILENT is never raised
                else -> NudgeRoute("phone", DeviceCapabilities.PHONE_DISPLAY_OFF)
            }
            "glasses" -> when {
                !gd -> NudgeRoute("phone", none)
                up && base == "phone" -> NudgeRoute("phone", VERBOSITY_SILENT) // the verbosity ceiling holds: SILENT is never raised to the HUD
                up -> NudgeRoute("toast")
                else -> NudgeRoute("phone", DeliveryRoute.GLASSES_NOT_CONNECTED)
            }
            else -> {
                // `both` and the built-in flow: the user's verbosity picks the surface (never raised); a HUD choice needs the glasses display
                when {
                    base == "phone" -> NudgeRoute(base)
                    !gd -> NudgeRoute("phone", none)
                    !pd -> NudgeRoute(base, DeviceCapabilities.PHONE_DISPLAY_OFF)
                    else -> NudgeRoute(base)
                }
            }
        }
    }

    private class NudgeRoute(val shownOn: String, val fallback: String? = null)

    /** The published Nudge pipeline's inputs; any problem with it (retrieval) runs the built-in inputs instead. The gates around it are the engine's own. */
    private suspend fun buildInputs(now: Long, plan: FlowPlan?, photo: TriggerPhoto?): NudgeInputs.Prepared {
        if (plan == null) return inputs.build(now)
        return try { inputs.build(now, photoVector = photo?.vector, plan = plan, photoSpace = photo?.spaceId) } catch (c: CancellationException) { throw c } catch (e: Exception) {
            PcLog.i(TAG, "nudge pipeline failed (${e.javaClass.simpleName}); built-in flow"); inputs.build(now)
        }
    }

    companion object {
        private const val TAG = "NudgeEngine"
        private const val MAX_TEXT = 200
        /** The triggers that come with a photo: a Moment landing, a Scene closing. */
        private val PHOTO_TRIGGERS = setOf(NodeTypes.TRIGGER_MOMENT, NodeTypes.TRIGGER_SCENE)
        private const val MAX_WHY = 300
        const val STALE_PUBLICATION = "STALE_PUBLICATION"
        /** The recorded reason when an explicit `glasses` delivery stays on the phone because verbosity is SILENT. */
        const val VERBOSITY_SILENT = "verbosity_silent"

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
    /** v0.9.8 slice D: the user's device capabilities (a switched-off display is not a delivery surface). */
    val capabilities: () -> DeviceCapabilities = { DeliveryEnvironment.caps() },
) {
    val limiter = TriggerRateLimiter(meta, clock)
}
