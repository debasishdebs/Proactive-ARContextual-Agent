package com.rokid.context.canvas.marks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.core.model.MarkPhotoResult
import com.rokid.context.memory.FrameIntake
import com.rokid.context.memory.FrameRows
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.6 slice C: the phone side of a mark that touches capture (photo state, link in either order, the failure report, undo and voice undo with the photo). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkPhotoRecorderTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val epoch = DeletionEpoch("inst", 2, 1_000)
    private lateinit var dao: com.rokid.context.memory.db.CanvasSlice3Dao
    private lateinit var db: com.rokid.context.memory.db.ContextDatabase
    private lateinit var rec: MarkRecorder
    private val t0 = 1_761_000_000_000L
    private val recorded = ArrayList<Long>()
    private val changed = ArrayList<Long>()
    private val forgotten = ArrayList<String>()
    private var forgetOk = true

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        db = MemoryGraph.get(ctx).db; dao = db.canvasSlice3Dao()
        rec = MarkRecorder(dao, { DaoFence(0, epoch, { 0 }, { epoch }) }, { t0 }, {}, { recorded += it }, { changed += it }, forgetPhoto = { forgotten += it; if (forgetOk) db.forgetDao().let { _ -> com.rokid.context.memory.forget.ForgetService(db, com.rokid.context.memory.ProposalRepository(db.proposalDao(), db.actionDao(), db)) { t0 }.forgetMoment(it) }; forgetOk })
    }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    private fun mark(seq: Long, photo: Boolean = true, undo: Long? = null) = MarkMessage(epoch = "e", seq = seq, atMs = 5, atElapsedMs = 5, listen = true, undo = undo, deletionCounter = 2, photo = photo)
    private fun at(ms: Long) = MarkArrival(ms, mapped = true, receivedAtMs = ms)
    private fun commitPhoto(id: String, ts: Long, ref: String?) = runBlocking {
        val meta = FrameMetaEntity(id, "w_$id", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = ts, markRef = ref)
        FrameIntake(db).commit(FrameEntity(id, ts, "/p/$id.jpg", "rokid_glasses"), FrameRows(meta, FrameStateEntity(id, "PENDING", 0, null, null, null, 1)))
    }

    @Test fun aMarkThatWillTakeAPhotoWaitsForItAndAnOldGlassesMarkIsAFallbackAtOnce() = runBlocking<Unit> {
        rec.onMark(mark(1, photo = true), at(t0)); rec.onMark(mark(2, photo = false), at(t0 + 5_000))
        assertEquals(MarkPhotoReason.PENDING, dao.label("mk:e:1")!!.photo)
        assertEquals(MarkPhotoReason.NO_PHOTO_SUPPORT, dao.label("mk:e:2")!!.photo)
    }

    @Test fun aPhotoThatArrivedBeforeItsMarkIsLinkedWhenTheMarkIsRecordedAndBothSecondsAreRebuilt() = runBlocking<Unit> {
        commitPhoto("p1", t0 + 1_400, "e:1")
        rec.onMark(mark(1), at(t0 + 900))
        assertEquals("p1", dao.label("mk:e:1")!!.frameId); assertEquals(MarkPhotoReason.OK, dao.label("mk:e:1")!!.photo)
        assertEquals(listOf(t0 + 900, t0 + 1_400), recorded)
    }

    @Test fun aMarkRecordedAgainAfterItsPhotoWasLinkedChangesNothing() = runBlocking<Unit> {
        rec.onMark(mark(1), at(t0)); commitPhoto("p1", t0 + 1_400, "e:1")
        recorded.clear()
        rec.onMark(mark(1), at(t0 + 50))
        assertEquals("p1", dao.label("mk:e:1")!!.frameId); assertTrue(recorded.isEmpty())
    }

    @Test fun aReportedFailureIsStoredAndTheSecondOfTheMarkIsRebuilt() = runBlocking<Unit> {
        rec.onMark(mark(1), at(t0)); recorded.clear()
        assertTrue(rec.onPhotoResult(MarkPhotoResult(epoch = "e", seq = 1, reason = "camera_busy")))
        assertEquals(MarkPhotoReason.CAMERA_BUSY, dao.label("mk:e:1")!!.photo); assertEquals(listOf(t0), recorded)
    }

    @Test fun aWildReasonIsCutToAShortTokenAndAReportForAnUnknownMarkIsNothing() = runBlocking<Unit> {
        rec.onMark(mark(1), at(t0))
        rec.onPhotoResult(MarkPhotoResult(epoch = "e", seq = 1, reason = "some <script> long ".repeat(10)))
        assertTrue(dao.label("mk:e:1")!!.photo!!.length <= 32)
        assertFalse(rec.onPhotoResult(MarkPhotoResult(epoch = "e", seq = 99, reason = "camera_busy")))
    }

    @Test fun aFailureReportNeverUndoesALinkedPhoto() = runBlocking<Unit> {
        rec.onMark(mark(1), at(t0)); commitPhoto("p1", t0 + 1_400, "e:1")
        assertFalse(rec.onPhotoResult(MarkPhotoResult(epoch = "e", seq = 1, reason = "no_frame")))
        assertEquals(MarkPhotoReason.OK, dao.label("mk:e:1")!!.photo)
    }

    @Test fun undoingAMarkForgetsItsPhotoThroughTheForgetPathAndRemovesTheMark() = runBlocking<Unit> {
        rec.onMark(mark(1), at(t0)); commitPhoto("p1", t0 + 1_400, "e:1")
        assertTrue(rec.onMark(mark(2, photo = false, undo = 1), at(t0 + 3_000)))
        assertEquals(listOf("p1"), forgotten)
        assertNull(dao.label("mk:e:1")); assertNull(dao.label("mk:e:1:i")); assertEquals(0, db.frameDao().count())
        assertNull("the photo is forgotten, no marker needed", dao.meta("mark_undone:mk:e:1"))
    }

    @Test fun aRefusedPhotoForgetMeansTheUndoIsNotAckedAndNothingIsRemoved() = runBlocking<Unit> {
        rec.onMark(mark(1), at(t0)); commitPhoto("p1", t0 + 1_400, "e:1")
        forgetOk = false
        try { rec.onMark(mark(2, photo = false, undo = 1), at(t0 + 3_000)); org.junit.Assert.fail("must throw so the glasses resend") } catch (e: IllegalStateException) { }
        assertNotNull(dao.label("mk:e:1")); assertEquals(1, db.frameDao().count())
        forgetOk = true
        assertTrue(rec.onMark(mark(2, photo = false, undo = 1), at(t0 + 4_000)))
        assertNull(dao.label("mk:e:1")); assertEquals(0, db.frameDao().count())
    }

    @Test fun undoingAMarkWhoseSecondaryPhotoIsStillOnItsWayRefusesThePhotoWhenItArrives() = runBlocking<Unit> {
        rec.onMark(mark(1), at(t0))
        assertTrue(rec.onMark(mark(2, photo = false, undo = 1), at(t0 + 800)))
        assertNull(dao.label("mk:e:1")); assertTrue(forgotten.isEmpty())
        assertNotNull("the marker that fences the late photo", dao.meta("mark_undone:mk:e:1"))
        assertEquals(FrameIntake.Outcome.FENCED, commitPhoto("late", t0 + 1_400, "e:1"))
        assertEquals(0, db.frameDao().count())
    }

    @Test fun aVoiceUndoForgetsThePhotoOfTheBoundMarkToo() = runBlocking<Unit> {
        rec.onListenStart(t0)
        rec.onMark(mark(1), at(t0 + 100)); commitPhoto("p1", t0 + 1_400, "e:1")
        rec.applyVoice(listOf(VoiceMark.Mark("buy"), VoiceMark.Undo), glasses = true, nowMs = t0 + 2_000)
        assertEquals(listOf("p1"), forgotten)
        assertTrue(dao.labels().isEmpty()); assertEquals(0, db.frameDao().count())
    }

    @Test fun theIntentOfAVoiceMarkFollowsItsMarksPhotoAndState() = runBlocking<Unit> {
        rec.onListenStart(t0); rec.onMark(mark(1), at(t0 + 100)); commitPhoto("p1", t0 + 1_400, "e:1")
        rec.applyVoice(listOf(VoiceMark.Mark("buy")), glasses = true, nowMs = t0 + 2_000)
        val i = dao.label("mk:e:1:i")!!
        assertEquals("p1", i.frameId); assertEquals(MarkPhotoReason.OK, i.photo)
    }

    @Test fun aMarkWithNoGlassesPhotoPossibleHasItsReasonFromTheStart() = runBlocking<Unit> {
        rec.applyVoice(listOf(VoiceMark.Mark(null)), glasses = false, nowMs = t0)
        assertEquals(MarkPhotoReason.PHONE_MARK, dao.labels().single().photo)
        rec.applyVoice(listOf(VoiceMark.Retro(null)), glasses = true, nowMs = t0 + 30_000)
        assertEquals(MarkPhotoReason.RETRO, dao.labels().single { it.source == "retro" }.photo)
    }

    /** The gate semantics: a mark and its photo recorded while an export runs are neither blocked nor lost (only deletes wait for the gate). */
    @Test fun aMarkAndItsPhotoDuringAnExportAreRecordedAndLinkedWithoutWaiting() = runBlocking<Unit> {
        com.rokid.context.orchestration.DataOpGate.shared.export {
            kotlinx.coroutines.withTimeout(5_000) {
                assertTrue(rec.onMark(mark(1), at(t0)))
                commitPhoto("p1", t0 + 1_400, "e:1")
            }
        }
        assertEquals("p1", dao.label("mk:e:1")!!.frameId)
    }

    /** Forget Moment of the PHOTO's second removes the mark too, though the mark was taken one second earlier (the existing frame lineage). */
    @Test fun forgettingTheMomentOfThePhotoRemovesTheMarkAndItsIntent() = runBlocking<Unit> {
        rec.onListenStart(t0); rec.onMark(mark(1), at(t0 + 100)); commitPhoto("p1", t0 + 1_400, "e:1")
        rec.applyVoice(listOf(VoiceMark.Mark("buy")), glasses = true, nowMs = t0 + 2_000)
        val svc = com.rokid.context.memory.forget.ForgetService(db, com.rokid.context.memory.ProposalRepository(db.proposalDao(), db.actionDao(), db)) { t0 }
        com.rokid.context.memory.forget.MomentForget(db, svc) { t0 }.forgetMoments(listOf(com.rokid.context.memory.moment.MomentId.ofMs(t0 + 1_400)))
        assertTrue(dao.labels().isEmpty()); assertEquals(0, db.frameDao().count())
    }
}
