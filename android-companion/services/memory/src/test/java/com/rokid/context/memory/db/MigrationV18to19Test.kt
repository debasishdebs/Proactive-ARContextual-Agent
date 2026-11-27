package com.rokid.context.memory.db

import androidx.room.migration.Migration
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
 * v18 -> v19 (layered place confirmation B3, D9): three nullable columns on `place_card_log` (`eval`, `label`, `labelAt`), nothing else. Started from the REAL exported v18 schema (plain SQLite, Robolectric).
 * The chain tests (v1 .. v17 -> v18) stay in the earlier migration tests; the encrypted run is `EncryptedMigrationV18to19DeviceTest` (androidTest, written, not run).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV18to19Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-1819"

    private fun v18() = helper.createDatabase(name, 18).also { V1Fixture.seedVersion(it, 18); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 19, true, MIGRATION_18_19)

    @Test fun migratesAndValidatesAgainst19Json() { v18(); migrate().close() }

    @Test fun everyRowKeptAndOnlyThreeColumnsAdded() {
        v18()
        val db = migrate()
        val v18 = V1Fixture.tables(18)
        val v19 = V1Fixture.tables(19)
        assertEquals(v18.keys, v19.keys)
        v18.keys.forEach { t -> assertEquals("rows of $t", 1, V1Fixture.count(db, t)) }
        v18.forEach { (t, cols) -> if (t != "place_card_log") assertEquals("table $t unchanged", cols, v19.getValue(t)) }
        assertEquals(v18.getValue("place_card_log") + listOf("eval" to "TEXT", "label" to "TEXT", "labelAt" to "INTEGER"), v19.getValue("place_card_log"))
        db.close()
    }

    @Test fun oldRowKeepsItsValuesAndNewColumnsAreNull() {
        v18()
        val db = migrate()
        db.query("SELECT id, kind, delivery, photoGone, eval, label, labelAt FROM place_card_log").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("v18", c.getString(0)); assertEquals("v18", c.getString(1)); assertEquals("v18", c.getString(2)); assertEquals(1, c.getInt(3))
            assertTrue(c.isNull(4)); assertTrue(c.isNull(5)); assertTrue(c.isNull(6))
        }
        db.close()
    }

    @Test fun newColumnsAcceptValuesAndInsertWithoutThemStillWorks() {
        v18()
        val db = migrate()
        db.execSQL("INSERT INTO place_card_log (id, at, placeId, title, kind, delivery) VALUES ('a', 1, 'wd:Q1', 't', 'PHOTO', 'PENDING')")
        db.execSQL("UPDATE place_card_log SET eval = '{\"v\":1}', label = 'RIGHT', labelAt = 5 WHERE id = 'a'")
        db.query("SELECT eval, label, labelAt FROM place_card_log WHERE id = 'a'").use { c -> assertTrue(c.moveToFirst()); assertEquals("{\"v\":1}", c.getString(0)); assertEquals("RIGHT", c.getString(1)); assertEquals(5L, c.getLong(2)) }
        db.execSQL("INSERT INTO place_card_log (id, at, placeId, title, kind, delivery) VALUES ('b', 2, 'wd:Q1', 't', 'NO_MATCH', 'HELD_NOT_CONFIRMED')")
        db.query("SELECT eval FROM place_card_log WHERE id = 'b'").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)) }
        db.close()
    }

    /** The chain of the installed phone's next step: a v17 file reaches v19 through both hand-written migrations and validates against 19.json. */
    @Test fun chainV17ToV19Validates() {
        helper.createDatabase(name, 17).also { V1Fixture.seedVersion(it, 17); it.close() }
        val db = helper.runMigrationsAndValidate(name, 19, true, MIGRATION_17_18, MIGRATION_18_19)
        assertEquals(0, V1Fixture.count(db, "place_card_log")); db.close()
    }

    @Test fun integrityCheckOk() {
        v18()
        val db = migrate()
        assertEquals("ok", db.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
        db.close()
    }

    /** Idempotent as far as Room runs it: a reopen at v19 runs no migration and a second open validates again. */
    @Test fun reopenAtV19IsStable() {
        v18(); migrate().close()
        val again = helper.runMigrationsAndValidate(name, 19, true)
        assertEquals(1, V1Fixture.count(again, "place_card_log"))
        again.close()
    }

    /** Exactly three ALTER TABLE ... ADD COLUMN on place_card_log, nullable, no default; nothing destructive or rewriting. */
    @Test fun migrationBodyIsThreeAdditiveAlters() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_18_19"))).map { it.groupValues[1] }.toList()
        assertEquals(listOf(
            "ALTER TABLE `place_card_log` ADD COLUMN `eval` TEXT",
            "ALTER TABLE `place_card_log` ADD COLUMN `label` TEXT",
            "ALTER TABLE `place_card_log` ADD COLUMN `labelAt` INTEGER",
        ), sql)
        sql.forEach { s -> listOf("DROP", "RENAME", "DELETE", "UPDATE", "INSERT", "NOT NULL", "DEFAULT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    /** The production list (DatabaseFactory) ends with the hand-written step and there is no destructive fallback. */
    @Test fun productionListHasStepAndNoDestructiveFallback() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/DatabaseFactory.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/DatabaseFactory.kt").readText()
        assertEquals(2, Regex("MIGRATION_17_18, MIGRATION_18_19\\)").findAll(src).count())
        assertFalse(src.contains("fallbackToDestructiveMigration"))
    }
}
