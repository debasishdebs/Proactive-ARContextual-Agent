package com.rokid.context.memory.forget

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentTombstoneEntity
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v18 WP10: the `scene_centroid` rows (derived from the user's photos) leave with every USER Forget that removes or changes their Scene (photo, radius, range, scoped Moment / Scene, multi-select, fact lock, Delete all),
 * and with any deletion of the Scene row itself (a Moment rebuild that empties it). They are KEPT, while the Scene row lives, by photo expiry, orphan expiry, `removeDescriptions` and the invalid-Moment purge
 * (a shrunk Scene keeps its row, now stale: its `membershipRev` moved). Each path runs on the [GoldenWorld] (rows of [GoldenWorld.seedCentroids]) with `forgetV2` OFF and ON. Rows are named `scene|space`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SceneCentroidForgetTest {
    private lateinit var w: GoldenWorld
    @After fun close() { ForgetV2.testOverride = null }

    private val all = setOf("sNear|e1", "sNear|e2", "sMixed|e1")
    private val iso = "sIso|e1"
    private val nearOnly = setOf("sNear|e1", "sNear|e2") // flag ON: the far photo / second changes only sMixed, so the Scene of the near photos keeps its centroid

    /**
     * The golden world's radius reaches every photo, so a photo, range or Moment Forget there changes both of its Scenes. [iso] is a Scene of its own (a member no Forget names, far from every span): its current
     * centroid must survive every Forget below except Delete all. [survivors] are the rows of `sNear` / `sMixed` expected to be left; [iso] is added unless [wipe].
     */
    private fun paths(survivors: Set<String>, on: Set<String>? = null, wipe: Boolean = false, op: suspend () -> Unit) = runBlocking {
        for (v2 in listOf(false, true)) {
            ForgetV2.testOverride = v2
            val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
            try {
                w = GoldenWorld(db); w.seed(); w.seedCentroids()
                val m = com.rokid.context.memory.moment.MomentId.ofMs(w.base + 4_000_000)
                db.sceneDao().replace(com.rokid.context.memory.db.SceneEntity("sIso", 1, 0L, w.base + 4_000_000, w.base + 4_000_999, com.rokid.context.memory.db.SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L),
                    listOf(com.rokid.context.memory.db.ScenePartEntity("sIso", com.rokid.context.memory.db.ScenePartKind.MOMENT, m, "r", w.base + 4_000_000)))
                db.sceneCentroidDao().upsert(com.rokid.context.memory.db.SceneCentroidEntity("sIso", "e1", 1, 2, ByteArray(8) { 1 }, 2, 1))
                op()
                val left = listOf("sNear", "sMixed", "sIso").flatMap { db.sceneCentroidDao().getForScene(it) }.map { it.sceneId + "|" + it.space }.toSet()
                assertEquals("forgetV2=$v2", if (wipe) survivors else (if (v2 && on != null) on else survivors) + iso, left)
            } finally { db.close() }
        }
    }

    // ---- rows go ---- (sNear = momA + momB, sMixed = momB + momFar)
    @Test fun photoForgetDeletesTheCentroidsOfTheScenesItChanged() = paths(emptySet()) { w.service.forgetMoment("fB") } // the radius reaches every photo of the world: both Scenes change
    @Test fun aFarPhotoForgetKeepsTheUntouchedScene() = paths(emptySet(), nearOnly) { w.service.forgetMoment("fFar") } 
    @Test fun rangeForgetDeletesTheChangedScenes() = paths(emptySet(), nearOnly) { w.fx.forget.forgetRange(w.tFar, w.tFar + 999) }
    @Test fun scopedMomentForgetDeletesTheChangedScenes() = paths(emptySet()) { w.fx.forget.forgetSpans(listOf(w.tA..w.tA + 999)) } // momA only: sNear shrinks, sMixed is untouched
    @Test fun sceneScopedForgetDeletesTheNamedScene() = paths(emptySet()) { w.fx.forget.forgetSpans(listOf(w.tA..w.tA + 999), listOf("sNear")) }
    @Test fun multiSelectDeletesTheChangedScenes() = paths(emptySet(), nearOnly) { w.fx.forget.forgetMoments(listOf(w.momFar)) }
    @Test fun momentForgetByIdDeletesTheChangedScenes() = paths(emptySet()) { w.fx.forget.forgetMoments(listOf(w.momA)) }
    @Test fun aFactForgetsLockedPhotoDeletesTheChangedScenes() = paths(emptySet()) { w.service.forgetFact("mA") }
    @Test fun deleteAllDeletesEveryRow() = paths(emptySet(), wipe = true) { w.db.wipeDao().wipeMemory(1_000) }
    @Test fun removingAScenesRowDeletesItsCentroids() = paths(setOf("sMixed|e1")) { w.db.sceneDao().remove(listOf("sNear")) }

    // ---- rows stay while the Scene row lives ----
    @Test fun photoExpiryKeepsTheRows() = paths(all) { w.service.expirePhotos(listOf("fA"), requireLinked = true) }
    @Test fun orphanExpiryKeepsTheRows() = paths(all) { w.service.expireOrphanFrames(listOf("fA")) }
    @Test fun removeDescriptionsKeepsTheRowsOfAShrunkScene() = paths(all) { w.service.removeDescriptions(listOf("mA")) }
    @Test fun theInvalidMomentPurgeKeepsTheRows() = paths(all) {
        w.db.withTransaction { w.db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(w.momA, 1))); w.db.momentDao().remove(listOf(w.momA)) }
    }
    /** A rebuild that empties a Scene deletes the Scene row, so its centroid goes with it; a Scene that only shrinks keeps its (stale) row. */
    @Test fun aMomentRebuildKeepsTheCentroidOnlyWhileTheSceneRowExists() = paths(setOf("sMixed|e1")) { w.db.momentDao().remove(listOf(w.momA, w.momB), step = "build") }

    @Test fun aShrunkSceneKeepsAStaleRowThatIsNeverCurrent() = runBlocking {
        ForgetV2.testOverride = true
        val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            w = GoldenWorld(db); w.seed(); w.seedCentroids()
            w.service.removeDescriptions(listOf("mA"))
            val c = db.sceneCentroidDao().get("sNear", "e1")!!
            assertTrue("the Scene's revision moved, the centroid is stale", c.membershipRev != db.sceneDao().scene("sNear")!!.membershipRev)
        } finally { db.close() }
    }
}
