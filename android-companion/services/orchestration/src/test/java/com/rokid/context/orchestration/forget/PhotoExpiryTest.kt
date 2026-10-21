package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.orchestration.WipeFence
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Ruling 11: photo expiry = tombstone + frame row + JPEG + sidecar; meta, vectors, state, sources and memories stay. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoExpiryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val service get() = ForgetService(graph.db, graph.proposals) { 5_000 }

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { graph.db.close(); MemoryGraph.resetForTests(null) }

    private fun files(id: String): Pair<File, File> {
        val dir = File(ctx.filesDir, "rokid_archive").apply { mkdirs() }
        return File(dir, "$id.jpg").apply { writeBytes(byteArrayOf(1)) } to File(dir, "$id.meta.json").apply { writeText("{}") }
    }

    private suspend fun seed(id: String, caption: Boolean = true): Pair<File, File> {
        val (jpg, side) = files(id)
        val db = graph.db
        db.frameDao().insertIgnore(FrameEntity(id, 1_000, jpg.absolutePath, "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.2f, null, null, null, "space1", 1_000))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, "EXTRACTED", 0, null, null, null, 1_000)))
        db.frameVectorDao().upsert(listOf(FrameVectorEntity(id, "space1", byteArrayOf(1, 2))))
        if (caption) {
            db.memoryDao().insert(MemoryEntity("mem_$id", id, "text $id", "caption", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, 1_000, false, "text $id"))
            db.frameSourceDao().insertAll(listOf(FrameSourceEntity("mem_$id", id, null, "FACT")))
        }
        return jpg to side
    }

    @Test fun expiryKeepsMetaVectorSourceAndMemories() = runBlocking {
        val (jpg, side) = seed("f1")
        val r = service.expirePhotos(listOf("f1"))
        assertEquals(setOf(jpg.absolutePath, side.absolutePath), r.framesToDelete.toSet())
        FrameSweep.delete(r.framesToDelete)
        assertFalse(jpg.exists()); assertFalse(side.exists())
        val db = graph.db
        assertNull(db.forgetDao().frame("f1"))
        assertEquals(listOf("f1"), db.forgetDao().tombstoned(listOf("f1")))
        assertNotNull(db.frameMetaDao().get("f1")); assertNotNull(db.frameStateDao().get("f1"))
        assertEquals(1, db.frameVectorDao().countForSpace("space1"))
        assertEquals(listOf("mem_f1"), db.frameSourceDao().memoriesForFrame("f1"))
        assertNotNull(db.memoryViewDao().byId("mem_f1"))
        assertEquals("f1", db.memoryViewDao().byId("mem_f1")!!.frameId) // the memory still names the (now rowless) photo
    }

    @Test fun tombstoneBlocksReextract() = runBlocking {
        seed("f1")
        service.expirePhotos(listOf("f1"))
        // reset the state to a claimable one: a tombstoned frame is still never claimed
        graph.db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET state = 'FAILED' WHERE frameId = 'f1'")
        assertTrue(graph.db.frameStateDao().claim(listOf("f1"), "c", 9_000, 0).isEmpty())
        assertEquals("FAILED", graph.db.frameStateDao().get("f1")!!.state)
    }

    @Test fun failedFileDeleteRetriedBySweep() = runBlocking {
        val (jpg, side) = seed("f1")
        val r = service.expirePhotos(listOf("f1"))
        assertNull(graph.db.forgetDao().frame("f1")) // row is gone: only the sweep can find the files now
        // The delete never ran (process death / failed delete): the files are still there.
        assertTrue(jpg.exists() && side.exists() && r.framesToDelete.size == 2)
        FrameSweep.sweep(ctx, nowMs = 10_000)
        assertFalse(jpg.exists()); assertFalse(side.exists())
        assertEquals(listOf("f1"), graph.db.forgetDao().tombstoned(listOf("f1"))) // a memory still cites it: tombstone stays
    }

    @Test fun expiringAnUnknownOrAlreadyExpiredFrameIsANoOp() = runBlocking {
        seed("f1")
        service.expirePhotos(listOf("f1"))
        val again = service.expirePhotos(listOf("f1", "nope"))
        assertTrue(again.framesToDelete.isEmpty())
        assertTrue(graph.db.forgetDao().tombstoned(listOf("nope")).isEmpty())
    }

    /** Delete all / Forget interplay: files are removed under the fence lock; a held fence delays the delete, never skips it. */
    @Test fun fenceWithConcurrentForget() = runBlocking {
        val (jpg, side) = seed("f1")
        val r = service.expirePhotos(listOf("f1"))
        val deleter = WipeFence.mutex.withLock {
            val d = async(kotlinx.coroutines.Dispatchers.Default) { FrameSweep.delete(r.framesToDelete) }
            repeat(20) { yield(); Thread.sleep(5) }
            assertTrue("delete must wait for the fence", jpg.exists() && side.exists())
            d
        }
        assertEquals(2, deleter.await())
        assertFalse(jpg.exists())
        // Forget moment after expiry still works on the rowless photo and removes its memories.
        val f = service.forgetMoment("f1")
        assertEquals(setOf("mem_f1"), f.forgotten)
        assertNull(graph.db.memoryViewDao().byId("mem_f1"))
        // and a forget that ran first leaves expiry nothing to do
        seed("f2"); service.forgetMoment("f2")
        assertTrue(service.expirePhotos(listOf("f2")).framesToDelete.isEmpty())
    }
}
