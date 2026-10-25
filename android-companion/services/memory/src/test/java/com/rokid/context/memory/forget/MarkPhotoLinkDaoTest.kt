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
        assertTrue(dao.linkMarkPhoto(mark))
        assertEquals("p1", dao.label(mark)!!.frameId); assertEquals("p1", dao.label("$mark:i")!!.frameId)
        assertFalse("linking twice changes nothing", dao.linkMarkPhoto(mark))
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
        assertTrue(dao.linkMarkPhoto(mark))
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

    @Test fun anUnlinkedMarkStaysInItsOwnSecondAndAnOldLabelWithNoStateToo() = runBlocking<Unit> {
        insertMark(photo = MarkPhotoReason.NO_PHOTO_SUPPORT, withIntent = false)
        assertEquals(listOf(mark), dao.momentMarksBetween(MS, MS + 999, 0).map { it.labelId })
        dao.markPhotoFailed(mark, "x") // not pending: untouched
        db.canvasSlice3Dao().label(mark)
    }

    @Test fun aPendingMarkIsHeldBackUntilItsWaitRanOutThenItIsAFallback() = runBlocking<Unit> {
        insertMark(createdAt = 100_000)
        assertTrue("still waiting for its photo", dao.momentMarksBetween(MS, MS + 999, pendingSince = 50_000).isEmpty())
        assertEquals(listOf(mark, "$mark:i"), dao.momentMarksBetween(MS, MS + 999, pendingSince = 100_001).map { it.labelId })
    }

    /** Retention expiry already deletes the labels of an expired photo (existing lineage): the mark goes with its photo, it never becomes a bare Moment. */
    @Test fun whenThePhotoExpiresTheMarkGoesWithItAndNoMomentRemains() = runBlocking<Unit> {
        insertMark(); commit("p1", MS + 100, ref)
        fx.service.expirePhotos(listOf("p1"))
        assertNull(dao.label(mark)); assertTrue(dao.momentMarksBetween(MS, MS + 999, 0).isEmpty())
    }

    /** The safety net behind that: a mark that says `ok` while its photo row is gone (any other path) makes no Moment either. */
    @Test fun aMarkWhoseLinkedPhotoRowIsGoneMakesNoMomentAtAll() = runBlocking<Unit> {
        insertMark(); commit("p1", MS + 100, ref)
        db.forgetDao().deleteFrame("p1")
        assertTrue(dao.momentMarksBetween(MS, MS + 999, 0).isEmpty())
        assertEquals(MarkPhotoReason.OK, dao.label(mark)!!.photo)
    }

    @Test fun deleteAllRemovesTheUndoMarkers() = runBlocking<Unit> {
        dao.putMeta("mark_undone:$mark", "1")
        db.wipeDao().wipeMemory(1_000)
        assertNull(dao.meta("mark_undone:$mark"))
    }
}
