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

    @Test fun wipeClearsCanvasRunsKeepsPipelines() = runBlocking {
        val c = db.canvasDao()
        c.upsertPipeline(CanvasPipelineEntity("p", "p", "{}", 0, false, 1, 1))
        c.insertVersion(CanvasPipelineVersionEntity("p", 1, "{}", "h", "", 1))
        c.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 1, hash = "h", publishedAt = 1, rolledBackAt = null, snapshotJson = "{}"))
        c.insertRun(CanvasRunEntity("r", "p", 1, "h", "{}", "DONE", 1, 1, 1, 0, 1, 2, "[]"))
        c.upsertItem(CanvasRunItemEntity("r", "f", "n", "OK", null, "{}", null, null, null, null, null, null, null))
        c.upsertScore(CanvasScoreEntity("r", "f", "n", 1, "", 1))
        db.wipeDao().wipeMemory()
        val raw = db.openHelper.writableDatabase
        listOf("canvas_run", "canvas_run_item", "canvas_score").forEach { assertEquals(it, 0, rowCount(raw, it)) }
        listOf("canvas_pipeline", "canvas_pipeline_version", "canvas_publish").forEach { assertEquals(it, 1, rowCount(raw, it)) }
    }

    private fun rowCount(raw: androidx.sqlite.db.SupportSQLiteDatabase, t: String) = raw.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
}
