package com.rokid.context.memory.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v6 → v7 (canvas slice 3a, migration A): seven additive publish columns and nine new tables, started from REAL exported-schema files. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration6To7Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-67"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
    private val newTables = listOf("canvas_trace", "canvas_trace_event", "canvas_replay", "canvas_sweep", "canvas_label", "canvas_meta", "canvas_calibration", "canvas_group_state", "canvas_helper_job")
    private val newPublishCols = listOf("groupId" to "TEXT", "component" to "TEXT", "planJson" to "TEXT", "configHash" to "TEXT", "gen" to "INTEGER", "state" to "TEXT", "trialEndsAt" to "INTEGER")

    private fun v6() = helper.createDatabase(name, 6).also { V1Fixture.seedVersion(it, 6); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 7, true, MIGRATION_6_7)

    @Test fun oldRowsSurviveAndNewColumnsAreNull() {
        v6()
        val db = migrate()
        V1Fixture.tables(6).keys.forEach { t -> assertEquals("table $t", 1, V1Fixture.count(db, t)) }
        db.query("SELECT pipelineId, kind, groupId, component, planJson, configHash, gen, state, trialEndsAt FROM canvas_publish").use {
            assertTrue(it.moveToFirst()); assertEquals("v6", it.getString(0)); assertEquals("v6", it.getString(1))
            (2..8).forEach { i -> assertTrue("col $i null", it.isNull(i)) }
        }
        // An old writer that omits the new columns still inserts.
        db.execSQL("INSERT INTO canvas_publish (pipelineId, version, hash, publishedAt, snapshotJson) VALUES ('p', 1, 'h', 1, '{}')")
        assertEquals("prompts", db.query("SELECT kind FROM canvas_publish WHERE pipelineId = 'p'").use { it.moveToFirst(); it.getString(0) })
        db.close()
    }

    @Test fun newTablesExistEmptyWithTheirIndices() {
        v6()
        val db = migrate()
        newTables.forEach { t -> assertTrue("table $t", V1Fixture.tableNames(db).contains(t)); assertEquals("table $t empty", 0, V1Fixture.count(db, t)) }
        val idx = db.query("SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'index_canvas_%'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(idx.containsAll(listOf(
            "index_canvas_trace_startedAtMs", "index_canvas_trace_parentTraceId", "index_canvas_trace_event_tMs", "index_canvas_replay_sweepId", "index_canvas_replay_createdAt",
            "index_canvas_sweep_createdAt", "index_canvas_label_atMs", "index_canvas_label_frameId", "index_canvas_calibration_configHash", "index_canvas_helper_job_state",
        )))
        // The new tables' defaults are in force for a writer that omits them.
        db.execSQL("INSERT INTO canvas_trace (traceId, source, status, manifestJson, wipeGen, startedAtMs, createdAt) VALUES ('t', 'IMU', 'COMPLETE', '{}', 0, 1, 1)")
        db.query("SELECT tombstoned, revision, installId, deletionCounter, exact FROM canvas_trace").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(0, it.getInt(1)); assertEquals("", it.getString(2)); assertEquals(0L, it.getLong(3)); assertEquals(1, it.getInt(4))
        }
        db.close()
    }

    @Test fun noOtherTableChanged() {
        val v6 = V1Fixture.tables(6); val v7 = V1Fixture.tables(7)
        assertEquals(v6.keys + newTables, v7.keys)
        v6.forEach { (t, cols) ->
            val extra = if (t == "canvas_publish") newPublishCols else emptyList()
            assertEquals("existing columns of $t keep their order", cols, v7[t]!!.take(cols.size))
            assertEquals("added columns of $t", extra, v7[t]!!.drop(cols.size))
        }
        assertEquals(7, newPublishCols.size)
        assertEquals(listOf("groupId", "gen", "publishId", "configHash", "component", "state", "updatedAt").toSet(), v7["canvas_group_state"]!!.map { it.first }.toSet())
        assertEquals(listOf("key", "value"), v7["canvas_meta"]!!.map { it.first })
        assertTrue("canvas_label has no text column", v7["canvas_label"]!!.map { it.first }.none { it.equals("text", true) || it.equals("transcript", true) })
        assertEquals(listOf("traceId", "source", "status", "manifestJson", "wipeGen", "tombstoned", "revision", "parentTraceId", "installId", "deletionCounter", "exact", "startedAtMs", "endedAtMs", "createdAt"), v7["canvas_trace"]!!.map { it.first })
        listOf("canvas_replay", "canvas_sweep").forEach { t -> assertTrue("$t has provenance and job state", v7[t]!!.map { it.first }.containsAll(listOf("sourcesJson", "labelRev", "jobState"))) }
    }

    @Test fun migratedMatchesExportedSchema() { v6(); migrate().close() }

    /** Every canonical lineage file (and the original v1 and v2) reaches v7 with its rows intact. */
    @Test fun migratesFromEachCanonicalSchema() {
        (1..6).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 7, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            newTables.forEach { t -> assertTrue(V1Fixture.tableNames(db).contains(t)); assertEquals(0, V1Fixture.count(db, t)) }
            if (from >= 3) assertTrue(db.query("SELECT groupId FROM canvas_publish").use { it.moveToFirst(); it.isNull(0) })
            db.close()
        }
    }
}
