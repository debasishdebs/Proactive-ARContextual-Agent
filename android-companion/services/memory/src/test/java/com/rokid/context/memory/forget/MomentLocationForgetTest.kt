package com.rokid.context.memory.forget

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentTombstoneEntity
import com.rokid.context.memory.forget.lifecycle.ForgetExecutor
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v18 WP2: the `moment_location` rows leave ONLY with a user Forget (photo, photo radius, range, scoped Moment / Scene, multi-select, a fact Forget's locked photo, mark delete, mark undo, Delete all) and are KEPT by
 * photo expiry, orphan expiry, `removeDescriptions`, the invalid-Moment purge and a Moment rebuild (`MomentDao.remove(step = "build")`). Each path runs on the [GoldenWorld] (rows of [GoldenWorld.seedLocations]) with
 * `forgetV2` OFF and ON, and asserts the Moment ids of the rows that SURVIVE.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentLocationForgetTest {
    private lateinit var w: GoldenWorld
    @After fun close() { ForgetV2.testOverride = null }

    private fun paths(survivors: GoldenWorld.() -> Set<String>, on: (GoldenWorld.() -> Set<String>)? = null, op: suspend () -> Unit) = runBlocking {
        for (v2 in listOf(false, true)) {
            ForgetV2.testOverride = v2
            val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
            try {
                w = GoldenWorld(db); w.seed(); w.seedLocations()
                op()
                val left = db.momentLocationDao().of(w.allIds()).map { it.momentId }.toSet()
                assertEquals("forgetV2=$v2", if (v2 && on != null) w.on() else w.survivors(), left)
            } finally { db.close() }
        }
    }

    private fun GoldenWorld.allIds() = setOf(momA, momB, momFar, momLone, momLone2)

    // ---- rows go ---- (flag OFF locks the photo of a multi-photo fact too, so its row goes; ON never locks it: the golden world's F5)
    @Test fun photoForgetDeletesItsRowAndTheRadius() = paths({ setOf(momFar, momLone, momLone2) }) { w.service.forgetMoment("fB") }
    @Test fun rangeForgetDeletesTheRowsInTheRange() = paths({ setOf(momLone, momLone2) }, { setOf(momFar, momLone, momLone2) }) { w.fx.forget.forgetRange(w.tA, w.tB) }
    @Test fun scopedMomentForgetDeletesTheRowsOfItsSeconds() = paths({ allIds() - momA }) { w.fx.forget.forgetSpans(listOf(w.tA..w.tA + 999)) }
    @Test fun sceneScopedForgetDeletesTheRowsOfItsSpan() = paths({ setOf(momFar, momLone, momLone2) }) { w.fx.forget.forgetSpans(listOf(w.tA..w.tB + 999), listOf("sNear")) }
    @Test fun multiSelectDeletesTheRowsOfItsMoments() = paths({ setOf(momLone, momLone2) }, { setOf(momFar, momLone, momLone2) }) { w.fx.forget.forgetMoments(listOf(w.momA, w.momB)) }
    @Test fun momentForgetByIdDeletesItsRow() = paths({ setOf(momLone, momLone2) }, { setOf(momFar, momLone, momLone2) }) { w.fx.forget.forgetMoments(listOf(w.momA)) }
    @Test fun aFactForgetsLockedPhotoDeletesItsRow() = paths({ setOf(momFar, momLone, momLone2) }) { w.service.forgetFact("mA") }
    @Test fun markUndoDeletesTheMarksRow() = paths({ allIds() - momLone2 }) { if (ForgetV2.on) ForgetExecutor.deleteMarks(w.db, listOf("mkLone2")) else w.db.canvasSlice3Dao().deleteLabel("mkLone2") }
    @Test fun markDeleteDeletesTheMarksRow() = paths({ allIds() - momLone }) { w.db.canvasSlice3Dao().deleteLabel("mkLone") }
    @Test fun deleteAllDeletesEveryRow() = paths({ emptySet() }) { w.db.wipeDao().wipeMemory(1_000) }

    // ---- rows stay ----
    @Test fun photoExpiryKeepsTheRows() = paths({ allIds() }) { w.service.expirePhotos(listOf("fA"), requireLinked = true) }
    @Test fun orphanExpiryKeepsTheRows() = paths({ allIds() }) { w.service.expireOrphanFrames(listOf("fA")) }
    @Test fun removeDescriptionsKeepsTheRows() = paths({ allIds() }) { w.service.removeDescriptions(listOf("mA")) }
    @Test fun aMomentRebuildKeepsTheRows() = paths({ allIds() }) { w.db.momentDao().remove(listOf(w.momA, w.momB), step = "build") }
    /** The `InvalidMomentPurge` transaction (orchestration module) replayed at its memory-level calls: a tombstone per invalid second, then `MomentDao.remove`. */
    @Test fun theInvalidMomentPurgeKeepsTheRows() = paths({ allIds() }) {
        w.db.withTransaction { w.db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(w.momA, 1))); w.db.momentDao().remove(listOf(w.momA)) }
    }
}
