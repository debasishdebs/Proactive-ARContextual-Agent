package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A7 (multi-select, ruling P2): the ids-only queries name exactly the rows the keyset pages and the tile counts list, in the same order, and honour the limit. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActivityIdsDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private fun memory(id: String, at: Long, frameId: String? = null, review: ReviewState = ReviewState.OK) =
        MemoryEntity(id, frameId, id, "short_term", "", MemoryTier.SHORT, 0.8, review, false, at, false, searchText = "")

    @Test fun framesAreListedByTheSameRangeAndOrderAsThePage() = runBlocking<Unit> {
        (1..6).forEach { db.frameDao().insertIgnore(FrameEntity("f$it", 1_000L + it / 2, "/f$it.jpg", "rokid_glasses")) } // pairs share a millisecond
        val v = db.frameViewDao()
        assertEquals(v.pageIn(1_000, 1_003, 1_003, "", 100).map { it.id }, v.idsIn(1_000, 1_003, 100))
        assertEquals("the end is exclusive", listOf("f5", "f4", "f3", "f2", "f1"), v.idsIn(1_000, 1_003, 100))
        assertEquals(listOf("f5", "f4"), v.idsIn(1_000, 1_003, 2))
    }

    @Test fun visibleMemoriesAreTheTileCountFilterAndAHourIsTheMomentPagePredicate() = runBlocking<Unit> {
        db.frameDao().insertIgnore(FrameEntity("fe", 100, "/fe.jpg", "rokid_glasses")) // an older photo: its memory is shown at 100, created at 5_100
        val md = db.memoryDao()
        md.insert(memory("m1", 5_000)); md.insert(memory("m2", 5_100, frameId = "fe")); md.insert(memory("m3", 5_200)); md.insert(memory("hidden", 5_300, review = ReviewState.INVALIDATED)); md.insert(memory("out", 9_000))
        val v = db.memoryViewDao()
        assertEquals(listOf("m3", "m2", "m1"), v.visibleIdsIn(5_000, 6_000, 100))
        assertEquals(v.visiblePageIn(5_000, 6_000, 6_000, "", 100).map { it.id }, v.visibleIdsIn(5_000, 6_000, 100))
        assertEquals(v.visibleMomentTimes(5_000, 6_000).size, v.visibleIdsIn(5_000, 6_000, 100).size)
        // one hour of display times: m2 is shown at 100, so it is NOT in [5_000, 5_150) but it is in the earlier group
        assertEquals(listOf("m1"), v.momentIdsIn(5_000, 6_000, 5_000, 5_150, 100))
        assertEquals(v.momentPageIn(5_000, 6_000, 5_000, 5_150, 5_150, "", 100).map { it.memory.id }, v.momentIdsIn(5_000, 6_000, 5_000, 5_150, 100))
        assertEquals(listOf("m2"), v.momentIdsIn(5_000, 6_000, Long.MIN_VALUE, 5_000, 100))
        assertEquals(1, v.momentIdsIn(5_000, 6_000, 5_000, 6_000, 1).size)
    }

    @Test fun marksAreTheMomentLabelsOfTheMarkPageOnly() = runBlocking<Unit> {
        val s3 = db.canvasSlice3Dao(); val md = db.momentDao()
        val e = DeletionEpoch("i", 0, 0); val f = DaoFence(0, e, { 0 }, { e })
        fun label(id: String, at: Long, kind: String = "MOMENT") = CanvasLabelEntity(id, kind, "voice", "X", at, createdAt = 1)
        listOf("a" to 1_000L, "b" to 5_000L, "c" to 5_000L, "d" to 9_000L).forEach { s3.insertLabelFenced(label(it.first, it.second), f) }
        s3.insertLabelFenced(label("b:i", 5_000, "INTENT"), f)
        assertEquals(md.marksPage(1_000, 9_000, 9_000, "", 100).map { it.labelId }, md.markIdsIn(1_000, 9_000, 100))
        assertEquals("ties keep the labelId ASC order, an INTENT row is not a mark", listOf("b", "c", "a"), md.markIdsIn(1_000, 9_000, 100))
        assertEquals(listOf("b", "c"), md.markIdsIn(1_000, 9_000, 2))
    }
}
