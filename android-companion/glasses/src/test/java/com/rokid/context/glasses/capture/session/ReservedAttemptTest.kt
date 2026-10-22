package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.golden.DecisionGoldenRecorder
import com.rokid.context.glasses.capture.golden.GoldenScripts
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * S83 / S31 G1: while capture is eligible a reserved timer photo attempt (a camera open) happens at least every `reservedBoundMs`
 * (60 s active, 300 s at rest) whatever the timer photo gaps are. Through the real session and the fake camera owner.
 */
class ReservedAttemptTest {
    private val tol = 1_000L // one 250 ms tick plus a late first frame

    /** A profile whose every timer gap is [gap] (the plan's worst case, or beyond it): the timer alone would not meet the bound. */
    private fun restrictive(gap: Long) = CaptureProfile(CameraWake.SETTLE, gap, gap, gap, 333, false, stillCapMs = gap)

    private fun rig(t: TestScope, state: ContextState, gap: Long, settings: HudSettings = HudSettings(captureMode = CaptureMode.SMART), work: Int = 90, rest: Int = 15, configure: Rig.() -> Unit = {}) =
        Rig(t, settings, work = work, rest = rest).apply { applied = state; profile = restrictive(gap); configure() }

    private fun attempts(r: Rig) = listOf(r.createdAt) + r.camera.openTimes
    private fun gaps(times: List<Long>, from: Long = 0, to: Long = Long.MAX_VALUE): List<Long> {
        val w = times.filter { it in from..to }
        return w.zipWithNext { a, b -> b - a }
    }
    /** The last attempt must be recent too: starvation after an early success must not pass. */
    private fun assertFinalGap(r: Rig, bound: Long, what: String) {
        val last = attempts(r).last()
        assertTrue(r.clock() - last <= bound + tol, "$what: ${r.clock() - last} ms since the last attempt at the end (bound $bound)")
    }
    private fun resv(r: Rig) = r.session.diagSnapshot().wakes["resv"] ?: 0

    private fun walking(r: Rig) {
        r.test.backgroundScope.launch {
            while (isActive) {
                val t = r.clock()
                r.motion.onGyro(0.01f, 0f, 0f, t)
                r.motion.onLinearAccel(0f, (2.0 * sin(2 * PI * 1.8 * t / 1000.0)).toFloat(), 0f, t)
                delay(20)
            }
        }
    }

    @Test fun within60sInUnknownUnderRestrictivePlan() = runTest {
        for (gap in listOf(300_000L, 900_000L)) {
            val r = rig(this, ContextState.UNKNOWN, gap)
            r.startSensors(); r.gyroMag = 0.01f
            r.session.start()
            delay(10.minutes.inWholeMilliseconds)
            val g = gaps(attempts(r))
            assertTrue(g.isNotEmpty() && g.max() <= 60_000 + tol, "gap=$gap gaps=$g")
            assertFinalGap(r, 60_000L, "final")
            assertTrue(resv(r) >= 8, "reserved attempts carried it: ${resv(r)}")
            r.session.stop("test")
        }
    }

    @Test fun within60sWhileWalking() = runTest {
        val r = rig(this, ContextState.WALKING, 300_000)
        walking(r)
        r.session.start()
        delay(6.minutes.inWholeMilliseconds)
        assertEquals(com.rokid.context.core.model.MotionState.WALKING, r.motion.state.value)
        val g = gaps(attempts(r))
        assertTrue(g.isNotEmpty() && g.max() <= 60_000 + tol, "gaps=$g")
        assertFinalGap(r, 60_000L, "final")
        r.session.stop("test")
    }

    private fun atRest(state: ContextState) = runTest {
        for (gap in listOf(300_000L, 900_000L)) {
            val r = rig(this, state, gap)
            r.startSensors(); r.gyroMag = 0.01f
            r.session.start()
            delay(26.minutes.inWholeMilliseconds)
            val g = gaps(attempts(r))
            assertTrue(g.isNotEmpty() && g.max() <= 300_000 + tol, "$state gap=$gap gaps=$g")
            assertFinalGap(r, 300_000L, "final")
            if (gap == 900_000L) assertTrue(g.all { it >= 300_000 - tol }, "not more often than the bound at rest: $g")
            r.session.stop("test")
        }
    }

    @Test fun within300sInRestWithGap300s() = atRest(ContextState.REST)

    @Test fun within300sInFlight() = atRest(ContextState.FLIGHT)

    @Test fun within300sWhileWorkRestResting() = runTest {
        val settings = HudSettings(captureMode = CaptureMode.SMART, workRestEnabled = true)
        val r = rig(this, ContextState.UNKNOWN, 900_000, settings, work = 1, rest = 40)
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(30.minutes.inWholeMilliseconds)
        assertTrue(r.session.status.value.restingUntilMs != null, "resting")
        val g = gaps(attempts(r), from = 130_000)
        assertTrue(g.size >= 3 && g.max() <= 300_000 + tol, "gaps=$g")
        assertFinalGap(r, 300_000L, "final")
        assertTrue(g.all { it >= 300_000 - tol }, "the bound is 300 s while resting, not 60 s: $g")
        r.session.stop("test")
    }

    @Test fun continuousHeadMotionStillBounded() = runTest {
        val r = rig(this, ContextState.UNKNOWN, 300_000)
        r.startSensors(); r.gyroMag = 1.5f // never settles: SETTLE has no timer check while HEAD_MOVING
        r.session.start()
        delay(8.minutes.inWholeMilliseconds)
        assertEquals(com.rokid.context.core.model.MotionState.HEAD_MOVING, r.motion.state.value)
        val g = gaps(attempts(r))
        assertTrue(g.isNotEmpty() && g.max() <= 60_000 + tol, "gaps=$g")
        assertFinalGap(r, 60_000L, "final")
        r.session.stop("test")
    }

    @Test fun stillBackoffBounded() = runTest {
        val r = Rig(this, HudSettings(captureMode = CaptureMode.SMART)).apply { profile = CaptureProfile(CameraWake.SETTLE, 10_000, 30_000, 10_000, 333, false, stillCapMs = 300_000) }
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(15.minutes.inWholeMilliseconds)
        val g = gaps(attempts(r))
        assertTrue(g.max() <= 60_000 + tol, "the still-scene backoff (cap 300 s) never defeats the bound: $g")
        assertFinalGap(r, 60_000L, "final")
        assertTrue(r.session.stillStep >= 2, "the backoff ran")
        r.session.stop("test")
    }

    @Test fun heatSlowBounded() = runTest {
        val r = rig(this, ContextState.UNKNOWN, 300_000) { heatReading = HeatReading(0, Float.NaN, 41f) }
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(10.minutes.inWholeMilliseconds)
        assertEquals(HeatLevel.SLOW, r.heat.level.value)
        val g = gaps(attempts(r))
        assertTrue(g.max() <= 60_000 + tol, "gaps=$g")
        assertFinalGap(r, 60_000L, "final")
        r.session.stop("test")
    }

    @Test fun bindBackoffNotEligibleThenFires() = runTest {
        val r = rig(this, ContextState.UNKNOWN, 300_000)
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(115_000); r.camera.busyOpens = true   // a first attempt succeeded at 60 s, so this is no cold-start grace
        delay(34_000) // the attempt due at about 120 s finds the camera busy; the 30 s busy back-off holds every retry
        val during = r.camera.openTimes.filter { it in 116_000..149_000 }
        assertEquals(1, during.size, "one busy attempt, then not eligible until the back-off ends: ${r.camera.openTimes}")
        r.camera.busyOpens = false
        delay(60_000)
        val after = r.camera.openTimes.filter { it > during[0] + 1_000 }
        assertTrue(after.isNotEmpty() && after[0] - during[0] in 30_000..32_000, "fires as soon as it is eligible again: $after (busy at ${during[0]})")
        r.session.stop("test")
    }

    @Test fun failedOpenDoesNotResetDeadline() = runTest {
        val r = rig(this, ContextState.UNKNOWN, 300_000)
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(115_000); r.camera.failOpens = 1 // one bind failure on the attempt due at about 120 s (a first frame already arrived: no grace)
        delay(15_000)
        val near = r.camera.openTimes.filter { it in 116_000..135_000 }
        assertEquals(2, near.size, "the failed attempt is retried after the 1 s back-off, not 60 s later: ${r.camera.openTimes}")
        assertTrue(near[1] - near[0] in 1_000..2_500, "$near")
        r.session.stop("test")
    }

    @Test fun notWhilePausedNotWornHeatStoppedCameraBusy() = runTest {
        class Cond(val on: Rig.() -> Unit, val off: Rig.() -> Unit)
        val conditions = mapOf(
            "paused" to Cond({ paused = true }, { paused = false }),
            "not_worn" to Cond({ wear.onRemote(false) }, { wear.onRemote(true) }),
            "heat" to Cond({ heatReading = HeatReading(0, Float.NaN, 46f) }, { heatReading = HeatReading(0, Float.NaN, 30f) }),
            "busy" to Cond({ camera.busyOpens = true }, { camera.busyOpens = false }),
        )
        for ((name, c) in conditions) {
            val r = rig(this, ContextState.UNKNOWN, 300_000, HudSettings(captureMode = CaptureMode.SMART, pauseWhenNotWorn = true))
            r.startSensors(); r.gyroMag = 0.01f
            r.session.start()
            delay(20_000)
            c.on(r)
            delay(35_000) // past the poll and past any in-flight episode
            val n = r.camera.openTimes.size
            delay(5 * 60_000L)
            val extra = r.camera.openTimes.size - n
            if (name == "busy") assertTrue(extra <= 11, "$name: only back-off retries, ${extra}") // 30 s back-off between busy attempts
            else assertEquals(0, extra, "$name: no reserved attempt while not eligible")
            c.off(r)
            if (name == "heat") r.heat.poll()
            delay(130_000)
            assertTrue(r.camera.openTimes.size > n + (if (name == "busy") extra else 0), "$name: attempts resume once eligible")
            r.session.stop("test")
        }
    }

    @Test fun ignoresCapHoldButNotHardCeiling() = runTest {
        // Cap 2 holds timer checks for the rest of the minute after a keep; every open sees a new scene, so each frame would be kept.
        val r = Rig(this, HudSettings(captureMode = CaptureMode.SMART)).apply {
            profile = CaptureProfile(CameraWake.SETTLE, 5_000, 5_000, 5_000, 333, false, stillCapMs = 5_000); contextCap = 2
        }
        r.startSensors(); r.gyroMag = 0.01f
        r.test.backgroundScope.launch { var s = 10; while (isActive) { r.scene = s++; delay(2_000) } }
        r.session.start()
        delay(10.minutes.inWholeMilliseconds)
        val g = gaps(attempts(r))
        assertTrue(g.max() <= 60_000 + tol, "the hold never starves the bound: $g")
        assertFinalGap(r, 60_000L, "final")
        val keeps = r.keyframes.map { it.first }
        val worst = keeps.maxOfOrNull { k -> keeps.count { it in k..(k + 59_999) } } ?: 0
        assertTrue(keeps.isNotEmpty() && worst <= 2 * 2, "hard ceiling 2 x cap still applies in any minute: $worst")
        r.session.stop("test")
    }

    /** Task 1's goldens (settings path) gain exactly the `expectedResv` opens recorded in them, and no others. */
    @Test fun goldensPlusExactlyExpectedResv() {
        for (s in GoldenScripts.all) {
            var text = ""
            runTest(timeout = 5.minutes) { text = DecisionGoldenRecorder.record(this, s) }
            val golden = javaClass.getResourceAsStream("/golden/decisions-${s.name}.txt")!!.bufferedReader().use { it.readText() }
            val expected = golden.lineSequence().first { it.startsWith("# expectedResv=") }.removePrefix("# expectedResv=").split(',').filter { it.isNotBlank() }.map { it.toLong() }
            val actual = text.lineSequence().filter { Regex("""^\d+ open resv """).containsMatchIn(it) }.map { it.substringBefore(' ').toLong() }.toList()
            assertEquals(expected.size, actual.size, "${s.name}: resv opens $actual vs expected $expected")
            expected.zip(actual).forEach { (e, a) -> assertTrue(a >= e && a - e <= 250 + 600, "${s.name}: resv at $a, expected $e") }
            // everything else equals the golden: drop the resv open lines and compare the decision lines
            val strip = { t: String -> t.lineSequence().filterNot { it.startsWith("#") || Regex("""^\d+ open resv """).containsMatchIn(it) }.joinToString("\n") }
            if (expected.isEmpty()) assertEquals(strip(golden), strip(text), "${s.name}: decisions equal the golden")
        }
    }
}
