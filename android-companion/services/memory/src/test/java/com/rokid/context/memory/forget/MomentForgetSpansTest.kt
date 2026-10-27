package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.MS
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.SEC
import com.rokid.context.memory.moment.MomentId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** `MomentForget.forgetSpans` / `preview` (Forget scope MOMENT and SCENE): the range-Forget code over several spans, whole Scenes, run rows by key, and counts from the same discovery. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentForgetSpansTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentForgetFixture(db)
    private val s3 = db.canvasSlice3Dao()
    @After fun close() = db.close()
    private fun mid(sec: Long) = MomentId.format(SEC + sec)

    private suspend fun scene(id: String, vararg moments: Long) {
        db.sceneDao().replace(SceneEntity(id, 1, MS, MS + moments.min() * 1000, MS + moments.max() * 1000 + 999, SceneRule.CAP_5_MIN, membershipRev = 1, builtAt = 1),
            moments.map { ScenePartEntity(id, ScenePartKind.MOMENT, mid(it), "r", MS + it * 1000) })
    }
    private suspend fun run(runId: String, frameKey: String) {
        if (db.canvasDao().runs(50).none { it.runId == runId }) db.canvasDao().insertRun(CanvasRunEntity(runId, "p", null, "h", "{}", "DONE", 1, 1, 0, 0, 1, null, "[]"))
        db.canvasDao().upsertItem(CanvasRunItemEntity(runId, frameKey, "n", "OK", null, "{\"ok\":true}", null, null, null, null, null, null, null))
    }

    @Test fun severalSpansAreForgottenAndFencedAndWhatLiesBetweenSurvives() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("keep", MS + 20_000); fx.frame("b", MS + 40_000); fx.memory("m1", "a"); fx.memory("mk", "keep"); fx.memory("m3", "b")
        val r = fx.forget.forgetSpans(listOf(MS..(MS + 999), (MS + 40_000)..(MS + 40_999)))
        assertEquals(listOf("keep"), fx.frameIds()); assertEquals(listOf("mk"), db.forgetDao().memories(listOf("m1", "mk", "m3")).map { it.id })
        assertTrue(s3.momentFenced(MS, MS + 999)); assertTrue(s3.momentFenced(MS + 40_000, MS + 40_999)); assertFalse(s3.momentFenced(MS + 20_000, MS + 20_999))
        assertEquals(2, r.seconds); assertEquals(2, r.frames)
    }

    @Test fun overlappingSpansAreMergedAndARangeOutsideTheClockIsIgnored() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        val r = fx.forget.forgetSpans(listOf(MS..(MS + 1500), (MS + 1000)..(MS + 2500), -9000L..-1L))
        assertEquals(listOf(MS..(MS + 2999)), s3.momentForgetRanges()); assertEquals(3, r.seconds)
        assertEquals(com.rokid.context.memory.forget.MomentForget.Result.EMPTY, fx.forget.forgetSpans(listOf(-5000L..-1L)))
    }

    @Test fun aSceneGoesWholeWithItsPartsAndItsRunRowsAndNeighbourScenesStay() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("far", MS + 600_000); fx.memory("m1", "a")
        fx.project(mid(0), MomentPartKind.FRAME to "a"); fx.project(mid(600), MomentPartKind.FRAME to "far")
        scene("sc-1", 0); scene("sc-far", 600)
        run("r1", "scene:sc-1"); run("r1", "moment:${mid(0)}"); run("r1", "scene:sc-far"); run("r1", "moment:${mid(600)}")
        fx.forget.forgetSpans(listOf(MS..(MS + 999)), sceneIds = listOf("sc-1"))
        assertNull(db.sceneDao().scene("sc-1")); assertEquals(emptyList<ScenePartEntity>(), db.sceneDao().parts("sc-1"))
        assertEquals(listOf("scene:sc-far", "moment:${mid(600)}").sorted(), db.canvasDao().items("r1").map { it.frameId }.sorted())
        assertEquals(listOf("sc-far"), db.sceneDao().allScenes().map { it.sceneId })
    }

    @Test fun aSceneWithNoMomentLeftInTheWindowIsStillRemovedWhole() = runBlocking<Unit> {
        scene("sc-ghost", 5, 6) // its parts are not stored as Moments any more
        fx.forget.forgetSpans(listOf((MS + 5000)..(MS + 6999)), sceneIds = listOf("sc-ghost"))
        assertNull(db.sceneDao().scene("sc-ghost")); assertTrue(db.sceneDao().parts("sc-ghost").isEmpty())
    }

    @Test fun withoutSceneIdsAScenePartiallyInTheWindowOnlyShrinks() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("b", MS + 20_000 + 5); fx.project(mid(0), MomentPartKind.FRAME to "a"); fx.project(mid(20), MomentPartKind.FRAME to "b"); scene("sc-1", 0, 20)
        fx.forget.forgetSpans(listOf(MS..(MS + 999)))
        assertEquals(listOf(mid(20)), db.sceneDao().parts("sc-1").map { it.refId })
    }

    @Test fun theRunRowsOfAGoneMomentAreDeletedByKeyEvenWithoutAnyLineageToken() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.project(mid(0), MomentPartKind.FRAME to "a"); run("r2", "moment:${mid(0)}")
        fx.forget.forgetSpans(listOf(MS..(MS + 999)))
        assertTrue(db.canvasDao().items("r2").isEmpty())
    }

    @Test fun theRangeAndIdForgetsKeepTheirOldRunRowBehaviour() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.project(mid(0), MomentPartKind.FRAME to "a"); run("r3", "moment:${mid(0)}")
        fx.forget.forgetRange(MS, MS + 999)
        assertEquals("by-key row deletion is the scoped Forget's, not the range Forget's", 1, db.canvasDao().items("r3").size)
    }

    @Test fun previewCountsAreExactlyWhatTheForgetThenRemoves() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("b", MS + 900); fx.frame("far", MS + 90_000); fx.memory("m1", "a"); fx.memory("m2", "b"); fx.memory("mfar", "far"); fx.mark("L1", MS + 500); fx.mark("Lfar", MS + 95_000)
        fx.project(mid(0), MomentPartKind.FRAME to "a", MomentPartKind.FRAME to "b", MomentPartKind.MEMORY to "m1", MomentPartKind.MARK to "L1"); fx.project(mid(90), MomentPartKind.FRAME to "far")
        scene("sc-1", 0)
        val spans = listOf(MS..(MS + 999))
        val c = fx.forget.preview(spans, listOf("sc-1"))
        assertEquals(MomentForget.Counts(photos = 2, memories = 2, marks = 1, moments = 1, scenesRemoved = 1, scenesShrunk = 0), c.copy(planHash = ""))
        assertEquals("preview wrote nothing", listOf("a", "b", "far"), fx.frameIds()); assertEquals(0, db.momentDao().tombstoneCount()); assertTrue(s3.momentForgetRanges().isEmpty())
        val r = fx.forget.forgetSpans(spans, listOf("sc-1"))
        assertEquals(listOf(c.photos, c.memories, c.marks), listOf(r.frames, r.memories, r.marks))
        assertEquals(listOf("far"), fx.frameIds()); assertEquals(listOf("Lfar"), fx.markIds())
    }

    @Test fun aMomentHeldOnlyThroughAMemoryPartIsCounted() = runBlocking<Unit> {
        fx.frame("far", MS + 90_000); fx.memory("mfar", "far", createdAt = MS + 200)
        fx.project(mid(90), MomentPartKind.MEMORY to "mfar")
        assertEquals(1, fx.forget.preview(listOf(MS..(MS + 999))).moments)
    }

    @Test fun aMemoryBornInTheWindowButCitingAPhotoFarAwayIsCountedAsAMemory() = runBlocking<Unit> {
        fx.frame("far", MS + 90_000); fx.memory("mfar", "far", createdAt = MS + 200)
        assertEquals(1, fx.forget.preview(listOf(MS..(MS + 999))).memories)
    }

    @Test fun aNamedSceneGoesWholeEvenWhenSomeOfItsPartsLieOutsideTheSpans() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("far", MS + 600_000); fx.project(mid(0), MomentPartKind.FRAME to "a"); fx.project(mid(600), MomentPartKind.FRAME to "far"); scene("sc-2", 0, 600)
        fx.forget.forgetSpans(listOf(MS..(MS + 999)), sceneIds = listOf("sc-2"))
        assertNull(db.sceneDao().scene("sc-2")); assertTrue(db.sceneDao().parts("sc-2").isEmpty())
    }

    @Test fun aMomentHeldOnlyThroughAMarkPartIsCounted() = runBlocking<Unit> {
        fx.mark("L1", MS + 500); fx.frame("far", MS + 90_000)
        fx.project(mid(90), MomentPartKind.FRAME to "far", MomentPartKind.MARK to "L1")
        assertEquals(1, fx.forget.preview(listOf(MS..(MS + 999))).moments)
    }

    @Test fun aMomentHeldOnlyThroughAFramePartIsCounted() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.project(mid(90), MomentPartKind.FRAME to "a")
        assertEquals(1, fx.forget.preview(listOf(MS..(MS + 999))).moments)
    }
}
