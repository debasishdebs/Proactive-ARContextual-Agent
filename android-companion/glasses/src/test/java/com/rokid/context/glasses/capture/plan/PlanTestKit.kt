package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.CapturePlan
import com.rokid.context.capture.plan.PLAN_SCHEMA
import com.rokid.context.capture.plan.PlanConfig
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanMeta
import com.rokid.context.capture.plan.PlanPersistence
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanRow
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.capture.plan.ReceiverState
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.CapturePlanMessage
import com.rokid.context.core.model.PlanMode

object PlanTestKit {
    fun plan(config: PlanConfig = PlanResolver.identity(), name: String = "t") = CapturePlan(PLAN_SCHEMA, PlanJson.configHash(config), config, PlanMeta(name, 1))
    fun withRow(c: PlanConfig, s: PlanState, f: (PlanRow) -> PlanRow) = c.copy(policy = c.policy + (s to f(c.policy.getValue(s))))
    fun msg(gen: Long, mode: PlanMode, plan: CapturePlan? = plan(), trialMaxMs: Long? = null, install: String = "p1") = CapturePlanMessage(
        gen = gen, mode = mode, configHash = plan?.configHash, planJson = plan?.let { PlanJson.encode(it) }, trialMaxMs = trialMaxMs, phoneInstall = install, atMs = 5,
    )
    fun trial(gen: Long, plan: CapturePlan = plan(), max: Long = 60_000L) = msg(gen, PlanMode.TRIAL, plan, max)
    fun shadow(gen: Long, plan: CapturePlan = plan()) = msg(gen, PlanMode.SHADOW, plan)
    fun settings(gen: Long) = msg(gen, PlanMode.SETTINGS, null)
}

open class MemPersistence(var state: ReceiverState? = null) : PlanPersistence {
    var commits = 0
    var failNextCommit = false
    override fun load() = state
    override fun commit(s: ReceiverState) {
        if (failNextCommit) { failNextCommit = false; throw java.io.IOException("disk full") }
        state = s; commits++
    }
}

class FakeHost(var quiet: Boolean = true) : PlanHost {
    val installs = ArrayList<ActiveConfig>()
    var aborts = 0
    override fun quiet() = quiet
    override fun abortBout() { aborts++; quiet = true }
    override fun install(cfg: ActiveConfig) { installs += cfg }
}

class Acks { val sent = ArrayList<CapturePlanAck>(); fun states() = sent.map { it.state.name + (it.reason?.let { r -> ":$r" } ?: "") } }

/** The PlanHost CaptureService builds, over a test [com.rokid.context.glasses.capture.session.Rig]. */
class RigHost(private val r: com.rokid.context.glasses.capture.session.Rig) : PlanHost {
    override fun quiet() = r.motion.quiet() && !r.session.episodeOpen()
    override fun abortBout() = r.motion.abortBout()
    override fun install(cfg: ActiveConfig) = r.motion.setConfig(cfg.glance)
}

object RigPlan {
    /** A controller on [r] (virtual clock, in-memory persistence); [apply] sends the plan messages before the session starts. */
    fun controller(r: com.rokid.context.glasses.capture.session.Rig, mem: MemPersistence = MemPersistence(), apply: (PlanController) -> Unit = {}): PlanController {
        val c = PlanController(mem, r.clock, { "bc:1" }, { com.rokid.context.core.model.context.ContextState.UNKNOWN }, {})
        c.host = RigHost(r)
        r.activePlan = { c.active }
        apply(c)
        return c
    }
}
