package com.rokid.context.memory.forget

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The REAL wipe against a real (in-memory) Room database: every `canvas_*` table the registry says is WIPED is seeded with a row (a table with no seed fails here, so a new
 * table cannot be registered without proving the wipe empties it), the wipe runs, and every WIPED table is empty and every KEPT one survives. Deleting a call from
 * `WipeDao.wipeMemory` fails this even if its DELETE statement is still declared (the SQL-text scan alone would not notice).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeletionGraphWipeTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private fun rows(raw: SupportSQLiteDatabase, t: String) = raw.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun seedEveryWipedTable() {
        val e = DeletionEpoch("i", 1, 0); val f = DaoFence(0, e, { 0 }, { e })
        val s = db.canvasSlice3Dao(); val c = db.canvasDao()
        s.acceptTraceFenced(CanvasTraceEntity("trace-wipe-01", "HUB", "COMPLETE", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = 1_000_000, endedAtMs = 1_000_100, createdAt = 1), f)
        s.appendEventsFenced("trace-wipe-01", listOf(CanvasTraceEventEntity("trace-wipe-01", 0, 1, "CTX", "{}")), f)
        s.insertReplay(CanvasReplayEntity("r1", null, "{}", "EXACT", "{}", createdAt = 1, jobState = "DONE")); s.insertSweep(CanvasSweepEntity("sw1", "{}", resultJson = "{}", jobState = "DONE", createdAt = 1))
        s.insertLabelFenced(CanvasLabelEntity("l1", "MOMENT", "tap", "X", 5, createdAt = 1), f)
        s.insertCalibration(CanvasCalibrationEntity(configHash = "c", settingsRev = 1, mode = "LIVE", phasesJson = "[]", createdAt = 1))
        s.insertHelperJob(CanvasHelperJobEntity("j1", "py", "QUEUED", frameIdsJson = "[\"f1\"]", createdAt = 1, updatedAt = 1))
        s.upsertGroupState(CanvasGroupStateEntity("g1", 1, 1, "c", "capture_plan", "EFFECTIVE", 1))
        c.upsertPipeline(CanvasPipelineEntity("p", "p", "{}", 0, false, 1, 1)); c.insertVersion(CanvasPipelineVersionEntity("p", 1, "{}", "h", "", 1))
        c.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 1, hash = "h", publishedAt = 1, rolledBackAt = null, snapshotJson = "{}"))
        c.insertRun(CanvasRunEntity("r", "p", 1, "h", "{}", "DONE", 1, 1, 1, 0, 1, 2, "[]"))
        c.upsertItem(CanvasRunItemEntity("r", "f", "n", "OK", null, "{}", null, null, null, null, null, null, null)); c.upsertScore(CanvasScoreEntity("r", "f", "n", 1, "", 1))
        c.upsertTrace(listOf(CanvasLiveTraceEntity(1, "f", "_batch", "LIVE", "OK", null, "{}", null, "b", 1)))
        s.forgetRangeTx(100, 200, 1) // writes the forget-range fence into canvas_meta
    }

    @Test fun theRealWipeEmptiesEveryRegisteredWipedTableAndKeepsTheKeptOnes() = runBlocking {
        seedEveryWipedTable()
        val raw = db.openHelper.writableDatabase
        val unseeded = DeletionGraph.wipedTables.filter { rows(raw, it) == 0 }
        assertTrue("registered WIPED tables with no seed row in this test (seed them, or the wipe is unproven): $unseeded", unseeded.isEmpty())
        db.wipeDao().wipeMemory()
        DeletionGraph.wipedTables.forEach { assertEquals("$it must be empty after Delete all", 0, rows(raw, it)) }
        DeletionGraph.TABLES.filter { it.fate == DeletionGraph.Fate.KEPT }.map { it.name }.filter { it != "canvas_meta" }.forEach { assertEquals("$it is kept by design", 1, rows(raw, it).coerceAtMost(1)) }
        assertTrue("the forget-range fence value is emptied (the epoch covers what came before)", db.canvasSlice3Dao().forgottenRanges().isEmpty())
    }
}
