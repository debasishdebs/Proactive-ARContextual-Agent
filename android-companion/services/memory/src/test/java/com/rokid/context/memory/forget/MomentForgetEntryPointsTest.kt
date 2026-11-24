package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.FrameIntake
import com.rokid.context.memory.FrameRows
import com.rokid.context.memory.db.CleanupOutboxEntity
import com.rokid.context.memory.db.CleanupTarget
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameState
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The existing Forget entry points clean the projection; late frames are fenced; Delete all keeps unacknowledged cleanup. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentForgetEntryPointsTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentForgetFixture(db)
    @After fun close() = db.close()

    private suspend fun projected() { fx.frame("a", MS + 5); fx.memory("m1", "a"); fx.mark("L1", MS + 500); fx.project(fx.id, MomentPartKind.FRAME to "a", MomentPartKind.MEMORY to "m1", MomentPartKind.MARK to "L1") }

    // --- source-to-projection cleanup at the existing entry points ---
    @Test fun forgetMomentOfAPhotoDropsItsStoredMoment() = runBlocking<Unit> { projected(); fx.service.forgetMoment("a"); assertTrue(db.momentDao().allMoments().isEmpty()) }
    @Test fun forgetFactDropsTheMomentsHoldingTheMemory() = runBlocking<Unit> { projected(); fx.service.forgetFact("m1"); assertTrue(db.momentDao().allMoments().isEmpty()) }
    /** Retention r1 (P1-1): photo expiry is storage only; the stored Moment stays (with its FRAME part, shown as "Photo expired"). */
    @Test fun photoRetentionExpiryKeepsTheMoment() = runBlocking<Unit> { projected(); fx.service.expirePhotos(listOf("a")); assertEquals(1, db.momentDao().allMoments().size) }
    @Test fun aCanvasRangeForgetDropsTheMomentsOfThatTime() = runBlocking<Unit> {
        projected(); CanvasSlice3Forget(db).forgetRange(MS, MS + 999)
        assertTrue(db.momentDao().allMoments().isEmpty())
    }
    /** A projection row far away in time (stale) that still names the source: only the by-reference cleanup can remove it. */
    private val far = com.rokid.context.memory.moment.MomentId.format(MomentForgetFixture.SEC + 5000)
    @Test fun aStaleMomentNamingAForgottenPhotoIsDropped() = runBlocking<Unit> { fx.frame("a", MS + 5); fx.project(far, MomentPartKind.FRAME to "a"); fx.service.forgetMoment("a"); assertTrue(db.momentDao().allMoments().isEmpty()) }
    @Test fun aStaleMomentNamingAForgottenMemoryIsDropped() = runBlocking<Unit> { fx.frame("a", MS + 5); fx.memory("m1", "a"); fx.project(far, MomentPartKind.MEMORY to "m1"); fx.service.forgetFact("m1"); assertTrue(db.momentDao().allMoments().isEmpty()) }
    @Test fun aMomentNamingAnExpiredPhotoIsKept() = runBlocking<Unit> { fx.frame("a", MS + 5); fx.project(far, MomentPartKind.FRAME to "a"); fx.service.expirePhotos(listOf("a")); assertEquals(1, db.momentDao().allMoments().size) }
    @Test fun aNeighbourMomentOutsideEverythingIsKept() = runBlocking<Unit> {
        projected(); fx.frame("z", MS + 500_000); fx.project(com.rokid.context.memory.moment.MomentId.format(MomentForgetFixture.SEC + 500), MomentPartKind.FRAME to "z")
        fx.service.forgetMoment("a")
        assertEquals(1, db.momentDao().allMoments().size)
    }

    // --- late source admission ---
    private fun rows(id: String, captured: Long?) = FrameRows(FrameMetaEntity(id, "w_$id", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = captured), FrameStateEntity(id, FrameState.PENDING.name, 0, null, null, null, 1))

    @Test fun aFrameArrivingAfterAMomentForgetIsRefusedInsideTheCommitAndItsJpegIsQueuedForDeletion() = runBlocking<Unit> {
        fx.forget.forgetMoments(listOf(fx.id))
        val late = FrameEntity("late", MS + 10, "/p/late.jpg", "rokid_glasses")
        assertEquals(FrameIntake.Outcome.FENCED, FrameIntake(db).commit(late, rows("late", MS + 10)))
        assertEquals(0, db.frameDao().count()); assertEquals(null, db.frameMetaDao().byWireId("w_late"))
        assertTrue(fx.outbox().contains(CleanupTarget.PHONE_FILE to "{\"path\":\"/p/late.jpg\"}")); assertTrue(fx.outbox().contains(CleanupTarget.PHONE_FILE to "{\"path\":\"/p/late.meta.json\"}"))
    }

    @Test fun aFrameWhoseCaptureTimeIsInsideTheFenceIsRefusedEvenWithAnOutsideFileTime() = runBlocking<Unit> {
        fx.forget.forgetMoments(listOf(fx.id))
        assertEquals(FrameIntake.Outcome.FENCED, FrameIntake(db).commit(FrameEntity("late", MS + 90_000, "/p/late.jpg", "rokid_glasses"), rows("late", MS + 10)))
    }

    @Test fun aFrameOutsideTheFenceIsAdmittedAndSoIsABurstNeighbourOfAForgottenPhoto() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.forget.forgetMoments(listOf(fx.id)) // the +-10 s photo link widening is NOT a Moment fence
        assertEquals(FrameIntake.Outcome.ADDED, FrameIntake(db).commit(FrameEntity("ok", MS + 3000, "/p/ok.jpg", "rokid_glasses"), rows("ok", MS + 3000)))
        assertEquals(FrameIntake.Outcome.ADDED, FrameIntake(db).commit(FrameEntity("ok2", MS + 1000, "/p/ok2.jpg", "rokid_glasses"), rows("ok2", MS + 1000)))
    }

    @Test fun aTombstonedSecondRefusesAFrameEvenWithoutARangeFence() = runBlocking<Unit> {
        db.momentDao().insertTombstones(listOf(com.rokid.context.memory.db.MomentTombstoneEntity(fx.id, 1)))
        assertEquals(FrameIntake.Outcome.FENCED, FrameIntake(db).commit(FrameEntity("late", MS + 10, "/p/late.jpg", "rokid_glasses"), rows("late", null)))
    }

    @Test fun aDelayedFrameOfUnknownCaptureTimeWithAFileTimeOutsideTheForgottenIntervalIsRefused() = runBlocking<Unit> {
        fx.forget.forgetMoments(listOf(fx.id))
        val delayed = FrameEntity("delayed", MS + 600_000, "/p/delayed.jpg", "rokid_glasses") // file time far outside, capture time unknown
        assertEquals(FrameIntake.Outcome.FENCED, FrameIntake(db) { 2_000 }.commit(delayed, rows("delayed", null)))
        assertEquals(0, db.frameDao().count()); assertTrue(fx.outbox().contains(CleanupTarget.PHONE_FILE to "{\"path\":\"/p/delayed.jpg\"}"))
    }

    @Test fun aLongRangeForgetStillLeavesARecencyMarkerForUnknownTimeFrames() = runBlocking<Unit> {
        fx.forget.forgetRange(MS, MS + 100_000_000)
        assertEquals(FrameIntake.Outcome.FENCED, FrameIntake(db) { 2_000 }.commit(FrameEntity("d", MS + 999_000_000, "/p/d.jpg", "rokid_glasses"), rows("d", null)))
    }

    @Test fun unknownTimeFramesAreAdmittedAgainWhenNoRecentForgetExists() = runBlocking<Unit> {
        fx.forget.forgetMoments(listOf(fx.id))
        val day = 25L * 3600 * 1000
        assertEquals(FrameIntake.Outcome.ADDED, FrameIntake(db) { 1_000 + day }.commit(FrameEntity("later", MS + 600_000, "/p/later.jpg", "rokid_glasses"), rows("later", null)))
    }

    /** A Forget repeated more than 24 h later must refresh the recency marker, or an unknown-capture-time frame slips in right after it. */
    private suspend fun repeatedAfterADay(forgetIt: suspend (MomentForget) -> Unit) {
        var now = 1_000L
        val f = MomentForget(db, fx.service) { now }
        forgetIt(f); now += 25L * 3600 * 1000; forgetIt(f)
        val late = FrameEntity("d", MS + 999_000_000, "/p/d.jpg", "rokid_glasses") // file time far outside the range, capture time unknown
        assertEquals(FrameIntake.Outcome.FENCED, FrameIntake(db) { now + 1_000 }.commit(late, rows("d", null)))
    }
    @Test fun aRepeatedLongRangeForgetRefreshesTheRecencyMarker() = runBlocking<Unit> { repeatedAfterADay { it.forgetRange(MS, MS + 100_000_000) } }
    @Test fun aRepeatedSingleSecondForgetRefreshesTheRecencyMarker() = runBlocking<Unit> { repeatedAfterADay { it.forgetMoments(listOf(fx.id)) } }

    // --- Delete all ---
    @Test fun deleteAllWipesTheProjectionAndFencesButKeepsUnackedCleanupAndAddsItsOwn() = runBlocking<Unit> {
        projected(); fx.forget.forgetMoments(listOf(fx.id)); fx.project(fx.id, MomentPartKind.FRAME to "a")
        val pendingBefore = db.momentDao().pendingOutbox().size
        assertTrue(pendingBefore > 0)
        db.momentDao().insertOutbox(listOf(CleanupOutboxEntity(target = CleanupTarget.PHONE_FILE, payloadJson = "{\"path\":\"/acked\"}", createdAt = 1, ackedAt = 5)))
        db.wipeDao().wipeMemory(50)
        assertTrue(db.momentDao().allMoments().isEmpty()); assertEquals(0, db.momentDao().tombstoneCount()); assertTrue(db.canvasSlice3Dao().momentForgetRanges().isEmpty())
        val box = db.momentDao().pendingOutbox()
        assertEquals("every unacked row survives, plus the device-wide command", pendingBefore + 1, box.size)
        assertTrue(box.any { it.target == CleanupTarget.ALL })
        assertEquals("the acked row is gone", 0, box.count { it.payloadJson.contains("acked") })
        assertEquals(pendingBefore + 1, db.momentDao().outboxCount())
    }

    @Test fun wipeAcknowledgeCaptureWipeLeavesAPendingAllCommand() = runBlocking<Unit> {
        db.wipeDao().wipeMemory(1)
        val first = db.momentDao().pendingOutbox().single { it.target == CleanupTarget.ALL }
        assertEquals(1, db.momentDao().ack(first.id, 2))
        fx.frame("a", MS + 5)
        db.wipeDao().wipeMemory(3)
        assertEquals("a second wipe must queue its own device-wide command", 1, db.momentDao().pendingOutbox().count { it.target == CleanupTarget.ALL })
    }

    @Test fun deleteAllTwiceDoesNotDuplicateTheDeviceWideCommand() = runBlocking<Unit> {
        db.wipeDao().wipeMemory(1); db.wipeDao().wipeMemory(2)
        assertEquals(1, db.momentDao().pendingOutbox().count { it.target == CleanupTarget.ALL })
    }
}
