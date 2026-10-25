package com.rokid.context.memory.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** `MomentType` (Room v13): derived from the parts, stored on the row, and what the Activity tab reads of a mark (newest first, joined Moment, type, reason). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentTypeStoreTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val md = db.momentDao()
    @After fun close() = db.close()

    @Test fun theTypeIsDerivedFromTheParts() {
        assertEquals(MomentType.FRAME, MomentType.of(frames = 1, marks = 0))
        assertEquals(MomentType.FRAME, MomentType.of(frames = 2, marks = 3))
        assertEquals(MomentType.MARK, MomentType.of(frames = 0, marks = 1))
        assertNull("a second with neither is not a Moment", MomentType.of(frames = 0, marks = 0))
    }

    @Test fun theTypeAndTheReasonAreStoredAndReadBack() = runBlocking<Unit> {
        md.replace(MomentEntity("00000001", 1, 1, "h", 1, MomentType.MARK, NoPhotoReason.UNKNOWN), emptyList())
        md.replace(MomentEntity("00000002", 1, 1, "h", 1, MomentType.FRAME), emptyList())
        assertEquals(MomentType.MARK to NoPhotoReason.UNKNOWN, md.moment("00000001")!!.let { it.momentType to it.noPhotoReason })
        assertEquals(MomentType.FRAME to null, md.moment("00000002")!!.let { it.momentType to it.noPhotoReason })
    }

    @Test fun aPartCarriesTheCodeOfItsMark() = runBlocking<Unit> {
        md.replace(MomentEntity("00000001", 1, 1, "h", 1, MomentType.MARK, NoPhotoReason.UNKNOWN), listOf(MomentPartEntity("00000001", MomentPartKind.MARK, "mk:1:1", "r", 1, "voice")))
        assertEquals("voice", md.parts("00000001").single().code)
    }

    private fun label(id: String, source: String, at: Long, kind: String = "MOMENT") = CanvasLabelEntity(id, kind, source, if (kind == "MOMENT") "MOMENT" else "word", at, createdAt = 1)

    @Test fun marksAreListedNewestFirstWithTheirMomentTypeAndReason() = runBlocking<Unit> {
        val s3 = db.canvasSlice3Dao()
        val e = DeletionEpoch("i", 0, 0); val f = DaoFence(0, e, { 0 }, { e })
        s3.insertLabelFenced(label("a", "tap", 1_000), f)
        s3.insertLabelFenced(label("b", "voice", 5_000), f); s3.insertLabelFenced(label("b:i", "voice", 5_000, "INTENT"), f); s3.insertLabelFenced(label("c", "ask_tap", 9_000), f)
        md.replace(MomentEntity("00000005", 1, 1, "h", 1, MomentType.MARK, NoPhotoReason.UNKNOWN), listOf(MomentPartEntity("00000005", MomentPartKind.MARK, "b", "r", 5_000, "voice")))
        val rows = md.marksIn(0, 10_000).first()
        assertEquals("the INTENT row is not a mark; newest first", listOf("c", "b", "a"), rows.map { it.labelId })
        assertEquals(listOf("ask_tap", "voice", "tap"), rows.map { it.code })
        assertEquals(listOf(null, "00000005", null), rows.map { it.momentId })
        assertEquals(listOf(null, MomentType.MARK, null), rows.map { it.momentType })
        assertEquals(listOf(null, NoPhotoReason.UNKNOWN, null), rows.map { it.noPhotoReason })
        assertEquals("a range", listOf("b"), md.marksIn(4_000, 6_000).first().map { it.labelId })
    }

    @Test fun aMarkRowCarriesItsPhotoStateAndTheErrorReasonOfItsFallbackMoment() = runBlocking<Unit> {
        val s3 = db.canvasSlice3Dao()
        val e = DeletionEpoch("i", 0, 0); val f = DaoFence(0, e, { 0 }, { e })
        s3.insertLabelFenced(label("a", "tap", 1_000).copy(photo = "pending"), f)
        s3.insertLabelFenced(label("b", "tap", 5_000).copy(photo = "camera_busy"), f)
        md.replace(MomentEntity("00000005", 1, 1, "h", 1, MomentType.MARK, NoPhotoReason.UNKNOWN, errorReason = "camera_busy"), listOf(MomentPartEntity("00000005", MomentPartKind.MARK, "b", "r", 5_000, "tap")))
        val rows = md.marksIn(0, 10_000).first()
        assertEquals(listOf("camera_busy", "pending"), rows.map { it.photo })
        assertEquals(listOf("camera_busy", null), rows.map { it.errorReason })
    }
}
