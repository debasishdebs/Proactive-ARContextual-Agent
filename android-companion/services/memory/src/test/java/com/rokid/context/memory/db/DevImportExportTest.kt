package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** WP8: a memory of a Developer-imported test photo never reaches the memory export; a real capture does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DevImportExportTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private fun memory(id: String, frameId: String) = MemoryEntity(id, frameId, "text $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "")

    @Test fun importedPhotoMemoriesAreNotExported() = runBlocking {
        db.frameDao().insertIgnore(FrameEntity("real", 1, "/x/real.jpg", "rokid_glasses"))
        db.frameDao().insertIgnore(FrameEntity("imp", 2, "/x/imp.jpg", FrameSources.DEV_IMPORT))
        db.memoryDao().insert(memory("m-real", "real")); db.memoryDao().insert(memory("m-imp", "imp"))
        assertEquals(listOf("m-real"), db.exportDao().memoryPage(0, 50).map { it.id })
    }
}
