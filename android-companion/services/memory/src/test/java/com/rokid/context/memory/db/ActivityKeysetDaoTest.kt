package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Activity pagination A1: every keyset query walks its whole range with no skip and no duplicate, ties at one millisecond included, and its filter equals the tile-count filter. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActivityKeysetDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private fun frame(id: String, ts: Long) = FrameEntity(id = id, ts = ts, path = "/x/$id.jpg", source = "rokid_glasses")
    private fun memory(id: String, at: Long, frameId: String? = null, review: ReviewState = ReviewState.OK) =
        MemoryEntity(id, frameId, "t $id", "short_term", "", MemoryTier.SHORT, 0.8, review, false, at, false, searchText = "")
    private fun ask(id: String, ts: Long, source: String = "phone_typed") = AskEntity(id, ts, "q", "a", "[]", null, source, false)
    private fun nudge(id: String, ts: Long) = NudgeEntity(id, ts, "n", "w", "[]", 1, "h_$id", "phone", false)

    /** Walks a keyset query to its end with pages of [size]. */
    private suspend fun <T> walk(size: Int, from: Long, to: Long, key: (T) -> Long, id: (T) -> String, page: suspend (Long, String, Int) -> List<T>): List<T> {
        val out = mutableListOf<T>(); var ck = to; var ci = ""
        while (true) {
            val p = page(ck, ci, size); out += p
            if (p.size < size) return out
            ck = key(p.last()); ci = id(p.last())
        }
    }

    @Test fun framesWalkWithTiesAndHalfOpenBounds() = runBlocking<Unit> {
        val d = db.frameDao(); val v = db.frameViewDao()
        listOf("a" to 100L, "b" to 200L, "c" to 200L, "d" to 200L, "e" to 300L, "f" to 400L, "g" to 99L, "h" to 500L).forEach { d.insertIgnore(frame(it.first, it.second)) }
        val all = walk(3, 100, 500, { it: FrameEntity -> it.ts }, { it.id }) { k, i, n -> v.pageIn(100, 500, k, i, n) }
        assertEquals("first bound inclusive, last exclusive, newest first, id DESC on a tie", listOf("f", "e", "d", "c", "b", "a"), all.map { it.id })
        assertEquals(v.observeTimes(100, 500).first().size, all.size)
    }

    @Test fun noSkipOrDuplicateWhileRowsAreInsertedAndDeletedBetweenPages() = runBlocking<Unit> {
        val d = db.frameDao(); val v = db.frameViewDao()
        (1..9).forEach { d.insertIgnore(frame("f$it", it * 100L)) }
        val first = v.pageIn(0, 1_000, 1_000, "", 3)
        assertEquals(listOf("f9", "f8", "f7"), first.map { it.id })
        d.insertIgnore(frame("new", 950)) // a newer row lands above the cursor: not in the next page
        db.forgetDao().deleteFrame("f5") // a row below the cursor is forgotten
        val next = v.pageIn(0, 1_000, first.last().ts, first.last().id, 3)
        assertEquals(listOf("f6", "f4", "f3"), next.map { it.id })
    }

    @Test fun answersSkipEvalAsksAndMatchTheTileCount() = runBlocking<Unit> {
        val d = db.askDao()
        (1..7).forEach { d.upsert(ask("a$it", 1_000L + it / 3)) } // ties
        d.upsert(ask("eval", 1_001, source = "canvas_eval"))
        val all = walk(2, 1_000, 2_000, { it: AskEntity -> it.ts }, { it.id }) { k, i, n -> d.pageIn(1_000, 2_000, k, i, n) }
        assertEquals(7, all.size); assertEquals(all.map { it.id }.toSet().size, all.size)
        assertTrue(all.none { it.source == "canvas_eval" })
        assertEquals(d.observeTimes(1_000, 2_000).first().size, all.size)
        assertEquals(listOf("a2"), d.byIds(listOf("a2")).map { it.id })
    }

    @Test fun nudgesWalkAndMatchTheTileCount() = runBlocking<Unit> {
        val d = db.nudgeDao()
        (1..8).forEach { d.insertIgnore(nudge("n$it", 500L + it % 3)) }
        val all = walk(3, 500, 600, { it: NudgeEntity -> it.ts }, { it.id }) { k, i, n -> d.pageIn(500, 600, k, i, n) }
        assertEquals(8, all.size); assertEquals(8, all.map { it.id }.toSet().size)
        assertEquals(d.observeTimes(500, 600).first().size, all.size)
        assertEquals(all.map { it.ts to it.id }, all.map { it.ts to it.id }.sortedWith(compareByDescending<Pair<Long, String>> { it.first }.thenByDescending { it.second }))
        assertEquals("n1", d.observe("n1").first()!!.id)
    }

    @Test fun visibleMemoriesSkipUnreviewedAndMatchTheTileCount() = runBlocking<Unit> {
        val d = db.memoryDao(); val v = db.memoryViewDao()
        (1..7).forEach { d.insertIgnore(memory("m$it", 1_000L + it % 2)) }
        d.insertIgnore(memory("hidden", 1_000, review = ReviewState.NEEDS_REVIEW))
        val all = walk(3, 1_000, 2_000, { it: MemoryEntity -> it.createdAt }, { it.id }) { k, i, n -> v.visiblePageIn(1_000, 2_000, k, i, n) }
        assertEquals(7, all.size); assertTrue(all.none { it.id == "hidden" })
        assertEquals(v.observeVisibleTimes(1_000, 2_000).first().size, all.size)
    }

    @Test fun momentPagesUseTheFrameTimeWithACreatedAtFallbackAndEarlierPhotos() = runBlocking<Unit> {
        val f = db.frameDao(); val d = db.memoryDao(); val v = db.memoryViewDao()
        f.insertIgnore(frame("fa", 1_500)); f.insertIgnore(frame("fold", 50))
        d.insertIgnore(memory("withFrame", at = 2_900, frameId = "fa")) // displayed at 1_500
        d.insertIgnore(memory("plain", at = 2_500)) // displayed at 2_500
        d.insertIgnore(memory("plain2", at = 2_500))
        d.insertIgnore(memory("old", at = 2_700, frameId = "fold")) // displayed at 50: before the day
        d.insertIgnore(memory("otherDay", at = 9_000))
        d.insertIgnore(memory("hidden", at = 2_000, review = ReviewState.NEEDS_REVIEW))
        val times = v.visibleMomentTimes(1_000, 3_000).sorted()
        assertEquals(listOf(50L, 1_500L, 2_500L, 2_500L), times)
        val hourHigh = walk(1, 2_000, 3_000, { it: MomentRow -> it.shownAt }, { it.memory.id }) { k, i, n -> v.momentPageIn(1_000, 3_000, 2_000, 3_000, k, i, n) }
        assertEquals(listOf("plain2", "plain"), hourHigh.map { it.memory.id })
        assertEquals(listOf(1_500L), v.momentPageIn(1_000, 3_000, 1_000, 2_000, 2_000, "", 10).map { it.shownAt })
        assertEquals(listOf("withFrame"), v.momentPageIn(1_000, 3_000, 1_000, 2_000, 2_000, "", 10).map { it.memory.id })
        assertEquals("earlier group", listOf("old"), v.momentPageIn(1_000, 3_000, Long.MIN_VALUE, 1_000, 1_000, "", 10).map { it.memory.id })
        assertEquals(times.size, v.momentPageIn(1_000, 3_000, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, "", 10).size)
    }

    @Test fun marksKeepTheLabelIdAscTieOrderAndSkipIntentRows() = runBlocking<Unit> {
        val s3 = db.canvasSlice3Dao(); val md = db.momentDao()
        val e = DeletionEpoch("i", 0, 0); val fence = DaoFence(0, e, { 0 }, { e })
        fun label(id: String, at: Long, kind: String = "MOMENT") = CanvasLabelEntity(id, kind, "tap", "X", at, createdAt = 1)
        listOf("b" to 5_000L, "a" to 5_000L, "c" to 5_000L, "d" to 4_000L, "e" to 3_000L, "z" to 9_000L).forEach { s3.insertLabelFenced(label(it.first, it.second), fence) }
        s3.insertLabelFenced(label("b:i", 5_000, "INTENT"), fence)
        val all = walk(2, 1_000, 9_000, { it: MomentDao.MarkRow -> it.atMs }, { it.labelId }) { k, i, n -> md.marksPage(1_000, 9_000, k, i, n) }
        // The paged rows agree with the whole-range query in the same order (labelId ASC on a tie).
        assertEquals(md.marksIn(1_000, 8_999).first().map { it.labelId }, all.map { it.labelId })
        assertEquals(listOf("a", "b", "c", "d", "e"), all.map { it.labelId })
    }

    @Test fun theDatabaseVersionIsEighteen() {
        assertEquals("pagination adds queries only; the migrations since v16 are the ruled v17 (ask.sourceStates, Forget M5) v18 (place_card_log, moment_location, scene_centroid, model_attribution) and v19 (place_card_log eval, label, labelAt)", 19, db.openHelper.readableDatabase.version)
    }
}
