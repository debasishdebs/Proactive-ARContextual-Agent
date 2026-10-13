package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun vacuumLeavesNoRebuildMarker() = runBlocking {
        MemoryMaintenance.vacuum(db)
        assertFalse(MemoryMaintenance.rebuildPending(db.openHelper.writableDatabase))
    }

    /** Task 14 carry: a crash between VACUUM and the FTS rebuild leaves the marker; the next open of the app's database rebuilds. */
    @Test fun aCrashAfterVacuumIsRepairedAtTheNextOpen() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(DatabaseFactory.NAME)
        fun open() = DatabaseFactory.buildDeferred(ctx, { false }) { ByteArray(0) }
        val first = open()
        val repo = MemoryRepository(first.memoryDao())
        listOf("b" to "banana bread", "c" to "cherry tart").forEach { (id, t) ->
            repo.add(MemoryEntity(id, null, t, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))
        }
        withContext(Dispatchers.IO) {
            first.openHelper.writableDatabase.apply {
                execSQL("DROP TRIGGER room_fts_content_sync_memory_fts_BEFORE_UPDATE")
                execSQL("DROP TRIGGER room_fts_content_sync_memory_fts_AFTER_UPDATE")
                MemoryMaintenance.markRebuildPending(this) // what vacuum() persists first
                execSQL("UPDATE memory SET rowid = rowid + 100") // VACUUM's renumbering, then the process dies before the rebuild
            }
        }
        assertTrue(repo.search("banana", 5).map { it.id } != listOf("b")) // the index is misaligned now
        first.close()

        val second = open()
        try {
            assertEquals(listOf("b"), MemoryRepository(second.memoryDao()).search("banana", 5).map { it.id })
            assertEquals(listOf("c"), MemoryRepository(second.memoryDao()).search("cherry", 5).map { it.id })
            assertFalse(withContext(Dispatchers.IO) { MemoryMaintenance.rebuildPending(second.openHelper.writableDatabase) })
        } finally { second.close(); ctx.deleteDatabase(DatabaseFactory.NAME) }
    }
}
