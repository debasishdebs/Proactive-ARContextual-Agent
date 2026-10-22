package com.rokid.context.work.frames.intent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The production writer on a real Room database: labels reach frame_meta through [RoomIntentFrameStore]. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameIntentWriterRoomTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun frame(id: String) {
        db.frameDao().insertIgnore(FrameEntity(id, 900, "/none/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, null, null, null, null, null, 1_000))
    }
    private suspend fun label(id: String) = db.openHelper.readableDatabase.query("SELECT intentLabel FROM frame_meta WHERE frameId='$id'").use { if (it.moveToFirst()) it.getString(0) else "ROW_GONE" }

    @Test fun aLiveLabelReachesFrameMetaAndATombstonedOrGoneFrameDoesNot() = runBlocking<Unit> {
        frame("a"); frame("b")
        val w = FrameIntentWriter(RoomIntentFrameStore { db })
        w.record("a", "DRIVING", 0.9f, 5)
        assertEquals("DRIVING", label("a")); assertEquals(1, w.lastRows)
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("b", 1))
        w.record("b", "DRIVING", 0.9f, 5)
        assertNull(label("b")); w.record("nope", "DRIVING", 0.9f, 5); assertEquals(0, w.lastRows)
    }
}
