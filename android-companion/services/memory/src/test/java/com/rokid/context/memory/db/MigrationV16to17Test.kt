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
 * v16 -> v17 (user ruling 2026-11-17, Forget lifecycle M5): `ask.sourceStates`, one additive nullable TEXT column holding the JSON source states of an answer. Started from the REAL exported v16 schema
 * (plain SQLite, Robolectric). The SQLCipher open path is unchanged by this migration (DatabaseFactory only adds MIGRATION_16_17 to both builders, the key handling is not touched); an encrypted migration
 * run is NOT covered here (needs the emulator AVD: SqlCipherMigrationDeviceTest, as for v16).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV16to17Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-1617"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)

    private fun v16() = helper.createDatabase(name, 16).also { V1Fixture.seedVersion(it, 16); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 17, true, MIGRATION_16_17)

    @Test fun everyExistingRowIsKeptAndTheNewColumnIsNull() {
        v16()
        val db = migrate()
        db.query("SELECT id, question, answer, sourcesJson, redacted, sourceStates FROM ask").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("v16", c.getString(0)); assertEquals("v16", c.getString(1)); assertEquals("v16", c.getString(2)); assertEquals("v16", c.getString(3)); assertEquals(1, c.getInt(4))
            assertTrue("an old row defaults to null (legacy matching)", c.isNull(5))
        }
        V1Fixture.tables(16).keys.forEach { t -> assertEquals("v16 table $t", 1, V1Fixture.count(db, t)) }
        db.close()
    }

    @Test fun onlyOneColumnWasAddedAndNoTable() {
        val v16 = V1Fixture.tables(16); val v17 = V1Fixture.tables(17)
        assertEquals(v16.keys, v17.keys)
        v16.forEach { (t, cols) -> assertEquals("table $t", cols.map { it.first } + if (t == "ask") listOf("sourceStates") else emptyList(), v17.getValue(t).map { it.first }) }
        assertEquals("TEXT", v17.getValue("ask").last().second)
    }

    @Test fun theMigratedColumnReadsAndWritesAndRoomReadsIt() {
        v16()
        val db = migrate()
        db.execSQL("UPDATE ask SET sourceStates = '{\"v\":1,\"state\":\"LIVE\",\"cited\":[\"m1\"],\"history\":[]}' WHERE id = 'v16'")
        db.query("SELECT sourceStates FROM ask WHERE id = 'v16'").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.getString(0).contains("\"cited\":[\"m1\"]")) }
        db.close()
    }

    @Test fun indicesAreIntact() {
        v16()
        val db = migrate()
        val idx = db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('ask','nudge','memory')").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(idx.toString(), idx.containsAll(setOf("index_ask_ts", "index_nudge_ts", "index_memory_frameId")))
        db.close()
    }

    @Test fun theFrozenV16SchemaIsUntouched() {
        assertFalse(V1Fixture.tables(16).getValue("ask").any { it.first == "sourceStates" })
    }

    /** Only ALTER TABLE ... ADD COLUMN: no DROP, RENAME, DELETE, UPDATE, INSERT, no copy of rows. */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_16_17"), src.indexOf("val MIGRATION_17_18"))).map { it.groupValues[1] }.toList()
        assertEquals(1, sql.size)
        sql.forEach { s -> assertTrue(s, s.startsWith("ALTER TABLE `ask` ADD COLUMN `sourceStates` TEXT")) }
        sql.forEach { s -> listOf("DROP", "RENAME", "DELETE", "UPDATE", "INSERT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v16(); migrate().close() }

    /** Every canonical lineage file reaches v17 with its rows intact. */
    @Test fun allUpgradePaths1To17() {
        (1..16).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 17, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
