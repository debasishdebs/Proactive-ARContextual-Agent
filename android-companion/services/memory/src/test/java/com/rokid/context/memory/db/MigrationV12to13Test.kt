package com.rokid.context.memory.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * v12 → v13 (a Moment can be born from a mark alone): three additive columns (`moment.momentType`, `moment.noPhotoReason`, `moment_part.code`), started from the REAL exported schemas (plain SQLite,
 * Robolectric). An encrypted (SQLCipher) migration run is NOT covered here: that needs the emulator AVD MyEmulator and is a separate androidTest that has not been written or run.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV12to13Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-1213"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)

    private fun v12() = helper.createDatabase(name, 12).also { V1Fixture.seedVersion(it, 12); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 13, true, MIGRATION_12_13)

    @Test fun everyExistingMomentBecomesFrameAndEveryExistingPartHasNoCode() {
        v12()
        val db = migrate()
        db.query("SELECT momentType, noPhotoReason FROM moment").use { c -> assertTrue(c.moveToFirst()); assertEquals("FRAME", c.getString(0)); assertTrue(c.isNull(1)) }
        db.query("SELECT code FROM moment_part").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)) }
        V1Fixture.tables(12).keys.forEach { t -> assertEquals("v12 table $t", 1, V1Fixture.count(db, t)) }
        db.close()
    }

    @Test fun onlyThreeColumnsWereAddedAndNoTable() {
        val v12 = V1Fixture.tables(12); val v13 = V1Fixture.tables(13)
        assertEquals(v12.keys, v13.keys)
        v12.forEach { (t, cols) ->
            val extra = when (t) { "moment" -> listOf("momentType", "noPhotoReason"); "moment_part" -> listOf("code"); else -> emptyList() }
            assertEquals("table $t", cols.map { it.first } + extra, v13.getValue(t).map { it.first })
        }
    }

    @Test fun theFrozenV12SchemaIsUntouched() {
        assertFalse(V1Fixture.tables(12).getValue("moment").any { it.first == "momentType" })
    }

    /** The migration holds only ALTER TABLE ... ADD COLUMN: no CREATE, DROP, RENAME, DELETE, UPDATE, INSERT, no copy of rows. */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_12_13"), src.indexOf("val MIGRATION_13_14"))).map { it.groupValues[1] }.toList()
        assertEquals(3, sql.size)
        sql.forEach { s -> assertTrue(s, Regex("^ALTER TABLE `\\w+` ADD COLUMN ").containsMatchIn(s)) }
        sql.forEach { s -> listOf("DROP", "RENAME", "DELETE", "UPDATE", "INSERT", "CREATE").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v12(); migrate().close() }

    /** Every canonical lineage file (v1 and v2 originals, v3 to v12) reaches v13 with its rows intact. */
    @Test fun allUpgradePaths1To13() {
        (1..12).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 13, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
