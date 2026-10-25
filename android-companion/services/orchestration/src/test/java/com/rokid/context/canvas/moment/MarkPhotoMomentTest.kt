package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.moment.MomentValidity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v0.9.6 slice C: what a mark that touches capture builds. A mark whose photo arrived is in the PHOTO's Moment (the photo's second, whatever second the mark was taken in); a mark that could not take
 * its photo is the explicit FALLBACK mark-only Moment with its error reason; a mark still waiting for its photo is no Moment yet.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkPhotoMomentTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private var now = 10_000_000L
    private val builder = MomentBuilder(db, enabled = { true }, clock = { now })
    private val md = db.momentDao()
    private fun id(i: Long = 0) = MomentId.format(SEC + i)
    private val ref = "ep:3"
    @After fun close() = db.close()

    @Test fun theMarkOfAPhotoIsInThePhotosMomentEvenWhenTheMarkWasTakenInAnEarlierSecond() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900, photo = MarkPhotoReason.PENDING, createdAt = now); fx.mark("mk:$ref:i", MS + 900, kind = "INTENT", photo = MarkPhotoReason.PENDING, createdAt = now)
        fx.markPhoto("p1", MS + 1_400, ref)
        builder.build(listOf(id(0), id(1)))
        assertNull("the mark's own second has no Moment", md.moment(id(0)))
        val m = md.moment(id(1))!!
        assertEquals(MomentType.FRAME, m.momentType); assertNull(m.errorReason); assertNull("the transcript stays a null scaffold", m.transcript)
        assertEquals(setOf(MomentPartKind.FRAME to "p1", MomentPartKind.MARK to "mk:$ref", MomentPartKind.MARK to "mk:$ref:i"), md.parts(id(1)).map { it.kind to it.refId }.toSet())
    }

    @Test fun thePhotosTextLineageIsTheUsualOne() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900, photo = MarkPhotoReason.PENDING, createdAt = now)
        fx.markPhoto("p1", MS + 1_400, ref); fx.memory("m1", "p1", text = "a red door")
        builder.build(listOf(id(1)))
        assertEquals("${java.time.Instant.ofEpochMilli(MS + 1_000)}\na red door", md.moment(id(1))!!.text)
    }

    @Test fun aMarkStillWaitingForItsPhotoMakesNoMomentYet() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900, photo = MarkPhotoReason.PENDING, createdAt = now - 30_000)
        assertEquals(0, builder.build(listOf(id(0))).stored)
        assertNull(md.moment(id(0)))
    }

    @Test fun whenTheWaitRanOutTheMarkIsTheFallbackMomentWithATimeoutReasonAndIsValid() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900, photo = MarkPhotoReason.PENDING, createdAt = now - DaoMomentSource.PHOTO_WAIT_MS - 1)
        builder.build(listOf(id(0)))
        val m = md.moment(id(0))!!
        assertEquals(MomentType.MARK, m.momentType); assertEquals(MarkPhotoReason.TIMEOUT, m.errorReason)
        assertTrue(MomentValidity.isFallbackMark(m.momentType, m.errorReason))
    }

    @Test fun aReportedFailureIsTheErrorReasonOfTheFallbackMoment() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900, photo = MarkPhotoReason.CAMERA_BUSY, createdAt = now)
        fx.mark("mk:$ref:i", MS + 900, kind = "INTENT", photo = MarkPhotoReason.CAMERA_BUSY, createdAt = now)
        builder.build(listOf(id(0)))
        assertEquals(MarkPhotoReason.CAMERA_BUSY, md.moment(id(0))!!.errorReason)
        assertEquals(2, md.parts(id(0)).size)
    }

    @Test fun anOldMarkWithNoPhotoStateIsAFallbackWithNoPhotoSupport() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900)
        builder.build(listOf(id(0)))
        assertEquals(MarkPhotoReason.NO_PHOTO_SUPPORT, md.moment(id(0))!!.errorReason)
    }

    @Test fun aLatePhotoUpgradesTheFallbackAndTheStaleMarkMomentIsRemovedByTheNextRebuild() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900, photo = MarkPhotoReason.PENDING, createdAt = now - DaoMomentSource.PHOTO_WAIT_MS - 1)
        builder.build(listOf(id(0)))
        assertEquals(MarkPhotoReason.TIMEOUT, md.moment(id(0))!!.errorReason)
        fx.markPhoto("p1", MS + 5_400, ref) // five seconds later, after the wait
        builder.rebuildWindow(MS, MS + 10_000)
        assertNull("the fallback is gone", md.moment(id(0)))
        val m = md.moment(id(5))!!
        assertEquals(MomentType.FRAME, m.momentType); assertNull(m.errorReason)
        assertTrue(md.parts(id(5)).any { it.kind == MomentPartKind.MARK && it.refId == "mk:$ref" })
    }

    @Test fun theStateIsPartOfTheContentHashSoAChangeRebuildsTheMoment() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900, photo = MarkPhotoReason.CAMERA_BUSY, createdAt = now)
        builder.build(listOf(id(0))); val before = md.moment(id(0))!!.contentHash
        db.openHelper.writableDatabase.execSQL("UPDATE canvas_label SET photo = 'no_frame' WHERE labelId = 'mk:$ref'")
        builder.build(listOf(id(0)))
        assertTrue(md.moment(id(0))!!.contentHash != before); assertEquals(MarkPhotoReason.NO_FRAME, md.moment(id(0))!!.errorReason)
    }

    @Test fun forgettingThePhotoRemovesTheMarkAndTheMomentAndARebuildNeverBringsThemBack() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900, photo = MarkPhotoReason.PENDING, createdAt = now)
        fx.markPhoto("p1", MS + 1_400, ref); builder.build(listOf(id(1)))
        assertNotNull(md.moment(id(1)))
        val svc = com.rokid.context.memory.forget.ForgetService(db, com.rokid.context.memory.ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }
        svc.forgetMoment("p1")
        assertNull(md.moment(id(1))); assertNull(db.canvasSlice3Dao().label("mk:$ref"))
        builder.rebuildWindow(MS, MS + 10_000)
        assertTrue(md.allMoments().isEmpty())
    }

    @Test fun aMarkPhotoMomentIsNotInvalid() = runBlocking<Unit> {
        fx.mark("mk:$ref", MS + 900, photo = MarkPhotoReason.PENDING, createdAt = now); fx.markPhoto("p1", MS + 1_400, ref)
        builder.build(listOf(id(1)))
        val m = md.moment(id(1))!!
        assertTrue(MomentValidity.isValid(m.momentId, m.momentType, m.errorReason, hasLivePhoto = true))
        assertFalse(MomentValidity.isFallbackMark(m.momentType, m.errorReason))
    }
}
