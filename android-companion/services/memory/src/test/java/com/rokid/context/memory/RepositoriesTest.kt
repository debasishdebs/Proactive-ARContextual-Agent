package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
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

    @Test fun addIgnoreKeepsFirst() = runTest {
        assertTrue(memories.addIgnore(m("m1", "first", 1)))
        assertFalse(memories.addIgnore(m("m1", "second", 2)))
        assertEquals("first", memories.recent(1).single().text)
    }
}
