package com.rokid.context.phone.hub

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.link.MarkState
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomMarkLookupTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.canvasSlice3Dao()
    private val lookup = RoomMarkLookup(dao)
    private suspend fun mark(id: String, photo: String?, frame: String? = null) {
        val e = DeletionEpoch("i", 1, 0)
        dao.insertLabelFenced(CanvasLabelEntity(id, "MOMENT", "tap", "MOMENT", 1, frameId = frame, createdAt = 1, photo = photo), DaoFence(0, e, { 0 }, { e }))
    }

    @Test fun onlyAStoredMarkStillWaitingForItsPhotoIsOpen() = runBlocking<Unit> {
        mark("mk:e:1", "pending"); mark("mk:e:2", "pending", frame = "p"); mark("mk:e:3", "camera_busy"); mark("mk:e:4", null)
        assertEquals(MarkState.OPEN, lookup.state("e:1"))
        for (r in listOf("e:2", "e:3", "e:4")) assertEquals(r, MarkState.CLOSED, lookup.state(r))
        assertEquals("not stored yet", MarkState.UNKNOWN, lookup.state("e:9"))
        dao.putMeta("mark_undone:mk:e:8", "1")
        assertEquals("undone before it ever arrived", MarkState.CLOSED, lookup.state("e:8"))
        db.close()
    }

    @Test fun theClaimIsStoredInTheDatabaseSoANewLookupSeesIt() = runBlocking<Unit> {
        mark("mk:e:1", "pending")
        assertEquals(true, lookup.claim("e:1", "f1"))
        assertEquals(true, RoomMarkLookup(dao).claim("e:1", "f1"))
        assertEquals(false, RoomMarkLookup(dao).claim("e:1", "f2"))
        db.close()
    }
}
