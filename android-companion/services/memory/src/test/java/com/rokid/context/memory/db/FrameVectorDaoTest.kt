package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameVectorDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private suspend fun meta(id: String) =
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.9f, null, null, null, "s1", 1_000))
    private fun vec(id: String, space: String) = FrameVectorEntity(id, space, byteArrayOf(1, 2, 3, 4))

    @Test fun pageDescByRowidDescBySpace() = runBlocking {
        listOf("a", "b", "c").forEach { meta(it) }
        db.frameVectorDao().upsert(listOf(vec("a", "s1"), vec("b", "s1"), vec("c", "s1"), vec("a", "other")))
        val dao = db.frameVectorDao()
        val first = dao.pageDesc("s1", Long.MAX_VALUE, 2)
        assertEquals(listOf("c", "b"), first.map { it.frameId })
        val next = dao.pageDesc("s1", first.last().rowid, 2)
        assertEquals(listOf("a"), next.map { it.frameId })
        assertEquals(listOf("a"), dao.pageDesc("other", Long.MAX_VALUE, 10).map { it.frameId })
    }

    @Test fun pageDescSkipsFramesWithoutMeta() = runBlocking {
        meta("kept")
        db.frameVectorDao().upsert(listOf(vec("kept", "s1"), vec("forgotten", "s1")))
        assertEquals(listOf("kept"), db.frameVectorDao().pageDesc("s1", Long.MAX_VALUE, 10).map { it.frameId })
    }

    @Test fun describedAmongAndNewestCaptions() = runBlocking {
        fun m(id: String, frame: String?, type: String, at: Long, state: ReviewState = ReviewState.OK) =
            MemoryEntity(id, frame, "t $id", type, "", MemoryTier.SHORT, 0.8, state, false, at, false, "t $id")
        db.memoryDao().insert(m("c1", "f1", "caption", 10)); db.memoryDao().insert(m("c2", "f1", "caption", 20))
        db.memoryDao().insert(m("dead", "f2", "caption", 30, ReviewState.INVALIDATED))
        db.memoryDao().insert(m("fact", null, "note", 5))
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("fact", "f3", null, "FACT"), FrameSourceEntity("fact", "f4", null, "CLUSTER_MEMBER")))
        val dao = db.frameSourceDao()
        assertEquals(setOf("f1", "f3"), dao.describedAmong(listOf("f1", "f2", "f3", "f4", "f5")).toSet())
        val caps = dao.newestCaptions(listOf("f1", "f2"))
        assertEquals(listOf("c2"), caps.map { it.memoryId }); assertEquals("f1", caps.single().frameId)
    }
}
