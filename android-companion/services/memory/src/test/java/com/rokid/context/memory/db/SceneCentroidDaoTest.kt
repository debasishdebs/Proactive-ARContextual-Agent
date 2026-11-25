package com.rokid.context.memory.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SceneCentroidDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val dao = db.sceneCentroidDao()
    @After fun close() = db.close()

    private fun row(scene: String, space: String = "e1", rev: Int = 1, fill: Byte = 1, n: Int = 3) = SceneCentroidEntity(scene, space, rev, 2, ByteArray(8) { fill }, n, 5)

    @Test fun upsertGetAndReplaceOnRebuild() = runBlocking {
        dao.upsert(row("s1", fill = 1)); dao.upsert(row("s1", "e2")); dao.upsert(row("s2"))
        assertEquals(3, dao.count())
        assertArrayEquals(ByteArray(8) { 1 }, dao.get("s1", "e1")!!.vector)
        dao.upsert(row("s1", rev = 2, fill = 9, n = 4)) // a rebuild replaces the (scene, space) row
        assertEquals(3, dao.count())
        val got = dao.get("s1", "e1")!!
        assertEquals(2, got.membershipRev); assertEquals(4, got.memberCount); assertArrayEquals(ByteArray(8) { 9 }, got.vector)
        assertNull(dao.get("s1", "nope"))
        assertEquals(listOf("e1", "e2"), dao.getForScene("s1").map { it.space })
    }

    @Test fun deleteForSceneScenesAndAll() = runBlocking {
        listOf("s1", "s2", "s3").forEach { dao.upsert(row(it)); dao.upsert(row(it, "e2")) }
        assertEquals(2, dao.deleteForScene("s1"))
        assertEquals(4, dao.deleteForScenes(listOf("s2", "s3", "zz")))
        assertEquals(0, dao.count())
        listOf("s1", "s2").forEach { dao.upsert(row(it)) }
        assertEquals(2, dao.deleteAll()); assertEquals(0, dao.count())
    }

    @Test fun deleteStaleKeepsOnlyCurrentRevisionsOfExistingScenes() = runBlocking {
        val sd = db.sceneDao()
        fun scene(id: String) = SceneEntity(id, 1, 0L, 0, 999, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L)
        sd.upsertScene(scene("s1")); sd.upsertScene(scene("s2"))
        val rev = sd.scene("s1")!!.membershipRev
        dao.upsert(row("s1", rev = rev)); dao.upsert(row("s2", rev = rev + 1)); dao.upsert(row("gone", rev = rev))
        assertEquals(2, dao.deleteStale())
        assertEquals(listOf("s1"), dao.getForScene("s1").map { it.sceneId }); assertEquals(1, dao.count())
    }

    @Test fun removingAScenesDeletesItsCentroids() = runBlocking {
        val sd = db.sceneDao()
        sd.upsertScene(SceneEntity("s1", 1, 0L, 0, 999, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L)); sd.upsertScene(SceneEntity("s2", 1, 0L, 0, 999, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L))
        dao.upsert(row("s1")); dao.upsert(row("s1", "e2")); dao.upsert(row("s2"))
        sd.remove(listOf("s1"))
        assertEquals(listOf("s2"), listOf("s1", "s2").flatMap { dao.getForScene(it) }.map { it.sceneId })
    }
}
