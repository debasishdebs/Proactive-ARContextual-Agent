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

    @Test fun wipeClearsLiveTrace() = runBlocking {
        val c = db.canvasDao()
        c.upsertTrace(listOf(CanvasLiveTraceEntity(1, "f", "_batch", "LIVE", "OK", null, "{}", null, "b", 1), CanvasLiveTraceEntity(1, "g", "n", "SHADOW", "OK", null, null, null, null, 2)))
        c.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 1, hash = "h", publishedAt = 1, rolledBackAt = null, snapshotJson = "{}", kind = "interpreter", mode = "LIVE"))
        db.wipeDao().wipeMemory()
        val raw = db.openHelper.writableDatabase
        assertEquals(0, rowCount(raw, "canvas_live_trace"))
        assertEquals("the publish itself holds no photo data and stays", 1, rowCount(raw, "canvas_publish"))
    }

    private val wipedSlice3 = listOf("canvas_trace", "canvas_trace_event", "canvas_replay", "canvas_sweep", "canvas_label", "canvas_calibration", "canvas_helper_job")
    private val keptSlice3 = listOf("canvas_meta", "canvas_group_state", "canvas_helper_cleanup")

    private suspend fun seedSlice3() {
        val s = db.canvasSlice3Dao()
        val epoch = DeletionEpoch("i", 1, 0)
        val fence = DaoFence(0, epoch, { 0 }, { epoch })
        s.acceptTraceFenced(CanvasTraceEntity("t1", "HUB", "COMPLETE", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = 1, createdAt = 1), fence)
        s.appendEventsFenced("t1", listOf(CanvasTraceEventEntity("t1", 0, 1, "CTX", "{}")), fence)
        s.insertReplay(CanvasReplayEntity("r1", null, "{}", "EXACT", "{}", createdAt = 1, jobState = "DONE")); s.insertSweep(CanvasSweepEntity("sw1", "{}", resultJson = "{}", jobState = "DONE", createdAt = 1))
        s.insertLabelFenced(CanvasLabelEntity("l1", "MOMENT", "tap", "X", 5, createdAt = 1), fence)
        s.insertCalibration(CanvasCalibrationEntity(configHash = "c", settingsRev = 1, mode = "LIVE", phasesJson = "[]", createdAt = 1))
        s.insertHelperJob(CanvasHelperJobEntity("j1", "py", "QUEUED", frameIdsJson = "[\"f1\"]", createdAt = 1, updatedAt = 1))
        s.cancelHelperJob("j1", 2) // the job row stays (CANCELLED) and one cleanup row is enqueued: Delete all keeps that row, the laptop copies must still be deleted
        s.upsertGroupState(CanvasGroupStateEntity("g1", 1, 1, "c", "capture_plan", "EFFECTIVE", 1))
    }

    /** Delete all reaches every slice 3a table that can hold derived data; the three that hold none (meta, group state, the helper cleanup commands) are kept on purpose, and labelRev still moves. */
    @Test fun wipeClearsSlice3TablesAndKeepsTheRecoveryRecord() = runBlocking {
        seedSlice3()
        val raw = db.openHelper.writableDatabase
        wipedSlice3.forEach { assertEquals("seeded $it", 1, rowCount(raw, it)) }
        val before = db.canvasSlice3Dao().labelRev()
        db.wipeDao().wipeMemory()
        wipedSlice3.forEach { assertEquals(it, 0, rowCount(raw, it)) }
        keptSlice3.filter { it != "canvas_helper_cleanup" }.forEach { assertEquals("$it is kept by design", 1, rowCount(raw, it)) }
        assertEquals("canvas_helper_cleanup is kept by design: the row seeded before the wipe plus the wildcard row Delete all adds (3b WP4)", 2, rowCount(raw, "canvas_helper_cleanup"))
        assertEquals("labelRev stays monotonic and moves, so nothing computed earlier reads as current", before + 1, db.canvasSlice3Dao().labelRev())
    }

    /** A table added later must be classified here (wiped or kept by design) or this fails: the wipe cannot silently miss a new table. */
    @Test fun everyCanvasTableIsClassifiedForDeleteAll() {
        val preExisting = setOf("canvas_pipeline", "canvas_pipeline_version", "canvas_publish", "canvas_run", "canvas_run_item", "canvas_score", "canvas_live_trace")
        val raw = db.openHelper.readableDatabase
        val actual = raw.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'canvas\\_%' ESCAPE '\\'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(preExisting + wipedSlice3 + keptSlice3, actual)
    }

    private fun rowCount(raw: androidx.sqlite.db.SupportSQLiteDatabase, t: String) = raw.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
}
