package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryMaintenanceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    @After fun close() = db.close()

    @Test fun searchStillMatchesTheRightRowsAfterVacuum() = runBlocking {
        listOf("a" to "apple pie", "b" to "banana bread", "c" to "cherry tart").forEach { (id, t) ->
            memories.add(MemoryEntity(id, null, t, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))
        }
        memories.deleteByIds(listOf("a"))
        MemoryMaintenance.vacuum(db)
        assertEquals(listOf("b"), memories.search("banana", 5).map { it.id })
        assertEquals(listOf("c"), memories.search("cherry", 5).map { it.id })
        assertEquals(emptyList<String>(), memories.search("apple", 5).map { it.id })
    }

    /** Robolectric's SQLite keeps rowids on VACUUM, so the renumbering an export or VACUUM may do is simulated. */
    @Test fun theFtsIndexIsRebuiltAgainstRenumberedRowids() = runBlocking {
        listOf("b" to "banana bread", "c" to "cherry tart").forEach { (id, t) ->
            memories.add(MemoryEntity(id, null, t, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))
        }
        db.openHelper.writableDatabase.apply { // renumber without Room's sync triggers, as a copy into a new file would
            execSQL("DROP TRIGGER room_fts_content_sync_memory_fts_BEFORE_UPDATE")
            execSQL("DROP TRIGGER room_fts_content_sync_memory_fts_AFTER_UPDATE")
            execSQL("UPDATE memory SET rowid = rowid + 100")
        }
        MemoryMaintenance.vacuum(db)
        assertEquals(listOf("b"), memories.search("banana", 5).map { it.id })
        assertEquals(listOf("c"), memories.search("cherry", 5).map { it.id })
    }
}
