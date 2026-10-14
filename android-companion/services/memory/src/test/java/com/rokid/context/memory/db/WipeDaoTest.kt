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
class WipeDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    @Test fun deleteAllClearsFrameTablesKeepsNotes() = runBlocking {
        db.frameDao().insertIgnore(FrameEntity("f1", 1, "/f1.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity("f1", "w1", "s", "UNKNOWN", false, null, null, null, 640, null, null, null, null, null, 1))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity("f1", "PENDING", 0, null, null, null, 1)))
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("m1", "f1", null, "FACT")))
        db.frameClusterDao().upsert(FrameClusterEntity("c1", 1, null, null, "[]", null))
        db.frameVectorDao().upsert(listOf(FrameVectorEntity("f1", "s", byteArrayOf(1))))
        db.noteDao().insertIgnore(NoteEntity("n1", "keep me", 1))
        db.wipeDao().wipeMemory()
        val raw = db.openHelper.writableDatabase
        listOf("frame", "frame_meta", "frame_state", "frame_source", "frame_cluster", "frame_vector").forEach { assertEquals(it, 0, rowCount(raw, it)) }
        assertEquals(1, rowCount(raw, "note"))
    }

    private fun rowCount(raw: androidx.sqlite.db.SupportSQLiteDatabase, t: String) = raw.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
}
