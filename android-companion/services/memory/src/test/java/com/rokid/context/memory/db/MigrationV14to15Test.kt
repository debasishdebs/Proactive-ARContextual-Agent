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
 * v14 -> v15 (a mark touches capture): `frame_meta.markRef` (with its index) and `canvas_label.photo`, two additive nullable columns, started from the REAL exported schemas (plain SQLite, Robolectric).
 * An encrypted (SQLCipher) migration run is NOT covered here (needs the emulator AVD).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV14to15Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-1415"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)

    private fun v14() = helper.createDatabase(name, 14).also { V1Fixture.seedVersion(it, 14); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 15, true, MIGRATION_14_15)

    @Test fun everyExistingFrameAndMarkHasNoMarkRefAndNoPhotoState() {
        v14()
        val db = migrate()
        db.query("SELECT markRef FROM frame_meta").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)) }
        db.query("SELECT photo FROM canvas_label").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)) }
        V1Fixture.tables(14).keys.forEach { t -> assertEquals("v14 table $t", 1, V1Fixture.count(db, t)) }
        db.close()
    }

    @Test fun onlyTwoColumnsWereAddedAndNoTable() {
        val v14 = V1Fixture.tables(14); val v15 = V1Fixture.tables(15)
        assertEquals(v14.keys, v15.keys)
        v14.forEach { (t, cols) ->
            val extra = when (t) { "frame_meta" -> listOf("markRef"); "canvas_label" -> listOf("photo"); else -> emptyList() }
            assertEquals("table $t", cols.map { it.first } + extra, v15.getValue(t).map { it.first })
        }
    }

    @Test fun theFrozenV14SchemaIsUntouched() {
        assertFalse(V1Fixture.tables(14).getValue("frame_meta").any { it.first == "markRef" })
        assertFalse(V1Fixture.tables(14).getValue("canvas_label").any { it.first == "photo" })
    }

    /** Only ALTER TABLE ... ADD COLUMN and one CREATE INDEX: no DROP, RENAME, DELETE, UPDATE, INSERT, no copy of rows. */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_14_15"), src.indexOf("val MIGRATION_15_16"))).map { it.groupValues[1] }.toList()
        assertEquals(3, sql.size)
        sql.forEach { s -> assertTrue(s, Regex("^(ALTER TABLE `\\w+` ADD COLUMN |CREATE INDEX IF NOT EXISTS `index_frame_meta_markRef` ON `frame_meta`)").containsMatchIn(s)) }
        sql.forEach { s -> listOf("DROP", "RENAME", "DELETE", "UPDATE", "INSERT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v14(); migrate().close() }

    /** Every canonical lineage file reaches v15 with its rows intact. */
    @Test fun allUpgradePaths1To15() {
        (1..14).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 15, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
