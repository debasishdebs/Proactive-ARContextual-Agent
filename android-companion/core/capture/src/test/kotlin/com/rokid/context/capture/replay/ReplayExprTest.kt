package com.rokid.context.capture.replay

import com.rokid.context.capture.replay.ReplayFixtures.START
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.glasses.capture.motion.MotionClassifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 3c X4: the simulator runs the same ExprRunner as the glasses; it counts (and acts on) the would-drops per guard. */
class ReplayExprTest {
    private fun guard(id: String, s: String, out: Int): JsonObject =
        Json.parseToJsonElement("""{"id":"$id","role":"guard","out":$out,"s":[$s]}""").jsonObject
    private fun gyroGt(t: Double, id: String = "g") =
        guard(id, """{"op":"sig","name":"gyroMax"},{"op":"const","v":$t},{"op":"cmp","f":">","a":0,"b":1}""", 2)
    private val alwaysDrop = guard("x", """{"op":"const","v":false}""", 0)
    private val identityLike = guard("i", """{"op":"const","v":true}""", 0)

    private fun cand(vararg e: JsonObject) = ReplayCandidate("h", glance = ReplayFixtures.glance, exprs = e.toList())
    private fun run(ev: List<TraceEvent>, c: ReplayCandidate?) = CaptureSimulator.replay(ev.asSequence(), ReplayFixtures.manifest(), c)

    /** The head turn at 20.0-20.7 s whose glance fires at 22,200 (the golden of CaptureSimulatorTest.dwellAndOnsetWindowsWithATwinAreExact), without the recorded async episodes. */
    private val end = START + 25_000
    private val sensors = (START..end step 20).flatMap { t ->
        val turning = t in 20_000..20_700
        listOf(TraceEvent.Gyro(t, t, 0f, 0f, if (turning) 1.5f else 0.02f), TraceEvent.Accel(t, t, 0f, 0f, 0f))
    }
    private val golden = ReplayFixtures.trace(sensors + ReplayFixtures.timerOracle(untilMs = 19_000).events, endMs = end)

    /** The live path by hand: a classifier fed the same sensors, features read once a second of event time (as the sensor feed does), the predicate applied at each dwell. */
    private fun liveGyroMaxAtDwells(): List<Pair<Long, Float>> {
        var now = START
        val cls = MotionClassifier({ now }, ReplayFixtures.glance)
        var feat = 0f; var lastAt = Long.MIN_VALUE
        val out = ArrayList<Pair<Long, Float>>()
        cls.dwellListener = { t -> out += t to feat }
        for (e in golden) {
            if (e.tMs > now) now = e.tMs
            if (lastAt == Long.MIN_VALUE || now - lastAt >= 1_000L) { feat = cls.features().gyroMax; lastAt = now }
            when (e) {
                is TraceEvent.Gyro -> cls.onGyro(e.x, e.y, e.z, e.sMs)
                is TraceEvent.Accel -> cls.onLinearAccel(e.x, e.y, e.z, e.sMs)
                else -> Unit
            }
        }
        return out.filter { it.first > ReplayFixtures.PRE_ROLL_END }
    }

    @Test fun ReplayExprExactTest() {
        val live = liveGyroMaxAtDwells()
        assertEquals(1, live.size, live.toString())
        val v = live[0].second.toDouble()
        // The threshold is exactly the live feature value: `>` drops, and an off-by-one `>=` would keep (the mutation).
        for ((thr, keep) in listOf(v to false, v - 0.001 to true, v + 0.001 to false)) {
            val r = run(golden, cand(gyroGt(thr)))
            assertEquals(listOf(live[0].first to keep), r.guardDecisions.map { it.atMs to it.keep }, "thr=$thr")
        }
    }

    @Test fun ExprChangesCountsUnderParamsTest() {
        val v = liveGyroMaxAtDwells()[0].second.toDouble()
        val low = run(golden, cand(gyroGt(v - 1.0)))
        val high = run(golden, cand(gyroGt(v + 1.0)))
        assertEquals(mapOf("g" to 0), low.guardDrops); assertEquals(1, low.glanceAdmissions)
        assertEquals(mapOf("g" to 1), high.guardDrops); assertEquals(0, high.glanceAdmissions)
        assertTrue(high.opens.none { it.cause == EpisodeCause.DWELL }, high.opens.toString())
        assertTrue(low.opens.any { it.cause == EpisodeCause.DWELL }, low.opens.toString())
    }

    @Test fun guardDropsAreCountedPerGuard() {
        val r = run(golden, cand(alwaysDrop, gyroGt(-1.0, "ok")))
        assertEquals(mapOf("x" to 1, "ok" to 0), r.guardDrops)
    }

    @Test fun identityLikeGuardChangesNothing() {
        val plain = run(golden, cand())
        val withG = run(golden, cand(identityLike))
        assertEquals(plain.opens, withG.opens); assertEquals(plain.glanceAdmissions, withG.glanceAdmissions)
        assertEquals(mapOf("i" to 0), withG.guardDrops)
    }

    @Test fun ReservedTimerUnaffectedByGuardTest() {
        val untilMs = START + 300_000
        val o = ReplayFixtures.timerOracle(untilMs = untilMs)
        val ev = ReplayFixtures.trace(o.events, endMs = untilMs)
        val r = run(ev, cand(alwaysDrop))
        assertEquals(run(ev, cand()).opens, r.opens, "the guard changes no timer attempt")
        for (min in 0 until 5) {
            val n = r.opens.count { it.cause == EpisodeCause.TIMER && it.atMs in (START + min * 60_000L) until (START + (min + 1) * 60_000L) }
            assertTrue(n >= 1, "minute $min has $n timer attempts: ${r.opens}")
        }
    }

    @Test fun ReplayGuardSeesAppliedCtxWhenCtxBoundFalse() {
        val restOnly = guard("c", """{"op":"sig","name":"ctx"},{"op":"in","a":0,"set":["REST"]}""", 1)
        val ev = ReplayFixtures.trace(sensors + ReplayFixtures.timerOracle(untilMs = 19_000).events + TraceEvent.Ctx(START + 5_000, com.rokid.context.core.model.context.ContextState.REST), endMs = end)
        val r = run(ev, ReplayCandidate("h", glance = ReplayFixtures.glance, ctxBound = false, exprs = listOf(restOnly)))
        assertEquals(listOf(true), r.guardDecisions.map { it.keep }, "applied REST: keep, as the glasses guard does")
    }

    @Test fun replayReportsBothErrorCounters() {
        val div0 = guard("d", """{"op":"const","v":1},{"op":"const","v":0},{"op":"arith","f":"/","a":0,"b":1},{"op":"const","v":0},{"op":"cmp","f":">","a":2,"b":3}""", 4)
        val r = run(golden, cand(div0))
        assertTrue(r.exprDiv0 >= 1, "div0 reported: ${r.exprDiv0}")
    }
}

/** 3c E2a X6: window terms replay from empty rings at the replay's start, exactly like a plan apply on the device. */
class ReplayWindowParityTest {
    private fun guard(id: String, s: String, out: Int): JsonObject =
        Json.parseToJsonElement("""{"id":"$id","role":"guard","out":$out,"s":[$s]}""").jsonObject

    /** keep while `max(gyroMax, [sec] s) > [thr]`. */
    private fun maxGuard(sec: Int, thr: Double, id: String = "w") =
        guard(id, """{"op":"win","name":"gyroMax","agg":"max","sec":$sec},{"op":"const","v":$thr},{"op":"cmp","f":">","a":0,"b":1}""", 2)

    private fun cand(vararg e: JsonObject) = ReplayCandidate("h", glance = ReplayFixtures.glance, exprs = e.toList())
    private fun run(ev: List<TraceEvent>, c: ReplayCandidate?) = CaptureSimulator.replay(ev.asSequence(), ReplayFixtures.manifest(), c)

    private val end = START + 25_000
    private val sensors = (START..end step 20).flatMap { t ->
        val turning = t in 20_000..20_700
        listOf(TraceEvent.Gyro(t, t, 0f, 0f, if (turning) 1.5f else 0.02f), TraceEvent.Accel(t, t, 0f, 0f, 0f))
    }
    private val golden = ReplayFixtures.trace(sensors + ReplayFixtures.timerOracle(untilMs = 19_000).events, endMs = end)

    /** The device by hand: a classifier fed the same sensors, one feature row a second of event time into a ring that starts empty, read at each dwell. */
    private fun liveMaxAtDwells(sec: Int): List<Pair<Long, Double?>> {
        var now = START
        val cls = MotionClassifier({ now }, ReplayFixtures.glance)
        val ring = com.rokid.context.core.model.context.graph.WindowRing(sec, com.rokid.context.core.model.context.graph.WindowRing.AGG_MAX)
        var lastAt = Long.MIN_VALUE
        val out = ArrayList<Pair<Long, Double?>>()
        cls.dwellListener = { t -> out += t to ring.value().takeIf { !it.isNaN() } }
        for (e in golden) {
            if (e.tMs > now) now = e.tMs
            if (lastAt == Long.MIN_VALUE || now - lastAt >= 1_000L) { ring.push(com.rokid.context.core.model.context.graph.WindowRing.quantise(cls.features().gyroMax.toDouble())!!); lastAt = now }
            when (e) {
                is TraceEvent.Gyro -> cls.onGyro(e.x, e.y, e.z, e.sMs)
                is TraceEvent.Accel -> cls.onLinearAccel(e.x, e.y, e.z, e.sMs)
                else -> Unit
            }
        }
        return out.filter { it.first > ReplayFixtures.PRE_ROLL_END }
    }

    @Test fun ReplayWindowParityTest() {
        val live = liveMaxAtDwells(5)
        assertEquals(1, live.size, live.toString())
        val v = live[0].second
        assertTrue(v != null && v > 0.0, "a 5 s window is full at the dwell (22.2 s): $v")
        // `>` against the exact live window value drops; a hair below keeps; the decision is the live ring's, not a partial or cold one
        for ((thr, keep) in listOf(v!! to false, v - 0.001 to true, v + 0.001 to false)) {
            val r = run(golden, cand(maxGuard(5, thr)))
            assertEquals(listOf(live[0].first to keep), r.guardDecisions.map { it.atMs to it.keep }, "thr=$thr")
            assertEquals(5, r.windowWarmingS); assertTrue(r.warming)
        }
    }

    /** 3d: a 3 s sensor stall (10.0-13.0 s, no events) leaves 2 gap seconds in a 20 s window that answers at the dwell (22.2 s): 18 of 20 valid. */
    @Test fun ReplayWindowToleranceParity() {
        val stalled = ReplayFixtures.trace(sensors.filter { it.tMs !in (START + 10_000)..(START + 13_000) } + ReplayFixtures.timerOracle(untilMs = 19_000).events, endMs = end)
        fun win(mv: String?) = guard("w", """{"op":"win","name":"gyroMax","agg":"max","sec":20${if (mv != null) ""","mv":$mv""" else ""}},{"op":"const","v":100},{"op":"cmp","f":">","a":0,"b":1}""", 2)
        // `max > 100` is false as soon as the window answers: the guard DROPS; while it is unavailable (a gap beyond the tolerance) it keeps
        fun keeps(mv: String?) = run(stalled, cand(win(mv))).guardDecisions.map { it.keep }
        assertEquals(listOf(false), keeps("90"), "18 of 20 valid at 90 %: exactly at the threshold")
        assertEquals(listOf(false), keeps("50"))
        assertEquals(listOf(true), keeps("91"), "one percent more than the trace has: unavailable, keeps")
        assertEquals(listOf(true), keeps("100")); assertEquals(listOf(true), keeps(null), "no tolerance written = strict")
        assertEquals(listOf(false), run(golden, cand(win("100"))).guardDecisions.map { it.keep }, "the same window over the unstalled trace answers strictly")
    }

    @Test fun replayStartsWarmingWhenTheHorizonOutlastsTheTrace() {
        val live = liveMaxAtDwells(30)
        assertEquals(listOf(null), live.map { it.second }, "the device would be warming too: 22 s of rings")
        val drops = run(golden, cand(maxGuard(30, 100.0)))   // max > 100 is false once a window answers: it must not answer yet
        assertEquals(listOf(true), drops.guardDecisions.map { it.keep }, "warming keeps: UNAVAILABLE never drops")
        assertEquals(0, drops.guardDrops.getValue("w")); assertEquals(30, drops.windowWarmingS)
    }

    @Test fun noWindowsMeansNotWarming() {
        val r = run(golden, cand(guard("i", """{"op":"const","v":true}""", 0)))
        assertEquals(0, r.windowWarmingS); assertTrue(!r.warming)
    }
}
