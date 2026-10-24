package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.MomentTombstoneEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentBuilderTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val builder = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val lite = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao()))
    private val id = MomentId.format(SEC)
    @After fun close() = db.close()

    private suspend fun kinds(m: String) = db.momentDao().parts(m).map { it.kind to it.refId }

    @Test fun twoFramesInOneSecondAreOneMomentAndOneSecondApartAreTwo() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("b", MS + 999); fx.frame("c", MS + 1000)
        assertEquals(2, builder.build(listOf(id, MomentId.format(SEC + 1))).stored)
        assertEquals(listOf(MomentPartKind.FRAME to "a", MomentPartKind.FRAME to "b"), kinds(id))
        assertEquals(listOf(MomentPartKind.FRAME to "c"), kinds(MomentId.format(SEC + 1)))
    }

    @Test fun memoriesMarksAndCitesAttachAsTypedParts() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a"); fx.memory("s1", "a", derived = true); fx.mark("L1", MS + 500); fx.mark("L2", MS + 1500); fx.ask("ask1", "a")
        builder.build(listOf(id))
        assertEquals(
            setOf(MomentPartKind.FRAME to "a", MomentPartKind.MEMORY to "m1", MomentPartKind.SUMMARY to "s1", MomentPartKind.MARK to "L1", MomentPartKind.CITE to "frame:a"),
            kinds(id).toSet(),
        )
    }

    @Test fun aSecondWithoutALivePhotoIsNotAMomentAndAForgottenPhotoDoesNotCount() = runBlocking<Unit> {
        fx.mark("L", MS + 3000); fx.memory("m", null, createdAt = MS + 3000)
        fx.frame("t", MS + 1); db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("t", 1))
        assertEquals(0, builder.build(listOf(MomentId.format(SEC + 3), id)).stored)
        assertTrue(db.momentDao().allMoments().isEmpty())
    }

    @Test fun contentRevAndHashAreExactlyMomentLites() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("b", MS + 7); fx.memory("m1", "a"); fx.mark("L1", MS + 500)
        builder.build(listOf(id))
        val derived = lite.resolve(listOf(id)).single(); val stored = db.momentDao().moment(id)!!
        assertEquals(derived.contentRev, stored.contentRev); assertEquals(derived.contentHash, stored.contentHash)
    }

    @Test fun partRevisionsAreDigestsNeverTextOrLabels() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a")
        builder.build(listOf(id))
        db.momentDao().parts(id).forEach { assertTrue(it.partRev, Regex("[0-9a-f]{64}").matches(it.partRev)) }
    }

    @Test fun aTombstonedSecondIsNeverBuilt() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(id, 1)))
        val r = builder.build(listOf(id))
        assertEquals(0, r.stored); assertEquals(1, r.refused); assertNull(db.momentDao().moment(id))
    }

    @Test fun aMomentForgetRangeFencesTheBuilder() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        db.canvasSlice3Dao().addMomentForgetRange(MS, MS + 999)
        assertEquals(1, builder.build(listOf(id)).refused); assertNull(db.momentDao().moment(id))
    }

    @Test fun aStoredMomentWhoseLastPhotoIsGoneIsRemovedByTheNextBuild() = runBlocking<Unit> {
        fx.frame("a", MS + 5); builder.build(listOf(id))
        db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("a", MS + 5, "/a.jpg", "rokid_glasses")) // still there
        db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("a", 1))
        assertEquals(1, builder.build(listOf(id)).removed); assertNull(db.momentDao().moment(id)); assertTrue(kinds(id).isEmpty())
    }

    @Test fun aSecondWithAPhotoOfUnknownCaptureTimeIsNotAMoment() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("u", MS + 6, captured = false)
        val r = builder.build(listOf(id))
        assertEquals(0, r.stored); assertEquals(1, r.refused); assertNull(db.momentDao().moment(id))
    }

    @Test fun malformedIdsAndTheSwitchOffWriteNothing() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        assertEquals(MomentBuilder.Report.NONE, builder.build(listOf("zz", "123")))
        assertEquals(MomentBuilder.Report.NONE, MomentBuilder(db, enabled = { false }).build(listOf(id)))
        assertTrue(db.momentDao().allMoments().isEmpty())
    }

    @Test fun anOverfullSecondIsRefusedNotTruncated() = runBlocking<Unit> {
        repeat(MomentResolver.FRAME_CAP + 1) { fx.frame("f$it", MS + 1) }
        assertEquals(0, builder.build(listOf(id)).stored); assertNull(db.momentDao().moment(id))
    }
}
