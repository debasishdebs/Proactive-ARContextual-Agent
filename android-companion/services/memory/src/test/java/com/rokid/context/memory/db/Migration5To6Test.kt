package com.rokid.context.memory.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v5 → v6 (canvas slice 2): five additive columns and one new table (`canvas_live_trace`), started from REAL exported-schema files. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration5To6Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-56"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

    private fun v5() = helper.createDatabase(name, 5).also { V1Fixture.seedVersion(it, 5); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 6, true, MIGRATION_5_6)

    @Test fun oldRowsSurviveWithDefaults() {
        v5()
        val db = migrate()
        V1Fixture.tables(5).keys.forEach { t -> assertEquals("table $t", 1, V1Fixture.count(db, t)) }
        db.query("SELECT pipelineId, kind, mode, rollbackReason FROM canvas_publish").use {
            assertTrue(it.moveToFirst()); assertEquals("v5", it.getString(0)); assertEquals("prompts", it.getString(1))
            assertTrue(it.isNull(2)); assertTrue(it.isNull(3))
        }
        assertTrue(db.query("SELECT abGroup FROM canvas_run").use { it.moveToFirst(); it.isNull(0) })
        assertTrue(db.query("SELECT calls FROM canvas_run_item").use { it.moveToFirst(); it.isNull(0) })
        // An old writer that omits the new columns still inserts, and kind defaults.
        db.execSQL("INSERT INTO canvas_publish (pipelineId, version, hash, publishedAt, snapshotJson) VALUES ('p', 1, 'h', 1, '{}')")
        assertEquals("prompts", db.query("SELECT kind FROM canvas_publish WHERE pipelineId = 'p'").use { it.moveToFirst(); it.getString(0) })
        db.close()
    }

    @Test fun traceTableExistsEmpty() {
        v5()
        val db = migrate()
        assertTrue(V1Fixture.tableNames(db).contains("canvas_live_trace"))
        assertEquals(0, V1Fixture.count(db, "canvas_live_trace"))
        val idx = db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='canvas_live_trace'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(idx.containsAll(listOf("index_canvas_live_trace_frameId", "index_canvas_live_trace_batchId", "index_canvas_live_trace_at")))
        db.close()
    }

    @Test fun noOtherTableChanged() {
        val v5 = V1Fixture.tables(5); val v6 = V1Fixture.tables(6)
        assertEquals(v5.keys + "canvas_live_trace", v6.keys)
        val added = mapOf("canvas_publish" to listOf("kind" to "TEXT", "mode" to "TEXT", "rollbackReason" to "TEXT"), "canvas_run" to listOf("abGroup" to "TEXT"), "canvas_run_item" to listOf("calls" to "INTEGER"))
        v5.forEach { (t, cols) ->
            val extra = added[t].orEmpty()
            assertEquals("existing columns of $t keep their order", cols, v6[t]!!.take(cols.size))
            assertEquals("added columns of $t", extra, v6[t]!!.drop(cols.size))
        }
        assertEquals(5, added.values.sumOf { it.size })
        assertEquals(listOf("publishId", "frameId", "nodeId", "mode", "status", "branch", "outputJson", "error", "batchId", "at"), v6["canvas_live_trace"]!!.map { it.first })
    }

    @Test fun migratedMatchesExportedSchema() { v5(); migrate().close() }

    /** Every canonical lineage file (and the original v1 and v2) reaches v6 with its rows intact. */
    @Test fun migratesFromEachCanonicalSchema() {
        (1..5).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 6, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            assertTrue(V1Fixture.tableNames(db).contains("canvas_live_trace"))
            assertEquals(0, V1Fixture.count(db, "canvas_live_trace"))
            if (from >= 3) assertEquals("prompts", db.query("SELECT kind FROM canvas_publish").use { it.moveToFirst(); it.getString(0) })
            db.close()
        }
    }
}
