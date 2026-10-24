package com.rokid.context.canvas.moment

import com.rokid.context.memory.db.SceneRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Scene boundary rules (scene design section 4) on plain data: no database, no clock. Seconds are phone-clock seconds; a Moment is one second. */
class SceneCutterTest {
    private val base = 490_000L * 3_600_000L // a clock hour start, in ms
    private val cutter = SceneCutter()

    private fun m(offsetSec: Long, activity: String? = null, hash: Long? = null, emb: FloatArray? = null, rev: String = "h") =
        SceneMoment(MomentId.format((base / 1000) + offsetSec), base + offsetSec * 1000, com.rokid.context.memory.moment.MomentType.FRAME, activity, hash, hash, emb, emb, rev)

    private fun mark(offsetSec: Long, activity: String? = null) =
        SceneMoment(MomentId.format((base / 1000) + offsetSec), base + offsetSec * 1000, com.rokid.context.memory.moment.MomentType.MARK, activity, null, null, null, null, "h")

    private fun ids(p: List<ScenePlan>) = p.map { s -> s.members.map { (it.startMs - base) / 1000 } }

    @Test fun momentsWithNoChangeStayInOneSceneEndedByTheHour() {
        val p = cutter.cut(listOf(m(0, hash = 0), m(1, hash = 0), m(2, hash = 0)))
        assertEquals(listOf(listOf(0L, 1L, 2L)), ids(p)); assertEquals(SceneRule.HOUR_END, p.single().rule)
    }

    @Test fun anHourBoundaryAlwaysCuts() {
        val p = cutter.cut(listOf(m(3598, hash = 0), m(3599, hash = 0), m(3600, hash = 0)))
        assertEquals(listOf(listOf(3598L, 3599L), listOf(3600L)), ids(p))
        assertEquals(SceneRule.HOUR_END, p[0].rule); assertEquals(base, p[0].hourStartMs); assertEquals(base + 3_600_000L, p[1].hourStartMs)
    }

    @Test fun anHourBoundaryCutsEvenWhenActivityAndFramesAreTheSame() {
        val p = cutter.cut(listOf(m(3599, "WALKING", 0), m(3600, "WALKING", 0)))
        assertEquals(2, p.size)
    }

    @Test fun aHashDistanceOfSixteenBitsStaysAndSeventeenCuts() {
        val sixteen = (1L shl 16) - 1; val seventeen = (1L shl 17) - 1
        assertEquals(1, cutter.cut(listOf(m(0, hash = 0), m(1, hash = sixteen))).size)
        val p = cutter.cut(listOf(m(0, hash = 0), m(1, hash = seventeen)))
        assertEquals(listOf(listOf(0L), listOf(1L)), ids(p)); assertEquals(SceneRule.FRAME_CHANGE, p[0].rule)
    }

    @Test fun theHashIsComparedBetweenNeighboursNotAgainstTheSceneStart() {
        // each step moves 10 bits (under the limit), the total drift from the first is 30 bits
        val a = 0L; val b = (1L shl 10) - 1; val c = (1L shl 20) - 1; val d = (1L shl 30) - 1
        assertEquals(1, cutter.cut(listOf(m(0, hash = a), m(1, hash = b), m(2, hash = c), m(3, hash = d))).size)
    }

    @Test fun anEmbeddingBelowPointSevenFiveCutsEvenWhenTheHashesMatch() {
        val x = floatArrayOf(1f, 0f); val far = floatArrayOf(0.6f, 0.8f) // cosine 0.6
        val p = cutter.cut(listOf(m(0, hash = 0, emb = x), m(1, hash = 0, emb = far)))
        assertEquals(2, p.size); assertEquals(SceneRule.FRAME_CHANGE, p[0].rule)
    }

    @Test fun anEmbeddingAtPointSevenFiveOrAboveStays() {
        val x = floatArrayOf(1f, 0f); val near = floatArrayOf(0.8f, 0.6f) // cosine 0.8
        assertEquals(1, cutter.cut(listOf(m(0, hash = 0, emb = x), m(1, hash = 0, emb = near))).size)
    }

    @Test fun withoutEmbeddingsOnBothSidesOnlyTheHashDecides() {
        assertEquals(1, cutter.cut(listOf(m(0, hash = 0, emb = floatArrayOf(1f, 0f)), m(1, hash = 0, emb = null))).size)
    }

    @Test fun noHashAndNoEmbeddingMeansNoCut() {
        assertEquals(1, cutter.cut(listOf(m(0), m(1), m(2))).size)
    }

    @Test fun anActivityChangeCuts() {
        val p = cutter.cut(listOf(m(0, "STILL", 0), m(1, "STILL", 0), m(2, "WALKING", 0)))
        assertEquals(listOf(listOf(0L, 1L), listOf(2L)), ids(p)); assertEquals(SceneRule.ACTIVITY_CHANGE, p[0].rule)
    }

    @Test fun whenActivityIsLoggedFrameChangeIsIgnored() {
        val far = -1L // 64 bits away from 0
        assertEquals(1, cutter.cut(listOf(m(0, "STILL", 0), m(1, "STILL", far))).size)
    }

    @Test fun whenOnlyOneSideLoggedAnActivityTheFramesDecide() {
        assertEquals(1, cutter.cut(listOf(m(0, "STILL", 0), m(1, null, 0))).size)
        assertEquals(2, cutter.cut(listOf(m(0, "STILL", 0), m(1, null, -1L))).size)
    }

    @Test fun aSceneNeverExceedsFiveMinutes() {
        val p = cutter.cut((0L..300L).map { m(it, hash = 0) })
        assertEquals(listOf(300, 1), p.map { it.members.size })
        assertEquals(SceneRule.CAP_5_MIN, p[0].rule); assertEquals(SceneRule.HOUR_END, p[1].rule)
        assertEquals(base + 299_999L, p[0].endMs)
    }

    @Test fun theCapAppliesToActivityScenesToo() {
        val p = cutter.cut((0L..300L).map { m(it, "STILL", 0) })
        assertEquals(SceneRule.CAP_5_MIN, p[0].rule); assertEquals(2, p.size)
    }

    @Test fun aRealCutBeatsTheCapInTheRecordedRule() {
        val p = cutter.cut(listOf(m(0, "STILL", 0), m(300, "WALKING", 0)))
        assertEquals(SceneRule.ACTIVITY_CHANGE, p[0].rule)
    }

    @Test fun aSceneRecordsItsWindowIdActivityAndMembers() {
        val p = cutter.cut(listOf(m(10, "STILL", 0, rev = "a"), m(12, "STILL", 0, rev = "b"), m(13, null, 0, rev = "c"))).single()
        assertEquals("S" + MomentId.format((base / 1000) + 10), p.sceneId)
        assertEquals(base + 10_000L, p.startMs); assertEquals(base + 13_999L, p.endMs); assertEquals(base, p.hourStartMs)
        assertEquals("STILL", p.activity); assertEquals(listOf("a", "b", "c"), p.members.map { it.contentHash })
    }

    @Test fun theSceneActivityIgnoresMomentsWithoutOneAndIsNullWhenNoneHasOne() {
        assertEquals("STILL", cutter.cut(listOf(m(0, "STILL", 0), m(1, null, 0), m(2, null, 0))).single().activity)
        assertNull(cutter.cut(listOf(m(0, hash = 0), m(1, hash = 0))).single().activity)
    }

    @Test fun theResultDoesNotDependOnTheInputOrder() {
        val ms = listOf(m(0, "STILL", 0), m(1, "STILL", 0), m(2, "WALKING", 0), m(400, "WALKING", 0))
        assertEquals(cutter.cut(ms), cutter.cut(ms.reversed())); assertEquals(cutter.cut(ms), cutter.cut(ms))
    }

    @Test fun emptyInputIsNoScene() = assertEquals(emptyList<ScenePlan>(), cutter.cut(emptyList()))

    @Test fun theThresholdsLiveInOneConfig() {
        val tight = SceneCutter(SceneConfig(hashDistanceFraction = 0.0625)) // 4 bits
        assertEquals(2, tight.cut(listOf(m(0, hash = 0), m(1, hash = 31))).size)
        assertEquals(1, tight.cut(listOf(m(0, hash = 0), m(1, hash = 15))).size)
        assertEquals(2, SceneCutter(SceneConfig(capMs = 60_000)).cut((0L..60L).map { m(it, hash = 0) }).size)
        assertEquals(16, SceneConfig().maxHashBits)
    }

    // --- a MARK Moment has no frame hash: it never cuts by frame or activity and joins the neighbouring Scene by time ---

    @Test fun aMarkMomentBetweenTwoSimilarPhotosJoinsTheSceneAndCutsNothing() {
        val p = cutter.cut(listOf(m(0, hash = 0), mark(1), m(2, hash = 0)))
        assertEquals(listOf(listOf(0L, 1L, 2L)), ids(p))
    }

    @Test fun theFrameRuleStillComparesThePhotosAcrossAMarkMomentAndTheMarkJoinsTheEarlierScene() {
        val p = cutter.cut(listOf(m(0, hash = 0), mark(1), m(2, hash = (1L shl 17) - 1)))
        assertEquals(listOf(listOf(0L, 1L), listOf(2L)), ids(p)); assertEquals(SceneRule.FRAME_CHANGE, p[0].rule)
    }

    @Test fun aMarkMomentWithAnActivityThatDiffersNeverCutsByActivity() {
        val p = cutter.cut(listOf(m(0, "STILL", 0), mark(1, "WALKING"), m(2, "STILL", 0)))
        assertEquals(listOf(listOf(0L, 1L, 2L)), ids(p))
    }

    @Test fun marksAloneAreOneSceneUntilTheHourOrTheFiveMinuteCap() {
        assertEquals(listOf(listOf(0L, 100L, 299L)), ids(cutter.cut(listOf(mark(0), mark(100), mark(299)))))
        val p = cutter.cut(listOf(mark(0), mark(100), mark(300)))
        assertEquals(listOf(listOf(0L, 100L), listOf(300L)), ids(p)); assertEquals(SceneRule.CAP_5_MIN, p[0].rule)
        assertEquals(2, cutter.cut(listOf(mark(3599), mark(3600))).size)
    }
}
