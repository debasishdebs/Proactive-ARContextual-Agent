package com.rokid.context.work.frames.scene

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneRulesTest {
    private val t = SceneTuning()

    private fun unit(vararg x: Float): FloatArray {
        val n = sqrt(x.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(x.size) { x[it] / n }
    }

    private fun input(
        id: String = "f", ts: Long = 0, dwell: Boolean = false, motion: MotionClass = MotionClass.STILL,
        blur: Float? = null, v: FloatArray? = unit(1f, 0f), words: Int? = null,
    ) = RouteInput(id, ts, dwell, motion, blur, v, words)

    private fun view(
        openedAt: Long = 0, lastTs: Long = 10_000, n: Int = 5, motion: MotionClass = MotionClass.STILL,
        centroid: FloatArray? = unit(1f, 0f), streak: List<String> = emptyList(),
    ) = OpenClusterView(openedAt, lastTs, n, motion, centroid, streak)

    @Test fun salienceTermsAndCap() {
        val s = SceneRules.salience(true, 6, 0.0f, 0.4f, t)
        assertEquals(0.4f, s.dwell, 1e-6f); assertEquals(0.3f, s.text, 1e-6f)
        assertEquals(0.3f, s.novelty, 1e-6f); assertEquals(0.3f, s.watch, 1e-6f)
        assertEquals(1.0f, s.total, 1e-6f)
        val low = SceneRules.salience(false, 3, 0.9f, 0.1f, t)
        assertEquals(0f, low.text, 0f); assertEquals(0f, low.watch, 0f)
        assertEquals(0.03f, low.novelty, 1e-5f)
        assertEquals(0f, SceneRules.salience(false, null, null, null, t).total, 0f)
        assertTrue(SceneRules.salience(false, 4, 0.0f, null, t).total >= 0.5f)
        assertTrue(s.json().contains("\"dwell\""))
    }

    @Test fun dwellAlwaysFastPath() {
        val s = SceneRules.salience(true, null, 0.99f, null, t)
        assertEquals(Route.FastPath, SceneRules.route(input(dwell = true), s, "x" to 0.99f, t))
    }

    @Test fun dupAboveThresholdIsNearDup() {
        val a = unit(1f, 0f)
        val near = unit(1f, 0.1f)
        val far = unit(1f, 1f)
        val dup = SceneRules.nearestDup(near, listOf("far" to far, "a" to a), t)
        assertNotNull(dup); assertEquals("a", dup!!.first)
        assertNull(SceneRules.nearestDup(far, listOf("a" to a), t))
        val s = SceneRules.salience(false, null, 0.05f, null, t)
        assertEquals(Route.NearDup("a", false), SceneRules.route(input(), s, dup, t))
    }

    @Test fun salientDupGoesToCaptionQueue() {
        val s = SceneRules.salience(false, 8, 0.1f, null, t)
        assertEquals(Route.NearDup("a", true), SceneRules.route(input(words = 8), s, "a" to 0.95f, t))
    }

    @Test fun noVectorMeansFastPath() {
        val s = SceneRules.salience(false, null, null, null, t)
        assertEquals(Route.FastPath, SceneRules.route(input(v = null), s, null, t))
    }

    @Test fun nonSalientClusters() {
        val s = SceneRules.salience(false, 2, 0.5f, null, t)
        assertEquals(Route.Cluster, SceneRules.route(input(), s, null, t))
        val salient = SceneRules.salience(false, 5, 0.2f, null, t)
        assertEquals(Route.FastPath, SceneRules.route(input(words = 5), salient, null, t))
    }

    @Test fun driftNeedsTwoConsecutive() {
        val off = unit(0f, 1f)
        assertTrue(SceneRules.isDrifting(unit(1f, 0f), off, t))
        assertEquals(SceneRules.Close.No, SceneRules.shouldClose(view(), input(ts = 11_000, v = off), t))
        val c = SceneRules.shouldClose(view(streak = listOf("d1")), input(ts = 12_000, v = off), t)
        assertTrue(c is SceneRules.Close.Yes && c.reason == "drift")
        assertEquals(SceneRules.Close.No, SceneRules.shouldClose(view(), input(ts = 11_000, v = unit(1f, 0.1f)), t))
    }

    @Test fun driftCloseCarriesFirstDriftingFrame() {
        val c = SceneRules.shouldClose(view(streak = listOf("d1")), input("d2", 12_000, v = unit(0f, 1f)), t)
        assertEquals(SceneRules.Close.Yes("drift", listOf("d1")), c)
    }

    @Test fun motionChangeCloses() {
        val c = SceneRules.shouldClose(view(), input(ts = 11_000, motion = MotionClass.MOVING), t)
        assertEquals(SceneRules.Close.Yes("motion", emptyList()), c)
    }

    @Test fun unknownMotionNeverCloses() {
        assertEquals(SceneRules.Close.No, SceneRules.shouldClose(view(), input(ts = 11_000, motion = MotionClass.UNKNOWN), t))
        assertEquals(SceneRules.Close.No, SceneRules.shouldClose(view(motion = MotionClass.UNKNOWN), input(ts = 11_000, motion = MotionClass.MOVING), t))
    }

    @Test fun sizeAgeGapOutOfOrderClose() {
        fun reason(c: SceneRules.Close) = (c as? SceneRules.Close.Yes)?.reason
        assertEquals("size", reason(SceneRules.shouldClose(view(n = 100), input(ts = 11_000), t)))
        assertEquals(SceneRules.Close.No, SceneRules.shouldClose(view(n = 99), input(ts = 11_000), t))
        assertEquals("age", reason(SceneRules.shouldClose(view(lastTs = 299_000), input(ts = 300_000), t)))
        assertEquals("gap", reason(SceneRules.shouldClose(view(), input(ts = 10_000 + 120_001), t)))
        assertEquals(SceneRules.Close.No, SceneRules.shouldClose(view(), input(ts = 10_000 + 120_000), t))
        assertEquals("out_of_order", reason(SceneRules.shouldClose(view(lastTs = 100_000), input(ts = 39_999), t)))
        assertEquals(SceneRules.Close.No, SceneRules.shouldClose(view(lastTs = 100_000), input(ts = 40_000), t))
        assertEquals("age", SceneRules.closeByTime(view(lastTs = 299_000), 300_000, t))
        assertEquals("gap", SceneRules.closeByTime(view(), 10_000 + 120_001, t))
        assertNull(SceneRules.closeByTime(view(), 20_000, t))
    }

    @Test fun repsMedoidMostDifferentSharpest() {
        val a = unit(1f, 0f, 0f); val b = unit(1f, 0.2f, 0f); val c = unit(1f, -0.2f, 0f)
        val far = unit(0f, 0f, 1f)
        val d = unit(1f, 0f, 0.3f)
        val reps = SceneRules.pickReps(
            listOf(
                RepCandidate("a", a, 0.1f), RepCandidate("b", b, 0.9f), RepCandidate("c", c, 0.2f),
                RepCandidate("far", far, 0.3f), RepCandidate("d", d, 0.5f),
            ), t,
        )
        assertEquals(listOf("d", "far", "b"), reps)
    }

    @Test fun repsDistinctWhenFewMembers() {
        assertEquals(listOf("a"), SceneRules.pickReps(listOf(RepCandidate("a", unit(1f, 0f), 1f)), t))
        val two = SceneRules.pickReps(listOf(RepCandidate("a", unit(1f, 0f), 1f), RepCandidate("b", unit(0f, 1f), null)), t)
        assertEquals(2, two.size); assertEquals(two.distinct(), two)
        assertTrue(SceneRules.pickReps(emptyList(), t).isEmpty())
    }

    @Test fun photosPerClusterTable() {
        assertEquals(0, SceneRules.photosPerCluster(0, t))
        assertEquals(3, SceneRules.photosPerCluster(1, t))
        assertEquals(3, SceneRules.photosPerCluster(2, t))
        assertEquals(2, SceneRules.photosPerCluster(3, t))
        for (n in 1..3) assertTrue(n * SceneRules.photosPerCluster(n, t) <= t.callMaxPhotos)
        val c = SceneRules.centroid(listOf(unit(1f, 0f), unit(0f, 1f)))
        assertEquals(1f, c[0] * c[0] + c[1] * c[1], 1e-5f)
    }

    // ---- replay (spec P4.6): ~200 synthetic frames, 6 scenes ----

    private class Frame(val id: String, val ts: Long, val v: FloatArray, val motion: MotionClass, val dwell: Boolean, val words: Int?)

    private val dims = 42

    /** Scene base on axis [scene]; noise on its own axis per frame so frames differ (cos ~0.75) but stay near the base. */
    private fun sceneVec(scene: Int, i: Int): FloatArray {
        val th = Math.toRadians(30.0)
        val v = FloatArray(dims)
        v[scene] = cos(th).toFloat()
        v[6 + (i % 36)] += sin(th).toFloat()
        return v
    }

    @Test fun replayDayGivesExpectedClusters() {
        val frames = ArrayList<Frame>()
        var ts = 0L
        val sizes = intArrayOf(33, 33, 33, 33, 33, 35)
        for (s in 0 until 6) {
            if (s == 5) ts += 3 * 60_000L // a pause before the last scene (gap close)
            for (i in 0 until sizes[s]) {
                ts += 7_000
                val moving = s == 3 // scene 3 happens while moving (motion close on entry and exit)
                frames += Frame(
                    "s${s}f$i", ts, sceneVec(s, i + s * 3),
                    if (moving) MotionClass.MOVING else MotionClass.STILL,
                    dwell = s == 2 && i == 10,
                    words = if (s == 4 && i == 20) 7 else null,
                )
            }
        }
        assertEquals(200, frames.size)

        class Open(val openedAt: Long, var lastTs: Long, val motion: MotionClass) {
            val members = ArrayList<Frame>(); var streak = ArrayList<String>()
        }
        val closed = ArrayList<Pair<String, List<String>>>()
        val salient = ArrayList<String>(); val nearDup = ArrayList<String>()
        val history = ArrayList<Frame>()
        var open: Open? = null

        fun view(o: Open) = OpenClusterView(
            o.openedAt, o.lastTs, o.members.size, o.motion,
            SceneRules.centroid(o.members.map { it.v }), o.streak.toList(),
        )

        for (f in frames) {
            val vec = f.v.let { SceneRules.centroid(listOf(it)) }
            val recent = history.filter { f.ts - it.ts <= t.dedupeWindowMs }.map { it.id to it.v }
            val novelty = history.filter { f.ts - it.ts <= t.noveltyWindowMs }
                .maxOfOrNull { h -> h.v.indices.sumOf { (h.v[it] * vec[it]).toDouble() }.toFloat() } ?: 0f
            val sal = SceneRules.salience(f.dwell, f.words, novelty, if (f.words != null) 0.4f else null, t) // text frame also hits the watch list
            val inp = RouteInput(f.id, f.ts, f.dwell, f.motion, null, vec, f.words)
            val route = SceneRules.route(inp, sal, SceneRules.nearestDup(vec, recent, t), t)
            history += f
            when (route) {
                Route.FastPath -> salient += f.id
                is Route.NearDup -> nearDup += f.id
                Route.Cluster -> {
                    var o = open
                    var carry: List<Frame> = emptyList()
                    if (o != null) {
                        val c = SceneRules.shouldClose(view(o), inp, t)
                        if (c is SceneRules.Close.Yes) {
                            carry = o.members.filter { it.id in c.carry }
                            o.members.removeAll(carry.toSet())
                            closed += c.reason to o.members.map { it.id }
                            o = null
                        } else {
                            o.streak = if (SceneRules.isDrifting(view(o).centroid, vec, t)) ArrayList(o.streak + f.id) else ArrayList()
                        }
                    }
                    if (o == null) {
                        o = Open(f.ts, f.ts, f.motion)
                        o.members += carry
                        o.members += f
                        o.streak = ArrayList()
                    } else {
                        o.members += f
                    }
                    o.lastTs = f.ts
                    open = o
                }
            }
        }
        open?.let { closed += "end" to it.members.map { m -> m.id } }

        assertEquals(listOf("s2f10", "s4f20"), salient)
        assertEquals(emptyList<String>(), nearDup)
        // 6 scenes -> 6 clusters; closes: drift (0->1), drift (1->2), motion (2->3), motion (3->4), gap (4->5), end
        assertEquals(listOf("drift", "drift", "motion", "motion", "gap", "end"), closed.map { it.first })
        assertEquals(listOf(33, 33, 32, 33, 32, 35), closed.map { it.second.size })
        assertTrue(closed[0].second.all { it.startsWith("s0") })
        assertTrue(closed[1].second.all { it.startsWith("s1") })
        assertTrue(closed[2].second.all { it.startsWith("s2") })
        assertTrue(closed[5].second.all { it.startsWith("s5") })
        val total = closed.sumOf { it.second.size } + salient.size
        assertEquals(200, total)
        assertFalse(closed.any { it.second.isEmpty() })
    }
}
