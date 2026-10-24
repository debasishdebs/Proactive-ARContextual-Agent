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
 * v9 → v10 (Moment building blocks part 2): four columns on `canvas_trace` (phone-clock interval) and two new tables (`object_audit`, `object_derived`), started from the REAL exported
 * schemas (plain SQLite, Robolectric). An encrypted (SQLCipher) migration run is NOT covered here: that needs the emulator AVD MyEmulator and is a separate androidTest that has not been written or run.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV9to10Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-910"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
    private val newTables = setOf("object_audit", "object_derived")

    private fun v9() = helper.createDatabase(name, 9).also { V1Fixture.seedVersion(it, 9); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 10, true, MIGRATION_9_10)

    @Test fun upgradeKeepsEveryV9RowAndCreatesTwoEmptyTables() {
        v9()
        val db = migrate()
        V1Fixture.tables(9).keys.forEach { t -> assertEquals("v9 table $t", 1, V1Fixture.count(db, t)) }
        newTables.forEach { t -> assertTrue(t, V1Fixture.tableNames(db).contains(t)); assertEquals("$t starts empty", 0, V1Fixture.count(db, t)) }
        db.close()
    }

    /** A pre-v10 trace row is conservative until the first-run backfill maps it: UNMAPPED, no phone end, nothing lost. */
    @Test fun existingTraceRowReadsUnmappedAndKeepsItsGlassesTimes() {
        v9()
        val db = migrate()
        db.query("SELECT clockQuality, phoneStartMs, phoneEndMs, uncertaintyMs, startedAtMs FROM canvas_trace").use {
            assertTrue(it.moveToFirst())
            assertEquals("UNMAPPED", it.getString(0)); assertEquals(0L, it.getLong(1)); assertTrue(it.isNull(2)); assertEquals(0L, it.getLong(3)); assertEquals(1L, it.getLong(4))
        }
        db.close()
    }

    @Test fun onlyCanvasTraceChangedAndOnlyByFourColumns() {
        val v9 = V1Fixture.tables(9); val v10 = V1Fixture.tables(10)
        assertEquals(v9.keys + newTables, v10.keys)
        v9.forEach { (t, cols) ->
            if (t == "canvas_trace") assertEquals(cols.map { it.first } + listOf("phoneStartMs", "phoneEndMs", "clockQuality", "uncertaintyMs"), v10[t]!!.map { it.first })
            else assertEquals("table $t is unchanged", cols, v10[t])
        }
    }

    @Test fun theFrozenV9SchemaIsUntouched() {
        val cols = V1Fixture.tables(9)["canvas_trace"]!!.map { it.first }
        assertFalse(cols.contains("phoneStartMs")); assertFalse(V1Fixture.tables(9).containsKey("object_audit"))
    }

    @Test fun auditIsKeyedByObjectAndSeqAndDerivedByObjectAndField() {
        v9()
        val db = migrate()
        db.execSQL("INSERT INTO object_audit (objectKind, objectId, seq, op, atMs, step) VALUES ('MOMENT', '00000001', 1, 'BUILT', 1, 'build')")
        db.execSQL("INSERT OR IGNORE INTO object_audit (objectKind, objectId, seq, op, atMs, step) VALUES ('MOMENT', '00000001', 1, 'REBUILT', 2, 'build')")
        assertEquals(1, V1Fixture.count(db, "object_audit"))
        db.execSQL("INSERT INTO object_derived (objectKind, objectId, field, value, builtRev, atMs) VALUES ('MOMENT', '00000001', 'SUMMARY', 's', 1, 1)")
        db.execSQL("INSERT OR IGNORE INTO object_derived (objectKind, objectId, field, value, builtRev, atMs) VALUES ('MOMENT', '00000001', 'SUMMARY', 't', 1, 1)")
        assertEquals(1, V1Fixture.count(db, "object_derived"))
        db.close()
    }

    /** The migration holds only ADD COLUMN and CREATE TABLE / INDEX IF NOT EXISTS: no DROP, RENAME, DELETE, UPDATE, INSERT, no copy of rows. */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_9_10"))).map { it.groupValues[1] }.toList()
        assertEquals(9, sql.size)
        sql.forEach { s -> assertTrue(s, s.startsWith("ALTER TABLE `canvas_trace` ADD COLUMN") || s.startsWith("CREATE TABLE IF NOT EXISTS") || s.startsWith("CREATE INDEX IF NOT EXISTS")) }
        sql.forEach { s -> listOf("DROP", "RENAME", "DELETE", "UPDATE", "INSERT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v9(); migrate().close() }

    /** Every canonical lineage file (v1 and v2 originals, v3 to v9) reaches v10 with its rows intact. */
    @Test fun allUpgradePaths1To10() {
        (1..9).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 10, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            newTables.forEach { t -> assertEquals("$t empty", 0, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
