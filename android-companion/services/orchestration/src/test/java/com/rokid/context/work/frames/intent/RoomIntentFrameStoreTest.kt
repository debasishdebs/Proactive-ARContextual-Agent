package com.rokid.context.work.frames.intent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.work.frames.KeyframeGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomIntentFrameStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val store = RoomIntentFrameStore { db }

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { KeyframeGraph.intentSampler = null; db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun frame(id: String, receivedAt: Long, frameTs: Long, capturedAtMs: Long? = null) {
        db.frameDao().insertIgnore(FrameEntity(id, frameTs, "/none/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, null, null, null, null, null, receivedAt, capturedAtMs = capturedAtMs))
    }

    @Test fun captureTimeIsCapturedAtThenFrameTsNeverTheReceiveTime() = runBlocking {
        frame("a", receivedAt = 1_000, frameTs = 400, capturedAtMs = 700)
        assertEquals(700L, store.newestUnlabelled(0)!!.capturedAtMs)
        db.frameMetaDao().setIntent("a", "OTHER", 0.5f, 1)
        frame("b", receivedAt = 2_000, frameTs = 450, capturedAtMs = null)
        assertEquals("falls back to frame.ts, not createdAt 2000", 450L, store.newestUnlabelled(0)!!.capturedAtMs)
    }

    @Test fun nothingKnownIsNull() = runBlocking {
        db.frameMetaDao().upsert(FrameMetaEntity("c", null, null, "STILL", false, null, null, null, null, null, null, null, null, null, 5))
        assertNull("no frame row and no capture time: unknown", store.newestUnlabelled(0)!!.capturedAtMs)
    }

    @Test fun setIntentReportsRowsAndTombstonesAreSeen() = runBlocking {
        frame("a", 1_000, 900)
        assertEquals(1, store.setIntent("a", "DRIVING", 0.9f, 5)); assertEquals(0, store.setIntent("gone", "DRIVING", 0.9f, 5))
        assertFalse(store.tombstoned("a"))
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("a", 1))
        assertTrue(store.tombstoned("a"))
    }
}
