package com.rokid.context.glasses.capture.golden

import com.rokid.context.capture.plan.PlanConfig
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.TimerPhotoGaps
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.SampleTap
import com.rokid.context.glasses.capture.motion.SensorRate
import com.rokid.context.glasses.capture.plan.PlanTestKit
import com.rokid.context.glasses.capture.plan.RigPlan
import com.rokid.context.glasses.capture.session.Rig
import com.rokid.context.glasses.capture.session.SensorRatePolicy
import com.rokid.context.core.model.MotionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** S84 (4): a plan changes photo frequency only. Sensing, detection and the latency of a glance or a manual trigger do not move. */
class SensingUnchangedTest {
    private class Observed(val text: String, val rates: List<Pair<Long, SensorRate>>, val gyro: Int, val accel: Int)

    private fun slowGaps(c: PlanConfig) = c.copy(policy = c.policy.mapValues { (_, r) -> r.copy(timerPhotoGapsMs = TimerPhotoGaps(300_000, 300_000, 300_000, 300_000)) })

    private fun observe(s: GoldenScript, config: PlanConfig): Observed {
        var text = ""; cur = SensorRate.FULL; val rates = ArrayList<Pair<Long, SensorRate>>(); var g = 0; var a = 0
        runTest(timeout = 10.minutes) {
            text = DecisionGoldenRecorder.record(this, s, sensorRate = { cur }, composeProfile = true, planFor = { r ->
                RigPlan.controller(r) { it.onMessage(PlanTestKit.trial(1, PlanTestKit.plan(config), max = 3_600_000)) }.also { startObservers(r, rates) { dg, da -> g += dg; a += da } }
            })
        }
        return Observed(text, rates, g, a)
    }

    /** The rate the (scripted) sensors are registered at: set by the observer from the real rate policy, and obeyed by the recorder's feed. */
    @Volatile private var cur = SensorRate.FULL

    /** What CaptureService does each second: the rate policy over the session's own status reason and the motion state; plus a tap counting classifier inputs. */
    private fun startObservers(r: Rig, rates: MutableList<Pair<Long, SensorRate>>, counts: (Int, Int) -> Unit) {
        val policy = SensorRatePolicy()
        var last: SensorRate? = null
        r.test.backgroundScope.launch {
            while (isActive) {
                val rate = policy.update(r.session.status.value.reason, r.clock(), r.motion.state.value)
                cur = rate
                if (rate != last) { rates += r.clock() to rate; last = rate }
                delay(1_000)
            }
        }
        var gy = 0; var ac = 0
        r.motion.addTap(object : SampleTap {
            override fun onGyro(x: Float, y: Float, z: Float, tMs: Long, state: MotionState) { gy++; counts(1, 0) }
            override fun onAccel(x: Float, y: Float, z: Float, tMs: Long, gyroNewestMs: Long, state: MotionState) { ac++; counts(0, 1) }
        })
    }

    private fun lines(o: Observed, kinds: Set<String>) = o.text.lineSequence().filter { !it.startsWith("#") && it.split(' ').getOrNull(1) in kinds }.toList()
    private fun header(o: Observed, key: String) = o.text.lineSequence().first { it.startsWith("# $key=") }
    private fun opens(o: Observed, cause: String) = o.text.lineSequence().filter { Regex("""^\d+ open $cause( |$)""").containsMatchIn(it) }.toList()

    @Test fun timerPhotoGapLeavesSensingUnchanged() {
        for (s in listOf(GoldenScripts.restStill, GoldenScripts.unknownWalk)) {
            val identity = observe(s, PlanResolver.identity())
            val slow = observe(s, slowGaps(PlanResolver.identity()))
            assertEquals(identity.rates, slow.rates, "${s.name}: identical sensor registration calls and rates")
            assertTrue(identity.rates.isNotEmpty())
            assertEquals(identity.gyro, slow.gyro, "${s.name}: identical MotionClassifier input count")
            assertEquals(identity.accel, slow.accel, "${s.name}: identical MotionClassifier input count")
            assertTrue(identity.rates.any { it.second == SensorRate.STILL }, "${s.name}: the rate policy really moved to STILL, and the feed obeyed it")
            assertTrue(identity.gyro > 5_000)
            assertEquals(lines(identity, setOf("motion", "dwell", "glance")), lines(slow, setOf("motion", "dwell", "glance")), "${s.name}: detection unchanged")
            assertEquals(header(identity, "classifierFingerprint"), header(slow, "classifierFingerprint"))
            assertEquals(header(identity, "glanceTriggerToOpenLatencyMs"), header(slow, "glanceTriggerToOpenLatencyMs"), "${s.name}: glance-trigger-to-open latencies")
            if (s === GoldenScripts.restStill) assertTrue(header(identity, "glanceTriggerToOpenLatencyMs").length > "# glanceTriggerToOpenLatencyMs=".length, "${s.name}: there are glance opens to compare")
            for (c in listOf("dwell", "now", "onset", "stream")) assertEquals(opens(identity, c), opens(slow, c), "${s.name}: $c opens unchanged")
        }
        // and the plan did change something: the timer-cause opens (REST gaps 30-120 s against 300 s)
        val i = observe(GoldenScripts.restStill, PlanResolver.identity()); val sl = observe(GoldenScripts.restStill, slowGaps(PlanResolver.identity()))
        assertNotEquals(opens(i, "timer"), opens(sl, "timer"))
        assertTrue(opens(sl, "timer").size + opens(sl, "resv").size < opens(i, "timer").size)
    }
}
