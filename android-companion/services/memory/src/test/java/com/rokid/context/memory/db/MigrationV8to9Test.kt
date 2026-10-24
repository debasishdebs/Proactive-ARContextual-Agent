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
 * v8 → v9 (Moment model phase 1): five new tables, nothing else, started from the REAL exported schemas (plain SQLite, Robolectric). An encrypted (SQLCipher) migration run is
 * NOT covered here: that needs the emulator AVD MyEmulator and is a separate androidTest that has not been written or run.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV8to9Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-89"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
    private val newTables = setOf("moment", "moment_part", "moment_tombstone", "moment_trace_dep", "cleanup_outbox")

    private fun v8() = helper.createDatabase(name, 8).also { V1Fixture.seedVersion(it, 8); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 9, true, MIGRATION_8_9)

    @Test fun upgradeKeepsEveryV8RowAndCreatesFiveEmptyTables() {
        v8()
        val db = migrate()
        V1Fixture.tables(8).keys.forEach { t -> assertEquals("v8 table $t", 1, V1Fixture.count(db, t)) }
        newTables.forEach { t -> assertTrue(t, V1Fixture.tableNames(db).contains(t)); assertEquals("$t starts empty", 0, V1Fixture.count(db, t)) }
        db.close()
    }

    @Test fun noExistingTableChanged() {
        val v8 = V1Fixture.tables(8); val v9 = V1Fixture.tables(9)
        assertEquals(v8.keys + newTables, v9.keys)
        v8.forEach { (t, cols) -> assertEquals("table $t is unchanged", cols, v9[t]) }
    }

    @Test fun theFrozenV8SchemaIsUntouched() {
        val v8 = V1Fixture.tables(8)
        assertFalse(v8.containsKey("moment")); assertFalse(v8.containsKey("cleanup_outbox"))
        assertEquals(listOf("cleanupId", "jobId", "reason", "createdAt"), v8["canvas_helper_cleanup"]!!.map { it.first })
    }

    @Test fun outboxIsUniqueOnTargetAndPayload() {
        v8()
        val db = migrate()
        db.execSQL("INSERT INTO cleanup_outbox (target, payloadJson, createdAt) VALUES ('PHONE_FILE', 'a', 1)")
        db.execSQL("INSERT OR IGNORE INTO cleanup_outbox (target, payloadJson, createdAt) VALUES ('PHONE_FILE', 'a', 2)")
        assertEquals(1, V1Fixture.count(db, "cleanup_outbox"))
        db.close()
    }

    /** The migration source holds only CREATE TABLE / CREATE INDEX IF NOT EXISTS: no ALTER, DROP, RENAME, DELETE, UPDATE, INSERT, no copy of rows. */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_8_9"))).map { it.groupValues[1] }.toList()
        assertEquals(10, sql.size)
        sql.forEach { s -> assertTrue(s, s.startsWith("CREATE TABLE IF NOT EXISTS") || s.startsWith("CREATE INDEX IF NOT EXISTS") || s.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS")) }
        sql.forEach { s -> listOf("ALTER", "DROP", "RENAME", "DELETE", "UPDATE", "INSERT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v8(); migrate().close() }

    /** Every canonical lineage file (v1 and v2 originals, v3 to v8) reaches v9 with its rows intact. */
    @Test fun allUpgradePaths1To9() {
        (1..8).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 9, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            newTables.forEach { t -> assertEquals("$t empty", 0, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
