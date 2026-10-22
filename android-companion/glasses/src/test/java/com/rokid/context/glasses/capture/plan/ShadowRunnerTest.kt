package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.PlanState
import com.rokid.context.capture.plan.TimerPhotoGaps
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.golden.DecisionGoldenRecorder
import com.rokid.context.glasses.capture.golden.GoldenScripts
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.motion.MotionClassifier
import com.rokid.context.glasses.capture.plan.PlanTestKit.shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ShadowRunnerTest {
    private var t = 0L
    private fun firstDiff(a: String, b: String): String {
        val x = a.lines(); val y = b.lines()
        val i = (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] } ?: return "same prefix, sizes ${x.size} vs ${y.size}"
        return "line $i: plain='${x[i]}' shadow='${y[i]}' (prev '${x.getOrNull(i - 1)}')"
    }
    private val clock = { t }
    private fun cfg(g: GlanceParams) = ActiveConfig.SETTINGS.copy(glance = g)

    /** 50 Hz gyro for [ms] at [mag] rad/s about one axis, then silence (the classifier and the tap both see every sample). */
    private fun gyro(c: MotionClassifier, ms: Long, mag: Float) {
        val end = t + ms
        while (t < end) { c.onGyro(mag, 0f, 0f, t); c.onLinearAccel(0f, 0f, 0f, t); t += 20 }
    }

    /** A real glance: still, a 0.5 s turn of about 0.75 rad, then 3 s of stillness (settle 1.5 s). */
    private fun glance(c: MotionClassifier) { gyro(c, 2_000, 0.01f); gyro(c, 500, 1.5f); gyro(c, 3_000, 0.01f) }

    @Test fun countsWouldOpen() {
        val c = MotionClassifier(clock)
        val plan = cfg(GlanceParams())
        val s = ShadowRunner({ plan }, { ContextState.UNKNOWN })
        c.addTap(s)
        glance(c)
        assertEquals(ShadowCounts(open = 1, skip = 0), s.take())
        assertEquals(ShadowCounts(0, 0), s.take(), "take resets")
    }

    @Test fun countsWouldSkipWhenThePlanNoveltyRuleWouldRefuse() {
        val c = MotionClassifier(clock)
        val plan = cfg(GlanceParams(noveltyMinRad = 1.5f, bigTurnRad = 2f))
        val s = ShadowRunner({ plan }, { ContextState.UNKNOWN })
        c.addTap(s)
        glance(c)
        assertEquals(ShadowCounts(open = 0, skip = 1), s.take())
    }

    @Test fun usesThePlanGlanceNotTheRealOne() {
        val real = MotionClassifier(clock)                 // default params: this turn is a glance
        val plan = cfg(GlanceParams(minBoutRad = 1.9f))
        val s = ShadowRunner({ plan }, { ContextState.UNKNOWN }) // the plan wants a bigger turn
        real.addTap(s)
        glance(real)
        assertEquals(1, real.takeGlanceCounts().glances, "the real detector fired")
        assertEquals(ShadowCounts(0, 0), s.take(), "the plan's detector did not")
    }

    @Test fun anInactiveShadowCountsNothing() {
        val c = MotionClassifier(clock)
        val s = ShadowRunner({ null }, { ContextState.UNKNOWN })
        c.addTap(s)
        glance(c)
        assertEquals(ShadowCounts(0, 0), s.take())
    }

    @Test fun aNewPlanStartsFromACleanDetector() {
        var g: ActiveConfig? = cfg(GlanceParams())
        val c = MotionClassifier(clock)
        val s = ShadowRunner({ g }, { ContextState.UNKNOWN })
        c.addTap(s)
        gyro(c, 2_000, 0.01f); gyro(c, 500, 1.5f)   // a bout in progress under plan A
        g = cfg(GlanceParams(settleMs = 1_600))     // plan B arrives mid-bout
        gyro(c, 3_000, 0.01f)
        assertEquals(ShadowCounts(0, 0), s.take(), "no glance spans two plans")
    }

    private fun restrictiveShadowPlan(): com.rokid.context.capture.plan.CapturePlan {
        val base = com.rokid.context.capture.plan.PlanResolver.identity()
        val slow = base.policy.mapValues { (_, r) -> r.copy(timerPhotoGapsMs = TimerPhotoGaps(300_000, 300_000, 300_000, 300_000)) }
        return PlanTestKit.plan(base.copy(policy = slow), "restrictive")
    }

    /** Each recording needs its own virtual clock (the recorder reads the scheduler's absolute time). */
    private fun rec(s: com.rokid.context.glasses.capture.golden.GoldenScript, planFor: ((com.rokid.context.glasses.capture.session.Rig) -> PlanController)? = null): String {
        var text = ""
        runTest(timeout = 10.minutes) { text = DecisionGoldenRecorder.record(this, s, planFor = planFor) }
        return text
    }

    private fun withShadow(s: com.rokid.context.glasses.capture.golden.GoldenScript, out: (PlanController, ShadowRunner) -> Unit = { _, _ -> }) = rec(s) { r ->
        RigPlan.controller(r) { it.onMessage(shadow(1, restrictiveShadowPlan())) }.also { c ->
            val runner = ShadowRunner({ c.shadow }, { r.applied }, { r.session.status.value.restingUntilMs != null })
            r.motion.addTap(runner)
            r.test.backgroundScope.launch { delay(s.durationMs - 1_000); out(c, runner) }
        }
    }

    @Test fun neverOpensCamera() {
        // The runner holds no camera, session or owner: it only counts. The real session beside it opens the camera exactly as it does without it.
        val s0 = GoldenScripts.unknownWalk
        val plain = rec(s0)
        var counts: ShadowCounts? = null
        val shadowed = withShadow(s0) { _, runner -> counts = runner.take() }
        assertTrue(plain == shadowed, firstDiff(plain, shadowed))
        assertTrue(counts!!.open + counts!!.skip > 0, "the shadow saw glances: $counts")
    }

    /** S67: SHADOW changes counts only. Real capture under a restrictive SHADOW plan equals the settings path in FLIGHT and at REST. */
    @Test fun shadowCaptureEqualsSettingsUnderRestrictiveFlightAndRest() {
        for (s in listOf(GoldenScripts.flightSettings, GoldenScripts.restStill, GoldenScripts.workRestRest)) {
            val plain = rec(s)
            var installed = false
            val shadowed = withShadow(s) { c, _ -> installed = c.shadow != null && c.status().mode == PlanMode.SHADOW }
            assertTrue(installed, "${s.name}: the SHADOW plan is installed and reported")
            assertTrue(plain == shadowed, "${s.name}: real capture is the settings path; " + firstDiff(plain, shadowed))
        }
    }
}
