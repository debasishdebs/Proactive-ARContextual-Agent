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
}
