package com.rokid.context.memory.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v3 (canvas) → v4 (wave 2 scene grouping): additive columns on frame_cluster and frame_meta, started from a REAL v3 file. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration3To4Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-34"
    private val canvasTables = listOf("canvas_pipeline", "canvas_pipeline_version", "canvas_run", "canvas_run_item", "canvas_score", "canvas_publish")

    /** One row in every v3 table (frame_*, canvas_*, memory, ...) plus a second, still-open cluster. */
    private fun v3() = helper.createDatabase(name, 3).also {
        V1Fixture.seedVersion(it, 3)
        it.execSQL("INSERT INTO frame_cluster (clusterId, openedAt, closedAt, closeReason, repFrameIds, memoryId) VALUES ('open-c', 5, NULL, NULL, '[]', NULL)")
        it.close()
    }

    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4)

    private fun <T> SupportSQLiteDatabase.one(sql: String, read: (androidx.sqlite.db.SupportSQLiteDatabase, android.database.Cursor) -> T): T =
        query(sql).use { it.moveToFirst(); read(this, it) }

    @Test fun v3RowsSurvive() {
        v3()
        val db = migrate()
        V1Fixture.tables(3).keys.forEach { t -> assertEquals("table $t", if (t == "frame_cluster") 2 else 1, V1Fixture.count(db, t)) }
        db.close()
    }

    @Test fun canvasTablesSurviveWithTheirRows() {
        v3()
        val db = migrate()
        assertTrue(V1Fixture.tableNames(db).containsAll(canvasTables))
        canvasTables.forEach { assertEquals(it, 1, V1Fixture.count(db, it)) }
        assertEquals("v3", db.one("SELECT name FROM canvas_pipeline") { _, c -> c.getString(0) })
        assertEquals("v3", db.one("SELECT pipelineId FROM canvas_run") { _, c -> c.getString(0) })
        db.close()
    }

    @Test fun closedClustersBecomeClosedState() {
        v3()
        val db = migrate()
        assertEquals("CLOSED", db.one("SELECT state FROM frame_cluster WHERE closedAt IS NOT NULL") { _, c -> c.getString(0) })
        assertEquals("OPEN", db.one("SELECT state FROM frame_cluster WHERE clusterId = 'open-c'") { _, c -> c.getString(0) })
        assertEquals(1, db.one("SELECT COUNT(*) FROM frame_cluster WHERE state = 'CLOSED'") { _, c -> c.getInt(0) })
        db.close()
    }

    @Test fun newColumnsHaveDefaults() {
        v3()
        val db = migrate()
        db.query("SELECT attempts, claimId, claimedAt, lastError, updatedAt, motionClass, memberCount, lastTs FROM frame_cluster WHERE clusterId = 'open-c'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2)); assertTrue(it.isNull(3))
            assertEquals(0L, it.getLong(4)); assertTrue(it.isNull(5)); assertEquals(0, it.getInt(6)); assertTrue(it.isNull(7))
        }
        assertTrue(db.one("SELECT routedAt FROM frame_meta") { _, c -> c.isNull(0) })
        // The v3 meta row keeps its own values.
        assertEquals("v3", db.one("SELECT wireFrameId FROM frame_meta") { _, c -> c.getString(0) })
        // A fresh cluster insert that omits the new columns gets the SQL defaults (an old writer would).
        db.execSQL("INSERT INTO frame_cluster (clusterId, openedAt, repFrameIds) VALUES ('n', 1, '[]')")
        assertEquals("OPEN", db.one("SELECT state FROM frame_cluster WHERE clusterId = 'n'") { _, c -> c.getString(0) })
        db.close()
    }

    @Test fun indicesExist() {
        v3()
        val db = migrate()
        val idx = db.query("SELECT name FROM sqlite_master WHERE type='index'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(idx.containsAll(listOf("index_frame_cluster_state", "index_frame_meta_clusterId")))
        db.close()
    }

    @Test fun noOtherTableChanged() {
        val v3 = V1Fixture.tables(3); val v4 = V1Fixture.tables(4)
        assertEquals(v3.keys, v4.keys)
        v3.forEach { (t, cols) ->
            if (t == "frame_cluster" || t == "frame_meta") assertEquals("existing columns of $t keep their order", cols, v4[t]!!.take(cols.size))
            else assertEquals("columns of $t", cols, v4[t])
        }
        assertEquals(listOf("state", "attempts", "claimId", "claimedAt", "lastError", "updatedAt", "motionClass", "memberCount", "lastTs"), v4["frame_cluster"]!!.drop(v3["frame_cluster"]!!.size).map { it.first })
        assertEquals(listOf("routedAt"), v4["frame_meta"]!!.drop(v3["frame_meta"]!!.size).map { it.first })
    }

    @Test fun migratedMatchesExportedSchema() {
        v3()
        migrate().close()
    }

    @Test fun v2ToV4Chain() {
        helper.createDatabase(name, 2).also { V1Fixture.seedVersion(it, 2); it.close() }
        val db = helper.runMigrationsAndValidate(name, 4, true, MIGRATION_2_3, MIGRATION_3_4)
        V1Fixture.tables(2).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(db, it)) }
        assertEquals("CLOSED", db.one("SELECT state FROM frame_cluster") { _, c -> c.getString(0) })
        assertTrue(V1Fixture.tableNames(db).containsAll(canvasTables))
        db.close()
    }

    @Test fun v1ToV4Chain() {
        helper.createDatabase(name, 1).also { V1Fixture.seed(it); it.close() }
        val db = helper.runMigrationsAndValidate(name, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        V1Fixture.tables(1).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(db, it)) }
        assertTrue(V1Fixture.tableNames(db).containsAll(canvasTables))
        db.close()
    }
}
