package com.rokid.context.capture.replay

import com.rokid.context.capture.plan.ExprRunner
import com.rokid.context.capture.plan.ExprSignals
import com.rokid.context.capture.replay.ReplayFixtures.START
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.MotionClassifier
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 3c X9: replay-vs-runner parity on random traces. A random head-turn trace is replayed with a candidate guard (a scalar threshold, or a window) and the same sensors are
 * fed by hand to a classifier whose features are read once a second into a fresh [ExprRunner] (the live path, as `ReplayExprTest` does for one trace): the verdict at every
 * dwell must be the replay's. The value programs run on the same once-a-second feed and must answer on as many seconds as the replay's `valueTicks` says.
 */
class ReplayRunnerParityFuzzTest {
    private val end = START + 120_000

    private fun guard(s: String, out: Int): JsonObject = Json.parseToJsonElement("""{"id":"g","role":"guard","out":$out,"s":[$s]}""").jsonObject
    private fun scalar(thr: Double) = guard("""{"op":"sig","name":"gyroMax"},{"op":"const","v":$thr},{"op":"cmp","f":">","a":0,"b":1}""", 2)
    private fun windowed(sec: Int, thr: Double) = guard("""{"op":"win","name":"gyroMax","agg":"max","sec":$sec},{"op":"const","v":$thr},{"op":"cmp","f":"<=","a":0,"b":1}""", 2)

    /** Random head turns between 20 s and 110 s, at least 6 s apart; the rest is a still head. */
    private fun sensors(r: Random): List<TraceEvent> {
        val turns = ArrayList<Pair<Long, Long>>()
        var t = 20_000L + r.nextInt(3_000)
        while (t < 108_000) { val len = 300L + r.nextInt(900); turns += t to t + len; t += len + 6_000 + r.nextInt(8_000) }
        val amp = turns.map { 0.4f + r.nextInt(30) / 10f }
        return (START..end step 20).flatMap { at ->
            val i = turns.indexOfFirst { at - START in it.first..it.second }
            listOf(TraceEvent.Gyro(at, at, 0f, 0f, if (i >= 0) amp[i] else 0.02f), TraceEvent.Accel(at, at, 0f, 0f, 0f))
        }
    }

    private fun trace(sensors: List<TraceEvent>) = ReplayFixtures.trace(sensors + ReplayFixtures.timerOracle(untilMs = 19_000).events, endMs = end)

    private fun live(events: List<TraceEvent>, g: JsonObject): List<Pair<Long, Boolean>> {
        var now = START
        val cls = MotionClassifier({ now }, ReplayFixtures.glance)
        val runner = ExprRunner.of(listOf(g))!!
        val sig = ExprSignals(); runner.bind(sig)
        var lastAt = Long.MIN_VALUE
        val out = ArrayList<Pair<Long, Boolean>>()
        cls.dwellListener = { t -> if (t > ReplayFixtures.PRE_ROLL_END) out += t to runner.keep(t, sig, ContextState.REST, 0) }
        for (e in events) {
            if (e.tMs > now) now = e.tMs
            if (lastAt == Long.MIN_VALUE || now - lastAt >= 1_000L) { val f = cls.features(); sig.set(SecondFeatures(f.gyroMax, f.gyroMean, f.gyroRms, f.aboveOnFrac, f.accVariance, f.stepHz.toFloat(), f.rotRad), now); lastAt = now }
            when (e) {
                is TraceEvent.Gyro -> cls.onGyro(e.x, e.y, e.z, e.sMs)
                is TraceEvent.Accel -> cls.onLinearAccel(e.x, e.y, e.z, e.sMs)
                else -> Unit
            }
        }
        return out
    }

    @Test fun guardVerdictsAtEveryDwellMatchTheLivePathOnRandomTraces() {
        var dwells = 0; var drops = 0
        for (seed in 1L..40L) {
            val r = Random(seed)
            val events = trace(sensors(r))
            val g = if (r.nextBoolean()) scalar(0.3 + r.nextInt(25) / 10.0) else windowed(1 + r.nextInt(6), 0.3 + r.nextInt(25) / 10.0)
            val replay = CaptureSimulator.replay(events.asSequence(), ReplayFixtures.manifest(endMs = end), ReplayCandidate("h", glance = ReplayFixtures.glance, exprs = listOf(g)))
            val want = live(events, g)
            assertEquals(want, replay.guardDecisions.map { it.atMs to it.keep }, "seed=$seed guard=$g")
            dwells += want.size; drops += want.count { !it.second }
        }
        assertTrue(dwells >= 40 && drops >= 5 && drops < dwells, "the fuzz must exercise both outcomes: $dwells dwells, $drops drops")
    }

    /** A value program on the same feed: the once-a-second tick answers on the same seconds, and a window program warms up for exactly its horizon. */
    @Test fun valueTicksMatchTheLivePathOnRandomTraces() {
        for (seed in 101L..120L) {
            val r = Random(seed)
            val events = trace(sensors(r))
            val sec = 1 + r.nextInt(8)
            val v = Json.parseToJsonElement("""{"id":"v","role":"value","row":"REST","field":"settledGapS","out":3,"s":[{"op":"win","name":"gyroMax","agg":"mean","sec":$sec},{"op":"const","v":10},{"op":"arith","f":"*","a":0,"b":1},{"op":"clamp","a":2,"lo":5,"hi":60}]}""").jsonObject
            val replay = CaptureSimulator.replay(events.asSequence(), ReplayFixtures.manifest(endMs = end), ReplayCandidate("h", glance = ReplayFixtures.glance, exprs = listOf(v)))
            var now = START
            val cls = MotionClassifier({ now }, ReplayFixtures.glance)
            val runner = ExprRunner.of(listOf(v))!!
            val sig = ExprSignals(); runner.bind(sig)
            var lastAt = Long.MIN_VALUE
            for (e in events) {
                if (e.tMs > now) now = e.tMs
                if (lastAt == Long.MIN_VALUE || now - lastAt >= 1_000L) {
                    val f = cls.features(); sig.set(SecondFeatures(f.gyroMax, f.gyroMean, f.gyroRms, f.aboveOnFrac, f.accVariance, f.stepHz.toFloat(), f.rotRad), now); lastAt = now
                    runner.tickValues(now, sig, ContextState.REST, 0)
                }
                when (e) { is TraceEvent.Gyro -> cls.onGyro(e.x, e.y, e.z, e.sMs); is TraceEvent.Accel -> cls.onLinearAccel(e.x, e.y, e.z, e.sMs); else -> Unit }
            }
            assertEquals(runner.tunedTicks(0), replay.valueTicks.getValue("v"), "seed=$seed window=$sec s")
            assertTrue(replay.valueTicks.getValue("v") in 100..121, "${replay.valueTicks} for a 120 s trace")
        }
    }
}
