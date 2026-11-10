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
 * v15 -> v16 (user ruling 2026-11-10, image-delivery proof): `memory.imageDelivered`, `memory.reviewFlaggedAt` and `canvas_run_item.imageDelivered`, three
 * additive nullable columns, started from the REAL exported v15 schema (plain SQLite, Robolectric). The SQLCipher open path is unchanged by this
 * migration (DatabaseFactory only adds MIGRATION_15_16 to both builders); an encrypted migration run is NOT covered here (needs the emulator AVD).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV15to16Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-1516"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)

    private fun v15() = helper.createDatabase(name, 15).also { V1Fixture.seedVersion(it, 15); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 16, true, MIGRATION_15_16)

    @Test fun everyExistingRowIsKeptAndTheNewColumnsAreNull() {
        v15()
        val db = migrate()
        db.query("SELECT imageDelivered, reviewFlaggedAt, text FROM memory").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertTrue(c.isNull(1)); assertEquals("v15", c.getString(2)) }
        db.query("SELECT imageDelivered FROM canvas_run_item").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)) }
        V1Fixture.tables(15).keys.forEach { t -> assertEquals("v15 table $t", 1, V1Fixture.count(db, t)) }
        db.close()
    }

    @Test fun onlyThreeColumnsWereAddedAndNoTable() {
        val v15 = V1Fixture.tables(15); val v16 = V1Fixture.tables(16)
        assertEquals(v15.keys, v16.keys)
        v15.forEach { (t, cols) ->
            val extra = when (t) { "memory" -> listOf("imageDelivered", "reviewFlaggedAt"); "canvas_run_item" -> listOf("imageDelivered"); else -> emptyList() }
            assertEquals("table $t", cols.map { it.first } + extra, v16.getValue(t).map { it.first })
        }
    }

    @Test fun indicesAreIntact() {
        v15()
        val db = migrate()
        val idx = db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('memory','canvas_run_item')").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(idx.toString(), idx.containsAll(setOf("index_memory_frameId", "index_memory_createdAt", "index_canvas_run_item_frameId")))
        db.close()
    }

    @Test fun theFrozenV15SchemaIsUntouched() {
        assertFalse(V1Fixture.tables(15).getValue("memory").any { it.first == "imageDelivered" || it.first == "reviewFlaggedAt" })
        assertFalse(V1Fixture.tables(15).getValue("canvas_run_item").any { it.first == "imageDelivered" })
    }

    /** Only ALTER TABLE ... ADD COLUMN: no DROP, RENAME, DELETE, UPDATE, INSERT, no copy of rows. */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_15_16"))).map { it.groupValues[1] }.toList()
        assertEquals(3, sql.size)
        sql.forEach { s -> assertTrue(s, s.startsWith("ALTER TABLE `") && " ADD COLUMN " in s) }
        sql.forEach { s -> listOf("DROP", "RENAME", "DELETE", "UPDATE", "INSERT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v15(); migrate().close() }

    /** Every canonical lineage file reaches v16 with its rows intact. */
    @Test fun allUpgradePaths1To16() {
        (1..15).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 16, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
