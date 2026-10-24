package com.rokid.context.memory.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * v10 → v11 (Scenes): two new tables, `scene` and `scene_part`, started from the REAL exported schemas (plain SQLite, Robolectric). An encrypted (SQLCipher) migration run is NOT covered
 * here: that needs the emulator AVD MyEmulator and is a separate androidTest that has not been written or run.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV10to11Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-1011"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
    private val newTables = setOf("scene", "scene_part")

    private fun v10() = helper.createDatabase(name, 10).also { V1Fixture.seedVersion(it, 10); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 11, true, MIGRATION_10_11)

    @Test fun upgradeKeepsEveryV10RowAndCreatesTwoEmptyTables() {
        v10()
        val db = migrate()
        V1Fixture.tables(10).keys.forEach { t -> assertEquals("v10 table $t", 1, V1Fixture.count(db, t)) }
        newTables.forEach { t -> assertTrue(t, V1Fixture.tableNames(db).contains(t)); assertEquals("$t starts empty", 0, V1Fixture.count(db, t)) }
        db.close()
    }

    @Test fun noExistingTableChanged() {
        val v10 = V1Fixture.tables(10); val v11 = V1Fixture.tables(11)
        assertEquals(v10.keys + newTables, v11.keys)
        v10.forEach { (t, cols) -> assertEquals("table $t is unchanged", cols, v11[t]) }
    }

    @Test fun theSceneRowCarriesTheBoundaryRuleTheTriggersTheActivityAndTheRevisions() {
        val cols = V1Fixture.tables(11).getValue("scene").map { it.first }
        assertEquals(listOf("sceneId", "buildRev", "hourStartMs", "startMs", "endMs", "boundaryRule", "startTrigger", "endTrigger", "activity", "membershipRev", "builtAt"), cols)
        assertEquals(listOf("sceneId", "kind", "refId", "partRev", "partTimeMs"), V1Fixture.tables(11).getValue("scene_part").map { it.first })
    }

    @Test fun aScenePartIsKeyedBySceneKindAndReference() {
        v10()
        val db = migrate()
        db.execSQL("INSERT INTO scene (sceneId, buildRev, hourStartMs, startMs, endMs, boundaryRule, membershipRev, builtAt) VALUES ('S1', 1, 0, 0, 1, 'HOUR_END', 1, 1)")
        db.execSQL("INSERT INTO scene_part (sceneId, kind, refId, partRev, partTimeMs) VALUES ('S1', 'MOMENT', '00000001', 'r', 1)")
        db.execSQL("INSERT OR IGNORE INTO scene_part (sceneId, kind, refId, partRev, partTimeMs) VALUES ('S1', 'MOMENT', '00000001', 'r2', 2)")
        assertEquals(1, V1Fixture.count(db, "scene_part"))
        db.close()
    }

    @Test fun theFrozenV10SchemaIsUntouched() {
        assertFalse(V1Fixture.tables(10).containsKey("scene"))
    }

    /** The migration holds only CREATE TABLE / INDEX IF NOT EXISTS: no ALTER, DROP, RENAME, DELETE, UPDATE, INSERT, no copy of rows. */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_10_11"))).map { it.groupValues[1] }.toList()
        assertEquals(6, sql.size)
        sql.forEach { s -> assertTrue(s, s.startsWith("CREATE TABLE IF NOT EXISTS") || s.startsWith("CREATE INDEX IF NOT EXISTS")) }
        sql.forEach { s -> listOf("ALTER", "DROP", "RENAME", "DELETE", "UPDATE", "INSERT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v10(); migrate().close() }

    /** Every canonical lineage file (v1 and v2 originals, v3 to v10) reaches v11 with its rows intact. */
    @Test fun allUpgradePaths1To11() {
        (1..10).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 11, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            newTables.forEach { t -> assertEquals("$t empty", 0, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
