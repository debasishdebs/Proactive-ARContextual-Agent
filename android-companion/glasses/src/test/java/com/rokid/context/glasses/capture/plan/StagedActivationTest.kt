package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.PlanState
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.keyframe.GateParams
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.plan.PlanTestKit.trial
import com.rokid.context.glasses.capture.plan.PlanTestKit.withRow
import com.rokid.context.glasses.capture.session.Rig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Staged activation against the REAL classifier and gate (the fake-host cases are in PlanControllerTest). */
class StagedActivationTest {
    private val identity = PlanTestKit.plan()
    private val tuned = PlanTestKit.plan(
        withRow(identity.config, PlanState.REST) { it.copy(capPerMin = 1) }.let { it.copy(glance = it.glance.copy(onsetRadS = 1.0f), gate = it.gate.copy(hamming = 14)) },
    )

    private fun TestScope.rig() = Rig(this, HudSettings(captureMode = CaptureMode.SMART), glance = GlanceParams())

    private fun withAcks(r: Rig, sent: MutableList<String>): PlanController {
        val c = PlanController(MemPersistence(), r.clock, { "bc:1" }, { ContextState.UNKNOWN }, { sent += it.state.name })
        c.host = RigHost(r); r.activePlan = { c.active }
        return c
    }

    /** Continuous gyro of [rate] rad/s for [ms]; [tick] runs once a second like the supervisor. */
    private suspend fun turn(r: Rig, c: PlanController, ms: Long, rate: Float, tick: Boolean = true) {
        var t = 0L
        while (t < ms) {
            r.motion.onGyro(rate, 0f, 0f, r.clock())
            if (tick && t % 1_000L == 0L) c.tick(r.clock())
            delay(20); t += 20
        }
    }

    @Test fun stagedActivationWaitsForQuiet() = runTest {
        val sent = ArrayList<String>()
        val r = rig()
        val c = withAcks(r, sent)
        turn(r, c, 400, 1.5f, tick = false)                 // a bout is running
        assertFalse(r.motion.quiet())
        c.onMessage(trial(1, tuned))
        turn(r, c, 1_000, 0f)                                // settling: the glance is pending, not quiet before ~1.5 s
        assertEquals(listOf("STAGED"), sent)
        assertFalse(r.motion.quiet())
        turn(r, c, 3_000, 0f)
        assertTrue(r.motion.quiet())
        assertEquals(listOf("STAGED", "EFFECTIVE"), sent)
        assertEquals(1.0f, r.motion.config.onsetRadS)
    }

    @Test fun effectiveOnlyAfterActivation() = runTest {
        val sent = ArrayList<String>()
        val r = rig()
        val c = withAcks(r, sent)
        turn(r, c, 400, 1.5f, tick = false)
        c.onMessage(trial(1, tuned))
        assertEquals(listOf("STAGED"), sent)
        assertEquals(GlanceParams(), r.motion.config)
        turn(r, c, 6_000, 0f)
        assertEquals(listOf("STAGED", "EFFECTIVE"), sent)
    }

    @Test fun activationAbortsBoutAfter10s() = runTest {
        val r = rig()
        val c = RigPlan.controller(r)
        turn(r, c, 400, 1.5f, tick = false)
        c.onMessage(trial(1, tuned))
        val staged = r.clock()
        turn(r, c, 9_000, 1.5f)                              // the bout never ends: 9 s staged, nothing applied
        assertEquals(0.6f, r.motion.config.onsetRadS); assertSame(ActiveConfig.SETTINGS, c.active)
        turn(r, c, 2_000, 1.5f)
        assertEquals(1.0f, r.motion.config.onsetRadS)
        assertEquals(tuned.configHash, c.active.configHash)
        assertTrue(r.clock() - staged >= 10_000)
    }

    @Test fun noMixedOldDetectorNewCaps() = runTest {
        val r = rig()
        val c = RigPlan.controller(r)
        turn(r, c, 400, 1.5f, tick = false)
        c.onMessage(trial(1, tuned))
        // while the bout runs: classifier, gate values, rows and the session's glance tunables are ALL the old ones
        fun snapshot() = listOf(r.motion.config, r.activePlan!!().gate, r.activePlan!!().contextCap(ContextState.REST), r.activePlan!!().glance)
        val old = snapshot()
        assertEquals(listOf(GlanceParams(), GateParams(), 2, GlanceParams()), old)
        turn(r, c, 2_000, 1.5f)
        assertEquals(old, snapshot())
        turn(r, c, 12_000, 0f)
        val new = snapshot()
        assertEquals(listOf(r.motion.config, GateParams(hamming = 14), 1, r.motion.config), new)
        assertEquals(1.0f, r.motion.config.onsetRadS)
    }
}
