package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.moment.NoPhotoReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A1: the by-id mark queries return exactly what the old scan of every mark (`marksIn(0, MAX)` then a filter) returned. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkByIdsTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    @Test fun byIdsEqualsTheFullScanFilteredAndAnUnknownIdIsMissing() = runBlocking<Unit> {
        val s3 = db.canvasSlice3Dao(); val md = db.momentDao()
        val e = DeletionEpoch("i", 0, 0); val f = DaoFence(0, e, { 0 }, { e })
        fun label(id: String, at: Long, kind: String = "MOMENT") = CanvasLabelEntity(id, kind, "voice", "X", at, createdAt = 1)
        listOf("a" to 1_000L, "b" to 5_000L, "c" to 5_000L, "d" to 9_000L).forEach { s3.insertLabelFenced(label(it.first, it.second), f) }
        s3.insertLabelFenced(label("b:i", 5_000, "INTENT"), f)
        md.replace(MomentEntity("00000005", 1, 1, "h", 1, MomentType.MARK, NoPhotoReason.UNKNOWN), listOf(MomentPartEntity("00000005", MomentPartKind.MARK, "b", "r", 5_000, "voice")))
        val ids = listOf("d", "b", "a", "missing", "b:i")
        val old = md.marksIn(0L, Long.MAX_VALUE).first().filter { it.labelId in ids }
        assertEquals(old, md.marksByIds(ids))
        assertEquals(listOf("d", "b", "a"), md.marksByIds(ids).map { it.labelId })
        assertEquals(old.first { it.labelId == "b" }, md.observeMark("b").first())
        assertNull(md.observeMark("missing").first())
        assertNull("an INTENT row is not a mark", md.observeMark("b:i").first())
    }
}
