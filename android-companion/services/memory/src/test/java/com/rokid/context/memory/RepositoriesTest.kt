package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameDao
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryDao
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.db.TraceDao
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoriesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    @After fun close() = db.close()

    private fun m(id: String, text: String, at: Long) = MemoryEntity(id, null, text, "short_term", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, at, false, searchText = "")

    @Test fun addComputesSearchTextSoSearchWorks() = runTest {
        memories.add(m("m1", "Milk-carton EMPTY", 1))
        assertEquals("milk carton empty", memories.recent(1).single().searchText)
        assertEquals(listOf("m1"), memories.search("milk", 10).map { it.id })
        assertTrue(memories.search("!!!", 10).isEmpty())
    }

    @Test fun onDayReturnsOnlyThatLocalDay() = runTest {
        val zone = ZoneId.of("Asia/Kolkata")
        val day = DayBucket.bounds(LocalDate.of(2026, 10, 12), zone)
        memories.add(m("in", "a", day.first + 1))
        memories.add(m("before", "b", day.first - 1))
        memories.add(m("after", "c", day.last + 1))
        assertEquals(listOf("in"), memories.onDay(LocalDate.of(2026, 10, 12), zone).map { it.id })
    }

    /** Fix round 2: LIKE wildcards in the prefix (`_`, `%`, `\\`) are literal, so a sibling job never matches. */
    @Test fun idsWithPrefixTreatsLikeWildcardsLiterally() = runTest {
        listOf("rokid_mem_ab_c_0", "rokid_mem_ab_c_1", "rokid_mem_abxc_1", "rokid_mem_ab_cx0", "rokid_mem_ab%c_0", "rokid_mem_a\\b_0", "rokid_mem_axb_0")
            .forEach { memories.add(m(it, "t", 1)) }
        assertEquals(setOf("rokid_mem_ab_c_0", "rokid_mem_ab_c_1"), memories.idsWithPrefix("rokid_mem_ab_c_").toSet())
        assertEquals(setOf("rokid_mem_ab%c_0"), memories.idsWithPrefix("rokid_mem_ab%c_").toSet())
        assertEquals(setOf("rokid_mem_a\\b_0"), memories.idsWithPrefix("rokid_mem_a\\b_").toSet())
    }

    /** Fix round 2: the DAO query itself (no Kotlin re-check) treats an escaped prefix literally. */
    @Test fun idsLikeWithEscapedPrefixMatchesOnlyLiteralPrefix() = runTest {
        listOf("rokid_mem_ab_c_0", "rokid_mem_ab_c_1", "rokid_mem_abxc_1", "rokid_mem_ab_cx0", "rokid_mem_ab%c_0", "rokid_mem_a\\b_0", "rokid_mem_axb_0")
            .forEach { memories.add(m(it, "t", 1)) }
        val dao = db.memoryDao()
        assertEquals(setOf("rokid_mem_ab_c_0", "rokid_mem_ab_c_1"), dao.idsLike(MemoryRepository.escapeLike("rokid_mem_ab_c_")).toSet())
        assertEquals(setOf("rokid_mem_ab%c_0"), dao.idsLike(MemoryRepository.escapeLike("rokid_mem_ab%c_")).toSet())
        assertEquals(setOf("rokid_mem_a\\b_0"), dao.idsLike(MemoryRepository.escapeLike("rokid_mem_a\\b_")).toSet())
    }

    @Test fun addIgnoreKeepsFirst() = runTest {
        assertTrue(memories.addIgnore(m("m1", "first", 1)))
        assertFalse(memories.addIgnore(m("m1", "second", 2)))
        assertEquals("first", memories.recent(1).single().text)
    }

    private val twoThousand = (0 until 2_000).map { "id${it.toString().padStart(4, '0')}" }

    private class Calls { val sizes = mutableListOf<Int>(); val ids = mutableListOf<String>(); fun record(l: List<String>) { sizes += l.size; ids += l } }
    private class SpyFrames(real: FrameDao, val byIdsCalls: Calls = Calls(), val deleteCalls: Calls = Calls()) : FrameDao by real {
        private val r = real
        override suspend fun byIds(ids: List<String>): List<FrameEntity> { byIdsCalls.record(ids); return r.byIds(ids) }
        override suspend fun deleteByIds(ids: List<String>) { deleteCalls.record(ids); r.deleteByIds(ids) }
    }
    private class SpyMemories(private val r: MemoryDao, val calls: Calls = Calls()) : MemoryDao by r {
        override suspend fun deleteByIds(ids: List<String>) { calls.record(ids); r.deleteByIds(ids) }
    }
    private class SpyTraces(private val r: TraceDao, val calls: Calls = Calls()) : TraceDao by r {
        override suspend fun deleteByIds(ids: List<String>) { calls.record(ids); r.deleteByIds(ids) }
    }

    /** B4: API 29 SQLite allows 999 variables; no IN-list DAO call ever gets more than 500 ids, and none for empty input. */
    @Test fun noDaoCallGetsMoreThanFiveHundredIds() = runTest {
        val frames = SpyFrames(db.frameDao()); val mems = SpyMemories(db.memoryDao()); val traces = SpyTraces(db.traceDao())
        FrameRepository(frames).byIds(twoThousand + twoThousand.take(10)) // duplicates are de-duplicated
        FrameRepository(frames).deleteByIds(twoThousand)
        MemoryRepository(mems).deleteByIds(twoThousand)
        TraceRepository(traces).deleteByIds(twoThousand)
        for ((name, c) in listOf("frame.byIds" to frames.byIdsCalls, "frame.delete" to frames.deleteCalls, "memory.delete" to mems.calls, "trace.delete" to traces.calls)) {
            assertTrue("$name ${c.sizes}", c.sizes.isNotEmpty() && c.sizes.all { it <= 500 })
            assertEquals(name, twoThousand, c.ids.sorted())
        }
        val empty = SpyFrames(db.frameDao())
        assertTrue(FrameRepository(empty).byIds(emptyList()).isEmpty()); FrameRepository(empty).deleteByIds(emptyList())
        assertTrue(empty.byIdsCalls.sizes.isEmpty() && empty.deleteCalls.sizes.isEmpty())
    }

    /** B4: chunked byIds still returns every frame in ts ASC order across chunk boundaries. */
    @Test fun byIdsWithTwoThousandIdsReturnsAllInTsOrder() = runTest {
        val repo = FrameRepository(db.frameDao())
        // ts runs opposite to id order, so per-chunk results concatenated in id order are not ts-sorted.
        twoThousand.forEachIndexed { i, id -> repo.add(FrameEntity(id, ts = (2_000 - i).toLong(), path = "/f/$id", source = "glasses")) }
        val rows = repo.byIds(twoThousand)
        assertEquals(2_000, rows.size)
        assertEquals(rows.sortedBy { it.ts }.map { it.id }, rows.map { it.id })
        assertEquals(twoThousand.toSet(), rows.map { it.id }.toSet())
    }

    /** B4: chunked deletes remove every one of 2 000 memories and traces. */
    @Test fun deleteByIdsWithTwoThousandIdsDeletesAll() = runTest {
        twoThousand.forEach { memories.addIgnore(m(it, "t", 1)) }
        val traces = TraceRepository(db.traceDao())
        twoThousand.forEach { traces.addIgnore(SummaryTraceEntity(it, "done", 1, "")) }
        memories.deleteByIds(twoThousand); traces.deleteByIds(twoThousand)
        assertEquals(0, db.memoryDao().count())
        assertTrue(traces.recent(10_000).isEmpty())
    }
}
