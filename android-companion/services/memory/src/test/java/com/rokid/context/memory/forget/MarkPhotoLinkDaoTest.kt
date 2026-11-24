package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.memory.FrameIntake
import com.rokid.context.memory.FrameRows
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.6 slice C: a mark's photo is linked to its label (either arrival order), once, inside the frame commit; undo and Forget keep it consistent. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkPhotoLinkDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentForgetFixture(db)
    private val dao = db.canvasSlice3Dao()
    private val ref = "ep-1:7"
    private val mark = "mk:$ref"
    @After fun close() = db.close()

    private suspend fun insertMark(at: Long = MS + 100, photo: String? = MarkPhotoReason.PENDING, createdAt: Long = 1_000, withIntent: Boolean = true) {
        val e = DeletionEpoch("i", 1, 0); val f = DaoFence(0, e, { 0 }, { e })
        dao.insertLabelFenced(CanvasLabelEntity(mark, "MOMENT", "tap", "MOMENT", at, createdAt = createdAt, photo = photo), f)
        if (withIntent) dao.insertLabelFenced(CanvasLabelEntity("$mark:i", "INTENT", "tap", "buy", at, createdAt = createdAt, photo = photo), f)
    }
    private fun rows(id: String, captured: Long?, markRef: String?) = FrameRows(
        FrameMetaEntity(id, "w_$id", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = captured, markRef = markRef),
        FrameStateEntity(id, "PENDING", 0, null, null, null, 1),
    )
    private suspend fun commit(id: String, ts: Long, markRef: String?) = FrameIntake(db).commit(FrameEntity(id, ts, "/p/$id.jpg", "rokid_glasses"), rows(id, ts, markRef))

    @Test fun theMarkIsHereFirstTheFramePhotoCommitLinksItAndItsIntentInTheSameTransaction() = runBlocking<Unit> {
        insertMark()
        assertEquals(FrameIntake.Outcome.ADDED, commit("p1", MS + 1_500, ref))
        for (id in listOf(mark, "$mark:i")) dao.label(id)!!.let { assertEquals("p1", it.frameId); assertEquals(MarkPhotoReason.OK, it.photo) }
    }

    @Test fun thePhotoIsHereFirstTheMarkThatArrivesLaterFindsItAndLinks() = runBlocking<Unit> {
        assertEquals(FrameIntake.Outcome.ADDED, commit("p1", MS + 1_500, ref))
        insertMark()
        assertEquals("the photo's capture time, for the rebuild of its second", MS + 1_500, dao.linkMarkPhoto(mark))
        assertEquals("p1", dao.label(mark)!!.frameId); assertEquals("p1", dao.label("$mark:i")!!.frameId)
        assertNull("linking twice changes nothing", dao.linkMarkPhoto(mark))
    }

    @Test fun oneMarkOwnsOnePhotoASecondFrameWithTheSameReferenceStaysAnOrdinaryFrame() = runBlocking<Unit> {
        insertMark()
        commit("p1", MS + 1_500, ref); commit("p2", MS + 2_500, ref)
        assertEquals("p1", dao.label(mark)!!.frameId)
        assertEquals(2, db.frameDao().count())
    }

    @Test fun whenTwoPhotosArrivedBeforeTheMarkTheEarliestIsTheMarksPhoto() = runBlocking<Unit> {
        commit("p2", MS + 2_500, ref); commit("p1", MS + 1_500, ref)
        insertMark()
        assertEquals(MS + 1_500, dao.linkMarkPhoto(mark))
        assertEquals("p1", dao.label(mark)!!.frameId)
    }

    @Test fun aFrameWithNoMarkOrAMalformedReferenceLinksNothing() = runBlocking<Unit> {
        insertMark()
        commit("p1", MS + 1_500, null); commit("p2", MS + 1_600, "garbage")
        assertNull(dao.label(mark)!!.frameId); assertEquals(MarkPhotoReason.PENDING, dao.label(mark)!!.photo)
    }

    @Test fun aPhotoOfAnUndoneMarkIsRefusedInsideTheCommitAndItsJpegIsQueuedForDeletion() = runBlocking<Unit> {
        dao.putMeta("mark_undone:$mark", "1")
        assertEquals(FrameIntake.Outcome.FENCED, commit("late", MS + 1_500, ref))
        assertEquals(0, db.frameDao().count())
        assertTrue(fx.outbox().any { it.second.contains("late.jpg") })
    }

    @Test fun aFailureReasonIsStoredOnlyWhileThePhotoIsPending() = runBlocking<Unit> {
        insertMark()
        assertTrue(dao.markPhotoFailed(mark, MarkPhotoReason.CAMERA_BUSY))
        assertEquals(MarkPhotoReason.CAMERA_BUSY, dao.label(mark)!!.photo); assertEquals(MarkPhotoReason.CAMERA_BUSY, dao.label("$mark:i")!!.photo)
        assertFalse("a second report does not overwrite the first", dao.markPhotoFailed(mark, MarkPhotoReason.NO_FRAME))
        assertEquals(MarkPhotoReason.CAMERA_BUSY, dao.label(mark)!!.photo)
    }

    @Test fun aFailureNeverUndoesALinkedPhoto() = runBlocking<Unit> {
        insertMark(); commit("p1", MS + 1_500, ref)
        assertFalse(dao.markPhotoFailed(mark, MarkPhotoReason.NO_FRAME))
        assertEquals(MarkPhotoReason.OK, dao.label(mark)!!.photo)
    }

    @Test fun forgettingTheMarksPhotoRemovesTheMarkWithIt() = runBlocking<Unit> {
        insertMark(); commit("p1", MS + 1_500, ref)
        fx.service.forgetMoment("p1")
        assertNull(dao.label(mark)); assertNull(dao.label("$mark:i"))
        assertEquals(0, db.frameDao().count())
    }

    @Test fun theMomentSecondOfALinkedMarkIsItsPhotosSecondNotItsOwn() = runBlocking<Unit> {
        insertMark(at = MS + 900); commit("p1", MS + 1_500, ref)
        assertTrue("not in the mark's own second", dao.momentMarksBetween(MS, MS + 999, 0).isEmpty())
        assertEquals(listOf(mark, "$mark:i"), dao.momentMarksBetween(MS + 1_000, MS + 1_999, 0).map { it.labelId })
    }

    @Test fun anUnlinkedMarkStaysInItsOwnSecondAndSoDoesAMarkFromBeforeThePhotoStateExisted() = runBlocking<Unit> {
        insertMark(photo = MarkPhotoReason.NO_PHOTO_SUPPORT, withIntent = false)
        assertEquals(listOf(mark), dao.momentMarksBetween(MS, MS + 999, 0).map { it.labelId })
        val e = DeletionEpoch("i", 1, 0)
        dao.insertLabelFenced(CanvasLabelEntity("old", "MOMENT", "tap", "MOMENT", MS + 200, createdAt = 1, photo = null), DaoFence(0, e, { 0 }, { e }))
        assertEquals("a null photo state is a mark from before v15: it stays", listOf("old", mark), dao.momentMarksBetween(MS, MS + 999, 0).map { it.labelId }.sorted().reversed().sortedBy { it != "old" })
    }

    @Test fun aPendingMarkIsHeldBackUntilItsWaitRanOutThenItIsAFallback() = runBlocking<Unit> {
        insertMark(createdAt = 100_000)
        assertTrue("still waiting for its photo", dao.momentMarksBetween(MS, MS + 999, pendingSince = 50_000).isEmpty())
        assertEquals(listOf(mark, "$mark:i"), dao.momentMarksBetween(MS, MS + 999, pendingSince = 100_001).map { it.labelId })
    }

    /** Retention r1: expiry is storage only, so the user's mark stays when its photo expires; with no photo row it is not a bare Moment (the safety net below). */
    @Test fun whenThePhotoExpiresTheMarkStaysAndMakesNoBareMoment() = runBlocking<Unit> {
        insertMark(); commit("p1", MS + 100, ref)
        fx.service.expirePhotos(listOf("p1"))
        assertTrue(dao.label(mark) != null); assertTrue(dao.momentMarksBetween(MS, MS + 999, 0).isEmpty())
    }

    /** The safety net behind that: a mark that says `ok` while its photo row is gone (any other path) makes no Moment either. */
    @Test fun aMarkWhoseLinkedPhotoRowIsGoneMakesNoMomentAtAll() = runBlocking<Unit> {
        insertMark(); commit("p1", MS + 100, ref)
        db.forgetDao().deleteFrame("p1")
        assertTrue(dao.momentMarksBetween(MS, MS + 999, 0).isEmpty())
        assertEquals(MarkPhotoReason.OK, dao.label(mark)!!.photo)
    }

    /** Codex recheck: the allowance of a mark is claimed DURABLY by one frame id, so neither an in-memory eviction nor a restart can hand it to a second frame. */
    @Test fun aMarkPhotoAllowanceIsClaimedOnceDurablyByOneFrameId() = runBlocking<Unit> {
        insertMark()
        assertTrue(dao.claimMarkPhoto(mark, "f1"))
        assertTrue("the same frame again (a resend)", dao.claimMarkPhoto(mark, "f1"))
        assertFalse("another frame id is refused", dao.claimMarkPhoto(mark, "f2"))
        assertEquals("f1", dao.meta("mark_claim:$mark"))
    }

    /** Codex round 3: the admission read the mark OPEN, then undo / Forget / Delete all removed it; the claim must refuse and write nothing (no parentless ids). */
    @Test fun aClaimOnAMarkDeletedAfterTheAdmissionReadIsRefusedAndLeavesNoRow() = runBlocking<Unit> {
        insertMark()
        dao.deleteLabel(mark); dao.deleteLabel("$mark:i")
        assertFalse(dao.claimMarkPhoto(mark, "f1"))
        assertNull(dao.meta("mark_claim:$mark"))
        assertFalse("never existed", dao.claimMarkPhoto("mk:nobody:1", "f1")); assertNull(dao.meta("mark_claim:mk:nobody:1"))
    }

    @Test fun aMarkThatAlreadyHasItsPhotoOrALostOneTakesNoClaim() = runBlocking<Unit> {
        insertMark(); commit("p1", MS + 1_500, ref)
        assertFalse(dao.claimMarkPhoto(mark, "f2")); assertNull(dao.meta("mark_claim:$mark"))
        val m2 = "mk:ep-1:8"
        val e = DeletionEpoch("i", 1, 0)
        dao.insertLabelFenced(CanvasLabelEntity(m2, "MOMENT", "tap", "MOMENT", MS + 5, createdAt = 1, photo = MarkPhotoReason.CAMERA_BUSY), DaoFence(0, e, { 0 }, { e }))
        assertFalse(dao.claimMarkPhoto(m2, "f3")); assertNull(dao.meta("mark_claim:$m2"))
    }

    @Test fun aClaimGoesWithItsMarkUndoForgetRangeAndDeleteAll() = runBlocking<Unit> {
        insertMark(); dao.claimMarkPhoto(mark, "f1")
        dao.deleteLabel(mark); dao.deleteLabel("$mark:i")
        assertNull("undo", dao.meta("mark_claim:$mark"))
        insertMark(at = MS + 100); dao.claimMarkPhoto(mark, "f1")
        dao.deleteLabelsBetween(MS, MS + 999)
        assertNull("range forget", dao.meta("mark_claim:$mark"))
        insertMark(at = MS + 100); dao.claimMarkPhoto(mark, "f1")
        db.wipeDao().wipeMemory(1_000)
        assertNull("delete all", dao.meta("mark_claim:$mark"))
    }

    @Test fun deleteAllRemovesTheUndoMarkers() = runBlocking<Unit> {
        dao.putMeta("mark_undone:$mark", "1")
        db.wipeDao().wipeMemory(1_000)
        assertNull(dao.meta("mark_undone:$mark"))
    }

    @Test fun deleteAllRemovesTheCleanupOwedKeys() = runBlocking<Unit> {
        dao.putMeta("mark_cleanup_owed:$mark", "f1"); dao.putMeta("keepme", "x")
        db.wipeDao().wipeMemory(1_000)
        assertNull("delete all", dao.meta("mark_cleanup_owed:$mark")); assertEquals("x", dao.meta("keepme"))
    }
}
