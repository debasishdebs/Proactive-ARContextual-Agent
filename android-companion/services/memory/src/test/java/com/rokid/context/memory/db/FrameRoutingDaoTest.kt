package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Wave 2 additions to FrameMetaDao and FrameVectorDao (the scene router's reads and writes). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameRoutingDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private suspend fun frame(id: String, ts: Long?, state: String = "PENDING", routedAt: Long? = null, createdAt: Long = 0, clusterId: String? = null) {
        if (ts != null) db.frameDao().insertIgnore(FrameEntity(id, ts, "/x/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, null, null, clusterId, null, null, createdAt, routedAt))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, 0, null, null, null, 0)))
    }

    @Test fun unroutedPendingIsOldestCaptureFirstAndSkipsRoutedTombstonedAndOtherStates() = runBlocking {
        frame("late", 300); frame("early", 100); frame("noFrameRow", null, createdAt = 200)
        frame("routed", 50, routedAt = 1); frame("extracting", 60, state = "EXTRACTING"); frame("forgotten", 70)
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("forgotten", 1))
        assertEquals(listOf("early", "noFrameRow", "late"), db.frameMetaDao().unroutedPending(10).map { it.frameId })
        assertEquals(listOf("early"), db.frameMetaDao().unroutedPending(1).map { it.frameId })
    }

    @Test fun markRoutedWritesTheRoutingResultAndMembersFindsTheCluster() = runBlocking {
        frame("a", 1); frame("b", 2, createdAt = 2); frame("c", 3)
        val meta = db.frameMetaDao()
        meta.markRouted("a", 0.7f, "dwell,text", null, "cl1", "nomic", 99)
        meta.markRouted("b", null, null, "a", "cl1", null, 100)
        val a = meta.get("a")!!
        assertEquals(0.7f, a.salience!!, 0f); assertEquals("dwell,text", a.salienceTerms); assertEquals("cl1", a.clusterId); assertEquals("nomic", a.imageVectorSpace); assertEquals(99L, a.routedAt)
        assertEquals("a", meta.get("b")!!.nearDupOf)
        assertEquals(listOf("c"), meta.unroutedPending(10).map { it.frameId })
        assertEquals(setOf("a", "b"), meta.members("cl1").map { it.frameId }.toSet())
        assertEquals(emptyList<FrameMetaEntity>(), meta.members("none"))
    }

    @Test fun vectorGetWindowAndForFrames() = runBlocking {
        frame("a", 100); frame("b", 200); frame("c", 300)
        val v = db.frameVectorDao()
        v.upsert(listOf("a", "b", "c").map { FrameVectorEntity(it, "s1", byteArrayOf(it[0].code.toByte())) } + FrameVectorEntity("a", "s2", byteArrayOf(9)))
        assertNotNull(v.get("a", "s1")); assertNull(v.get("a", "s3")); assertEquals(9, v.get("a", "s2")!!.blob[0].toInt())
        assertEquals(listOf("b", "c"), v.window("s1", 150, 300).map { it.frameId }.sorted())
        assertEquals(listOf("a"), v.window("s2", 0, 1_000).map { it.frameId })
        assertEquals(setOf("a", "c"), v.forFrames("s1", listOf("a", "c", "zz")).map { it.frameId }.toSet())
    }
}
