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
 * v13 -> v14 (Moments carry text): three additive nullable columns on `moment` (`text`, `transcript`, `errorReason`), started from the REAL exported schemas (plain SQLite, Robolectric). An
 * encrypted (SQLCipher) migration run is NOT covered here (needs the emulator AVD, a separate androidTest that has not been written or run).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV13to14Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-1314"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)

    private fun v13() = helper.createDatabase(name, 13).also { V1Fixture.seedVersion(it, 13); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 14, true, MIGRATION_13_14)

    @Test fun everyExistingMomentHasNoTextNoTranscriptAndNoErrorReason() {
        v13()
        val db = migrate()
        db.query("SELECT text, transcript, errorReason FROM moment").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2)) }
        V1Fixture.tables(13).keys.forEach { t -> assertEquals("v13 table $t", 1, V1Fixture.count(db, t)) }
        db.close()
    }

    @Test fun onlyThreeColumnsWereAddedAndNoTable() {
        val v13 = V1Fixture.tables(13); val v14 = V1Fixture.tables(14)
        assertEquals(v13.keys, v14.keys)
        v13.forEach { (t, cols) ->
            val extra = if (t == "moment") listOf("text", "transcript", "errorReason") else emptyList()
            assertEquals("table $t", cols.map { it.first } + extra, v14.getValue(t).map { it.first })
        }
    }

    @Test fun theFrozenV13SchemaIsUntouched() {
        assertFalse(V1Fixture.tables(13).getValue("moment").any { it.first == "text" })
    }

    /** The migration holds only ALTER TABLE ... ADD COLUMN: no CREATE, DROP, RENAME, DELETE, UPDATE, INSERT, no copy of rows (the purge is NOT a schema migration: it runs once after the upgrade). */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_13_14"), src.indexOf("val MIGRATION_14_15"))).map { it.groupValues[1] }.toList()
        assertEquals(3, sql.size)
        sql.forEach { s -> assertTrue(s, Regex("^ALTER TABLE `\\w+` ADD COLUMN ").containsMatchIn(s)) }
        sql.forEach { s -> listOf("DROP", "RENAME", "DELETE", "UPDATE", "INSERT", "CREATE").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v13(); migrate().close() }

    /** Every canonical lineage file reaches v14 with its rows intact. */
    @Test fun allUpgradePaths1To14() {
        (1..13).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 14, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
