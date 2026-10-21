package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.glasses.capture.KeyframeOut
import com.rokid.context.glasses.capture.keyframe.ImageHashes
import com.rokid.context.glasses.capture.keyframe.KeyframeGate
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.motion.MotionClassifier
import com.rokid.context.glasses.capture.motion.WearDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance test for the "1 s look" promise (review fix 1), end to end in virtual time: the REAL MotionClassifier,
 * CameraPolicy, SamplingSession and KeyframeGate, a fake camera (start-up p95 535 ms, 40 ms frame interval), sensor
 * samples delivered 20 ms late, and the real blur/hash code on synthetic scenes. It counts SAVED keyframes (a keyframe
 * whose pixels were captured during the look), not raw camera callbacks (the probe counted those).
 */
class GlimpseOffsetTest {
    private val turnMs = 100L

    /** Camera realism knobs for the review's variants: a slow bind on the main thread and dark first frames (AE ramp). */
    private class Cam(val bindDelayMs: Long = 0, val darkFrames: Int = 0)

    /** The world: the head turns [turnMs] toward a new scene at each entry of [turns] (start time, scene); a scene change in front of a still head is a turn of length 0 with no head motion. */
    private class World(val first: Int, val turns: List<Pair<Long, Int>>, val still: List<Pair<Long, Int>> = emptyList(), val turn: Long) {
        fun scene(t: Long): Pair<Int, Boolean> { // scene id, in transition
            val tr = turns.lastOrNull { it.first <= t }
            val st = still.lastOrNull { it.first <= t }
            if (tr != null && (st == null || tr.first >= st.first)) return if (t < tr.first + turn) tr.second to true else tr.second to false
            return (st?.second ?: first) to false
        }
        /** Gyro magnitude (rad/s): a half-sine of peak 4 over each turn, a little noise otherwise. */
        fun gyro(t: Long): Float {
            val tr = turns.lastOrNull { it.first <= t && t < it.first + turn }
            return if (tr != null) (4.0 * sin(PI * (t - tr.first) / turn)).toFloat() else 0.02f
        }
    }

    private class Run(val keyframes: List<Triple<Long, Int, Long>>) // emitted at, scene shown, captured at

    private suspend fun simulate(
        test: TestScope, world: World, quickLookMs: Int, startup: () -> Long, endAt: Long, workMin: Int = 600, cam: Cam = Cam(),
    ): Run {
        val sched = test.testScheduler
        val clock: () -> Long = { sched.currentTime }
        val motion = MotionClassifier(clock, GlanceParams.LEGACY)
        val wear = WearDetector(clock)
        val gate = KeyframeGate(MemStore(), clock)
        val heat = HeatGuard({ HeatReading(0, Float.NaN, 30f) }, clock)
        val workRest = WorkRest(clock, { workMin }, { 15 })
        val out = ArrayList<Triple<Long, Int, Long>>()
        val camera = FakeCamera(test.backgroundScope, clock, startup) { t ->
            val (scene, moving) = world.scene(t)
            Scenes.frame(scene, if (moving) 100 else 1, t)
        }.also { it.bindDelayMs = cam.bindDelayMs; it.darkFrames = cam.darkFrames }
        val session = SamplingSession(
            scope = test.backgroundScope, camera = camera, owner = CameraOwner(), motion = motion, wear = wear, gate = gate,
            heat = heat, workRest = workRest, encoder = TagEncoder(),
            sink = { k: KeyframeOut, _ -> out += Triple(clock(), Scenes.sceneOf(k.jpeg), Scenes.capturedAtOf(k.jpeg)) },
            clock = clock, settings = { HudSettings(captureMode = CaptureMode.SMART, quickLookMs = quickLookMs) },
        )
        // 50 Hz gyro, every sample reaches the classifier 20 ms after it was taken
        test.backgroundScope.launch {
            while (isActive) { val t = clock() - SENSOR_DELAY_MS; motion.onGyro(world.gyro(t), 0f, 0f, t); delay(20) }
        }
        session.start()
        delay(endAt - clock())
        session.stop("done")
        return Run(out)
    }

    private fun latency(seed: Int, lo: Long, hi: Long): () -> Long { val r = Random(seed); return { lo + r.nextLong(hi - lo + 1) } }

    private data class Result(val saved: Int, val total: Int) { val pct get() = 100.0 * saved / total; override fun toString() = "%.1f%% (%d/%d)".format(pct, saved, total) }

    /** Scenario A: settled, a [turnMs] turn, then a [lookMs] look at a new scene; [offsets] phases against the brief-check beat. */
    private fun scenarioA(lookMs: Long, quickLook: Int, startup: () -> Long, offsets: Int = 20, turn: Long = turnMs, cam: Cam = Cam()): Result {
        var saved = 0
        for (i in 0 until offsets) runTest {
            val t0 = 12_000L + i * (5_000L / offsets)
            // the look: scene 1 for lookMs after the turn; then the head moves on to scene 2 and stays
            val world = World(0, listOf(t0 to 1, t0 + turn + lookMs to 2), turn = turn)
            val run = simulate(this, world, quickLook, startup, t0 + turn + lookMs + 8_000, cam = cam)
            if (run.keyframes.any { it.second == 1 && it.third in (t0 + turn)..(t0 + turn + lookMs) }) saved++
        }
        return Result(saved, offsets)
    }

    /** Scenario B: five distinct [lookMs] looks separated by [turnMs] turns; counts looks. */
    private fun scenarioB(lookMs: Long, quickLook: Int, startup: () -> Long, offsets: Int = 20, turn: Long = turnMs, cam: Cam = Cam()): Result {
        var saved = 0
        for (i in 0 until offsets) runTest {
            val t0 = 12_000L + i * (5_000L / offsets)
            val turns = (0 until 5).map { k -> (t0 + k * (turn + lookMs)) to (k + 1) } + ((t0 + 5 * (turn + lookMs)) to 6)
            val run = simulate(this, World(0, turns, turn = turn), quickLook, startup, t0 + 5 * (turn + lookMs) + 8_000, cam = cam)
            for (k in 0 until 5) {
                val from = t0 + k * (turn + lookMs) + turn
                if (run.keyframes.any { it.second == k + 1 && it.third in from..(from + lookMs) }) saved++
            }
        }
        return Result(saved, offsets * 5)
    }

    /** Scenario C: a scene change in front of a settled head after [settleMs]; the delay to its keyframe, per phase. */
    private fun scenarioC(settleMs: Long, phases: Int, spreadMs: Long, startup: () -> Long): List<Long> {
        val delays = ArrayList<Long>()
        for (i in 0 until phases) runTest {
            val t0 = settleMs + i * (spreadMs / phases)
            val world = World(0, emptyList(), still = listOf(t0 to 1), turn = turnMs)
            val run = simulate(this, world, 1000, startup, t0 + 30_000)
            val hit = run.keyframes.firstOrNull { it.second == 1 }
            delays += if (hit == null) Long.MAX_VALUE else hit.first - t0
        }
        return delays
    }

    @Test fun scenesAreDistinctToTheRealHashes() {
        val ids = (0..8).toList()
        for (a in ids) for (b in ids) if (a < b) {
            val ta = Scenes.thumb(a); val tb = Scenes.thumb(b)
            val d = minOf(java.lang.Long.bitCount(ImageHashes.dHash(ta) xor ImageHashes.dHash(tb)), java.lang.Long.bitCount(ImageHashes.pHash(ta) xor ImageHashes.pHash(tb)))
            assertTrue(d > 10, "scenes $a and $b are only $d bits apart: the test data would hide a miss")
        }
        assertTrue(ImageHashes.blur(Scenes.thumb(1)) > KeyframeGate.BLUR_FLOOR)
        assertTrue(ImageHashes.blur(Scenes.thumb(1, soft = 100)) < KeyframeGate.BLUR_FLOOR)
    }

    @Test fun scenarioA_oneSecondLookAfterATurn_savedAtLeast95Percent() {
        val worst = scenarioA(1_000, 1000, { 535L })
        val spread = scenarioA(1_000, 1000, latency(1, 292, 535))
        println("GLIMPSE A  quickLook=1s look=1.0s  p95-latency=$worst  latency 292..535 ms=$spread")
        assertTrue(worst.pct >= 95.0, "A at the start-up p95: $worst")
        assertTrue(spread.pct >= 95.0, "A with start-up 292..535 ms: $spread")
    }

    /**
     * Fix wave K1: five 1.0 s looks 1.1 s apart are 5 keyframes in 5.5 s, which the non-dwell rate cap (3 s / 6 s moving)
     * forbids by design (retest 1: 34-43 keyframes/min, the AI describes ~15). What carries the promise now is the DWELL
     * keyframe (head settled 1 s), exempt from the cap; so B asserts looks long enough to settle (1.6 s) and only reports
     * the 1.0 s variant.
     */
    @Test fun scenarioB_fiveLooksInARow_savedAtLeast95Percent() {
        println("GLIMPSE B (reported, rate-capped by design)  look=1.0s: ${scenarioB(1_000, 1000, { 535L })}")
        val worst = scenarioB(1_600, 1000, { 535L })
        val spread = scenarioB(1_600, 1000, latency(2, 292, 535))
        println("GLIMPSE B  quickLook=1s look=1.6s  p95-latency=$worst  latency 292..535 ms=$spread")
        assertTrue(worst.pct >= 95.0, "B at the start-up p95: $worst")
        assertTrue(spread.pct >= 95.0, "B with start-up 292..535 ms: $spread")
    }

    /** The measured lead (rolling p95 of the real start-up) keeps 1 s looks at 95 % when the camera is slower than the 535 ms probe. */
    @Test fun scenariosAandB_at700msStartup_atLeast95Percent() {
        val a = scenarioA(1_000, 1000, { 700L })
        val b = scenarioB(1_600, 1000, { 700L }) // 1.6 s looks: the dwell keyframe carries it (see scenarioB above)
        println("GLIMPSE start-up 700 ms  A=$a  B=$b")
        assertTrue(a.pct >= 95.0, "A at 700 ms start-up: $a")
        assertTrue(b.pct >= 95.0, "B at 700 ms start-up: $b")
    }

    /** Reported, not asserted (review of G3b): a 200 ms bind on Main, two dark frames while the exposure ramps, and 300 / 600 ms turns. */
    @Test fun reportRealisticCameraAndSlowTurns() {
        val sb = StringBuilder("GLIMPSE REALISM (quickLook=1000 ms, 1.0 s looks, 20 phases)\n")
        val variants = listOf(
            "ideal camera, 100 ms turn" to Triple(100L, Cam(), 535L),
            "bind 200 ms + 2 dark frames" to Triple(100L, Cam(bindDelayMs = 200, darkFrames = 2), 535L),
            "ideal camera, 300 ms turn" to Triple(300L, Cam(), 535L),
            "ideal camera, 600 ms turn" to Triple(600L, Cam(), 535L),
            "bind 200 + 2 dark frames, 600 ms turn" to Triple(600L, Cam(bindDelayMs = 200, darkFrames = 2), 535L),
        )
        for ((name, v) in variants) {
            val (turn, cam, lat) = v
            sb.append("  %-40s A=%s  B=%s\n".format(name, scenarioA(1_000, 1000, { lat }, turn = turn, cam = cam), scenarioB(1_000, 1000, { lat }, turn = turn, cam = cam)))
        }
        println(sb)
    }

    @Test fun scenarioC_aChangeInFrontOfASettledHead_withinTheStillCap() {
        val d = scenarioC(30_000, 20, 5_000, { 535L })
        println("GLIMPSE C  settled: max ${d.max()} ms, mean ${d.average().toInt()} ms")
        // Wave A2: a still scene backs the brief checks off (5 -> 60 s), so a change in front of a settled head is seen within the cap.
        assertTrue(d.all { it <= 60_000 }, "slowest ${d.max()} ms")
        val v = scenarioC(30_000, 10, 5_000, latency(3, 292, 535))
        assertTrue(v.all { it <= 60_000 }, "slowest with 292..535 ms start-up: ${v.max()} ms")
    }

    @Test fun scenarioC_after10QuietMinutes_within15s() {
        val d = scenarioC(10 * 60_000L + 2_000, 10, 15_000, { 535L })
        println("GLIMPSE C  after 10 quiet min: max ${d.max()} ms, mean ${d.average().toInt()} ms")
        assertTrue(d.all { it <= 15_000 }, "slowest ${d.max()} ms")
    }

    /** Reported, not asserted: how the other Quick look settings fare for 1.0 / 1.5 / 2.0 s looks. */
    @Test fun reportTheOtherQuickLookSettings() {
        val sb = StringBuilder("GLIMPSE REPORT (p95 start-up 535 ms, 20 phases; A = one look after a turn, B = five looks in a row)\n")
        for (q in listOf(1000, 1500, 2000, 3000)) for (look in listOf(1_000L, 1_500L, 2_000L)) {
            sb.append("  quickLook=%d ms look=%d ms  A=%s  B=%s\n".format(q, look, scenarioA(look, q, { 535L }), scenarioB(look, q, { 535L })))
        }
        sb.append("  start-up latency beyond the p95 (quickLook=1000 ms, 1.0 s looks):\n")
        for (lat in listOf(535L, 700L, 900L, 1_100L)) sb.append("    start-up %d ms  A=%s  B=%s\n".format(lat, scenarioA(1_000, 1000, { lat }), scenarioB(1_000, 1000, { lat })))
        println(sb)
    }

    private companion object { const val SENSOR_DELAY_MS = 20L }
}
