package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.PlanState
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextCapturePolicy
import com.rokid.context.core.model.context.ContextPolicies
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.MotionContextMessage
import com.rokid.context.glasses.capture.golden.DecisionGoldenRecorder
import com.rokid.context.glasses.capture.golden.GoldenScript
import com.rokid.context.glasses.capture.golden.GoldenScripts
import com.rokid.context.glasses.capture.golden.Turn
import com.rokid.context.glasses.capture.plan.PlanTestKit.shadow
import com.rokid.context.glasses.capture.plan.PlanTestKit.trial
import com.rokid.context.glasses.capture.plan.PlanTestKit.withRow
import com.rokid.context.glasses.capture.session.BudgetTier
import com.rokid.context.glasses.capture.session.CaptureProfile
import com.rokid.context.glasses.capture.session.ContextGate
import com.rokid.context.glasses.capture.session.HeatReading
import com.rokid.context.glasses.capture.session.Rig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Slice 3a Task 11: what an applied plan does (and, for the identity plan, does NOT do) to the real decisions. */
class PlanLiveApplyTest {
    private fun golden(name: String) = javaClass.getResourceAsStream("/golden/decisions-$name.txt")!!.bufferedReader().use { it.readText() }

    private fun record(s: GoldenScript, planFor: ((Rig) -> PlanController)?): String {
        var text = ""
        runTest(timeout = 5.minutes) { text = DecisionGoldenRecorder.record(this, s, planFor) }
        return text
    }

    private val identity = PlanTestKit.plan()

    @Test fun settingsPathEqualsGoldens() {
        for (s in GoldenScripts.all) assertEquals(golden(s.name), record(s) { r -> RigPlan.controller(r) }, "settings path via the plan machinery: ${s.name}")
    }

    @Test fun identityPlanLiveEqualsGoldens() {
        // Every script: REST 120 s and FLIGHT 300 s rows, WorkRest, T2 budget and heat-SLOW included. The receiver refuses LIVE (CORE), so
        // the live apply path is the TRIAL one; the config the session reads is the plan's, not the settings default.
        for (s in GoldenScripts.all) {
            var applied: ActiveConfig? = null
            val text = record(s) { r -> RigPlan.controller(r) { it.onMessage(trial(1, identity, 3_600_000)); applied = it.active } }
            assertEquals(identity.configHash, applied!!.configHash, "the plan really was applied: ${s.name}")
            assertEquals(golden(s.name), text, "identity plan: ${s.name}")
        }
    }

    @Test fun shadowLeavesCaptureInputsUntouched() {
        // restrictive rows in the shadow plan: if any of it reached capture the REST and FLIGHT goldens would change
        val harsh = PlanTestKit.plan(
            withRow(withRow(identity.config, PlanState.REST) { it.copy(capPerMin = 1, timerPhotoGapsMs = it.timerPhotoGapsMs.copy(settledMs = 300_000, quietMs = 300_000, walkMs = 300_000, stillCapMs = 300_000)) },
                PlanState.FLIGHT) { it.copy(capPerMin = 1) }.let { it.copy(glance = it.glance.copy(onsetRadS = 1.0f, settleMs = 3_000)) },
        )
        for (s in listOf(GoldenScripts.restStill, GoldenScripts.flightSettings, GoldenScripts.unknownWalk)) {
            var active: ActiveConfig? = null
            val text = record(s) { r -> RigPlan.controller(r) { it.onMessage(shadow(1, harsh)); active = it.active } }
            assertSame(ActiveConfig.SETTINGS, active)
            assertEquals(golden(s.name), text, "shadow: ${s.name}")
        }
    }

    // ---- scripts for the plan effects ----

    private fun script(name: String, ctx: ContextState, wake: CameraWake = CameraWake.SETTLE, build: (com.rokid.context.glasses.capture.golden.ScriptPlan, Long) -> Unit) =
        GoldenScript(name, 5L, 6 * 60_000L, HudSettings(captureMode = CaptureMode.SMART, cameraWake = wake), BudgetTier.T0) { _, p, d ->
            p.ctx += 0L to ctx
            build(p, d)
        }

    private fun times(text: String, kind: String) = text.lines().filter { it.split(' ').getOrNull(1) == kind }.map { it.substringBefore(' ').toLong() }
    private fun maxInAMinute(ts: List<Long>) = ts.maxOfOrNull { t -> ts.count { it >= t && it < t + 60_000 } } ?: 0

    /** A glance (turn onto a new scene) every [gap] ms. */
    private fun glanceEvery(p: com.rokid.context.glasses.capture.golden.ScriptPlan, d: Long, gap: Long, rate: Float = 1.5f) {
        var t = 5_000L; var scene = 100
        while (t < d) { p.turns += Turn(t, 600, rate, scene++); t += gap }
    }

    @Test fun liveCapOneLimitsKeeps() {
        val s = script("cap1", ContextState.REST) { p, d -> glanceEvery(p, d, 9_000) }
        val base = record(s) { r -> RigPlan.controller(r) }
        val capped = PlanTestKit.plan(withRow(identity.config, PlanState.REST) { it.copy(capPerMin = 1) })
        val live = record(s) { r -> RigPlan.controller(r) { it.onMessage(trial(1, capped, 3_600_000)) } }
        assertTrue(maxInAMinute(times(base, "keep")) >= 2, "settings REST cap 2 keeps two in a minute: ${times(base, "keep")}")
        assertEquals(1, maxInAMinute(times(live, "keep")), "plan REST cap 1: ${times(live, "keep")}")
    }

    @Test fun onsetFromPlanChangesBoutStart() {
        val s = script("onset", ContextState.UNKNOWN) { p, d -> glanceEvery(p, d, 30_000, rate = 0.8f) }
        val base = record(s) { r -> RigPlan.controller(r) }
        val high = PlanTestKit.plan(identity.config.let { it.copy(glance = it.glance.copy(onsetRadS = 1.0f)) })
        val live = record(s) { r -> RigPlan.controller(r) { it.onMessage(trial(1, high, 3_600_000)) } }
        assertTrue(times(base, "dwell").isNotEmpty(), "0.8 rad/s is a bout at the default onset 0.6")
        assertTrue(times(live, "dwell").isEmpty(), "and none at onset 1.0: ${times(live, "dwell")}")
    }

    // ---- composition (S68) ----

    private val rowPlan = PlanTestKit.plan(
        withRow(withRow(identity.config, PlanState.VEHICLE_PASSENGER) { it.copy(capPerMin = 1, timerPhotoGapsMs = it.timerPhotoGapsMs.copy(settledMs = 45_000)) }, PlanState.REST) { it.copy(capPerMin = 1) },
    )
    private val unchangedRow = ContextPolicies.DEFAULT.getValue(ContextState.UNKNOWN)

    private fun applied(a: ActiveConfig = ActiveConfig.of(rowPlan)) = a
    private fun msg(s: ContextState) = MotionContextMessage(state = s, atMs = 1, reasons = emptyList())

    @Test fun vetoedVehicleGetsFullRateRow() {
        val a = ActiveConfig.of(rowPlan)
        assertEquals(ContextPolicies.DEFAULT.getValue(ContextState.WALKING), a.table.getValue(ContextState.WALKING), "the locked WALKING row is in every applied table")
        assertEquals(unchangedRow, a.table.getValue(ContextState.UNKNOWN))
        val g = ContextGate(); g.onMessage(msg(ContextState.VEHICLE_PASSENGER), 0)
        val st = g.effective(10_000, MotionState.WALKING, null, emptyList())      // the local walking veto
        assertEquals(ContextState.UNKNOWN, st)
        assertEquals(unchangedRow, a.policyFor(st)); assertNull(a.contextCap(st))
        assertEquals(CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0).withContext(unchangedRow), a.profile(CameraWake.SETTLE, BudgetTier.T0, st))
    }

    @Test fun speedLiftsVeto() {
        // The glasses' own veto is unconditional (ContextGateTest pins "whatever the reasons"); the SPEED exception lives in the phone hub,
        // which sends the vehicle state while the glasses are not walking (a rider). That message gets the plan's row, not the default.
        val a = ActiveConfig.of(rowPlan)
        val g = ContextGate(); g.onMessage(msg(ContextState.VEHICLE_PASSENGER).copy(reasons = listOf("SPEED")), 0)
        val st = g.effective(10_000, MotionState.HEAD_MOVING, lastLocalWalkAtMs = null, sourcesOff = emptyList())
        assertEquals(ContextState.VEHICLE_PASSENGER, st)
        assertEquals(1, a.contextCap(st)); assertEquals(45_000L, a.policyFor(st).minSettledCheckMs)
        assertNotEquals(ActiveConfig.SETTINGS.contextCap(st), a.contextCap(st))
    }

    @Test fun ctxUnboundAppliesUnknown() {
        val unbound = ActiveConfig.of(PlanTestKit.plan(rowPlan.config.copy(ctxBound = false)))
        for (s in ContextState.entries) { assertEquals(unchangedRow, unbound.policyFor(s), "$s"); assertNull(unbound.contextCap(s)) }
        assertEquals(1, ActiveConfig.of(rowPlan).contextCap(ContextState.REST))
    }

    @Test fun rowGapsComposeAsMinimaOverTheSettingsProfile() {
        val a = ActiveConfig.of(rowPlan)
        val p = a.profile(CameraWake.MOTION, BudgetTier.T0, ContextState.VEHICLE_PASSENGER)
        assertEquals(CameraWake.MOTION, p.wake)                       // forceSettle false: the settings wake stays
        assertEquals(45_000L, p.settledCheckMs)                       // plan 45 s over the settings 5 s
        val low = a.profile(CameraWake.MOTION, BudgetTier.T2, ContextState.VEHICLE_PASSENGER)
        assertEquals(45_000L, low.settledCheckMs)                     // T2's own 20 s is below the row
        assertEquals(CaptureProfile.of(CameraWake.MOTION, BudgetTier.T2).quietCheckMs, low.quietCheckMs)   // a row gap of 0 inherits
    }

    @Test fun settingsEditAfterPublishDoesNotChangeLivePlan() {
        var activeAfter: ActiveConfig? = null
        runTest {
            val r = Rig(this, HudSettings(captureMode = CaptureMode.SMART), glance = com.rokid.context.glasses.capture.motion.GlanceParams())
            val c = RigPlan.controller(r) { it.onMessage(trial(1, rowPlan, 3_600_000)) }
            val before = c.active
            c.onPhoneSettings(planAware = true)                       // a settings push after publish
            r.settings = r.settings.copy(cameraWake = CameraWake.SETTLE, batteryTargetHours = 4, workMinutes = 30)
            activeAfter = c.active
            assertSame(before, activeAfter)
            assertEquals(1, c.active.contextCap(ContextState.REST))
            assertEquals(1, c.active.contextCap(ContextState.VEHICLE_PASSENGER))
        }
    }

    // ---- invariants beat the plan ----

    private fun blockedUnderPlan(setup: (Rig) -> Unit, reason: String) = runTest {
        val r = Rig(this, HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.MOTION), glance = com.rokid.context.glasses.capture.motion.GlanceParams())
        RigPlan.controller(r) { it.onMessage(trial(1, rowPlan, 3_600_000)) }
        setup(r)
        r.startSensors(); r.session.start()
        delay(90_000)
        assertEquals(reason, r.session.status.value.reason)
        assertEquals(0, r.camera.opens, "no camera open while $reason")
        r.session.stop("end")
    }

    @Test fun heatStopBeatsPlan() = blockedUnderPlan({ it.heatReading = HeatReading(0, Float.NaN, 45f) }, "heat")
    @Test fun userPauseBeatsPlan() = blockedUnderPlan({ it.paused = true }, "paused")
}
