package com.rokid.context.glasses.capture.plan

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.plan.PlanTestKit.shadow
import com.rokid.context.glasses.capture.plan.PlanTestKit.trial
import com.rokid.context.glasses.capture.session.BudgetTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the heartbeat reports about the plan: the effective state, never the staged or the settings one. */
class PlanStatusTest {
    private var now = 1_000L
    private val mem = MemPersistence()
    private val host = FakeHost()
    private fun ctl() = PlanController(mem, { now }, { "bc:1" }, { ContextState.UNKNOWN }, {}).also { it.host = host }
    private val tuned = PlanTestKit.plan(PlanTestKit.plan().config.let { it.copy(gate = it.gate.copy(hamming = 14)) })

    @Test fun noPlanIsSettingsGenZero() {
        val s = ctl().status()
        assertEquals(StatusView("none", 0, PlanMode.SETTINGS, "none"), StatusView(s.hash8 ?: "none", s.gen, s.mode, s.revert ?: "none"))
    }

    @Test fun aStagedPlanIsNotYetReported() {
        val c = ctl(); host.quiet = false
        c.onMessage(trial(1, tuned))
        assertEquals(PlanMode.SETTINGS, c.status().mode)
        assertNull(c.status().hash8)
        host.quiet = true; c.tick(now)
        assertEquals(PlanMode.TRIAL, c.status().mode)
        assertEquals(tuned.configHash.take(8), c.status().hash8)
        assertEquals(1L, c.status().gen)
    }

    @Test fun shadowIsReportedAndExposesItsConfigButNotAsActive() {
        val c = ctl()
        c.onMessage(shadow(1, tuned))
        assertEquals(PlanMode.SHADOW, c.status().mode)
        assertEquals(ActiveConfig.SETTINGS, c.active)
        assertEquals(14, c.shadow!!.gate.hamming)
        c.revert("auto:old_phone")
        assertNull(c.shadow)
        assertEquals("auto:old_phone", c.status().revert)
    }

    private data class StatusView(val hash: String, val gen: Long, val mode: PlanMode, val revert: String)

    /** Diagnostics label fix: a plan always runs SETTLE, so the heartbeat's `wake` must say so (not the settings wake). */
    @Test fun theHeartbeatWakeIsTheEffectiveOne() {
        val t0 = BudgetTier.T0; val u = ContextState.UNKNOWN
        assertEquals(CameraWake.MOTION, ActiveConfig.SETTINGS.effectiveWake(CameraWake.MOTION, t0, u))
        val plan = ActiveConfig.of(tuned)
        assertEquals(CameraWake.SETTLE, plan.effectiveWake(CameraWake.MOTION, t0, u), "a plan composes SETTLE whatever the settings say")
        assertEquals(CameraWake.SETTLE, ActiveConfig.SETTINGS.effectiveWake(CameraWake.MOTION, t0, ContextState.REST), "so does a forceSettle context row")
        for (w in CameraWake.entries) for (tier in BudgetTier.entries) for (cfg in listOf(ActiveConfig.SETTINGS, plan)) {
            assertEquals(cfg.profile(w, tier, u).wake, cfg.effectiveWake(w, tier, u), "the label is the composed profile's wake")
        }
    }
}
