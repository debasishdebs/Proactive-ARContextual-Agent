package com.rokid.context.memory.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration2To3Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-23"
    private val canvasTables = listOf("canvas_pipeline", "canvas_pipeline_version", "canvas_run", "canvas_run_item", "canvas_score", "canvas_publish")

    private fun v2() = helper.createDatabase(name, 2).also { V1Fixture.seedVersion(it, 2); it.close() }

    @Test fun v2RowsSurvive() {
        v2()
        val db = helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3)
        V1Fixture.tables(2).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(db, it)) }
        db.close()
    }

    @Test fun sixCanvasTablesExistEmpty() {
        v2()
        val db = helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3)
        assertTrue(V1Fixture.tableNames(db).containsAll(canvasTables))
        canvasTables.forEach { assertEquals(it, 0, V1Fixture.count(db, it)) }
        db.close()
    }

    @Test fun noV2TableChanged() {
        val v2 = V1Fixture.tables(2); val v3 = V1Fixture.tables(3)
        v2.forEach { (t, cols) -> assertEquals("columns of $t", cols, v3[t]) }
        assertEquals(canvasTables.toSet(), v3.keys - v2.keys)
    }

    @Test fun migratedMatchesExportedSchema() {
        v2()
        helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).close()
    }

    @Test fun v1ToV3Chain() {
        helper.createDatabase(name, 1).also { V1Fixture.seed(it); it.close() }
        val db = helper.runMigrationsAndValidate(name, 3, true, MIGRATION_1_2, MIGRATION_2_3)
        V1Fixture.tables(1).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(db, it)) }
        assertTrue(V1Fixture.tableNames(db).containsAll(canvasTables))
        db.close()
    }
}
