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
class Migration1To2Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test"

    private fun v1() = helper.createDatabase(name, 1).also { V1Fixture.seed(it); it.close() }

    @Test fun v1RowsSurvive() {
        v1()
        val db = helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2)
        V1Fixture.tables(1).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(db, it)) }
        db.close()
    }

    @Test fun fiveTablesExistAfterMigration() {
        v1()
        val db = helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2)
        assertTrue(V1Fixture.tableNames(db).containsAll(V1Fixture.FRAME_TABLES))
        V1Fixture.FRAME_TABLES.forEach { assertEquals(0, V1Fixture.count(db, it)) }
        db.close()
    }

    @Test fun noV1TableChanged() {
        val v1 = V1Fixture.tables(1); val v2 = V1Fixture.tables(2)
        v1.forEach { (t, cols) -> assertEquals("columns of $t", cols, v2[t]) }
        assertEquals(V1Fixture.FRAME_TABLES.toSet(), v2.keys - v1.keys)
        // and the migrated file agrees with the exported v2 schema (validated), with the v1 tables still named as before
        v1()
        val db = helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2)
        assertTrue(V1Fixture.tableNames(db).containsAll(v1.keys))
        db.close()
    }
}
