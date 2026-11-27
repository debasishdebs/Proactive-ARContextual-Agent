package com.rokid.context.memory.db

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * v17 -> v18 (v18 build spec WP1, WP10, WP11a): five new tables (`place_card_log`, `moment_location`, `scene_centroid`, `model_attribution`, `place_link`) and seven indexes, nothing else. Started from the REAL exported v17 schema (plain SQLite, Robolectric).
 * The encrypted run is NOT covered here (EncryptedMigrationV17to18DeviceTest, androidTest, runs on the emulator).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV17to18Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-1718"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)

    private fun v17() = helper.createDatabase(name, 17).also { V1Fixture.seedVersion(it, 17); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 18, true, MIGRATION_17_18)

    @Test fun migratesAndValidatesAgainst18Json() { v17(); migrate().close() }

    @Test fun allV17RowCountsUnchanged() {
        v17()
        val db = migrate()
        val v17 = V1Fixture.tables(17)
        assertTrue(v17.isNotEmpty())
        v17.keys.forEach { t -> assertEquals("v17 table $t", 1, V1Fixture.count(db, t)) }
        val v18 = V1Fixture.tables(18)
        assertEquals(v17.keys + setOf("place_card_log", "moment_location", "scene_centroid", "model_attribution", "place_link"), v18.keys)
        v17.forEach { (t, cols) -> assertEquals("table $t unchanged", cols, v18.getValue(t)) }
        assertEquals(0, V1Fixture.count(db, "place_card_log")); assertEquals(0, V1Fixture.count(db, "moment_location")); assertEquals(0, V1Fixture.count(db, "scene_centroid")); assertEquals(0, V1Fixture.count(db, "model_attribution")); assertEquals(0, V1Fixture.count(db, "place_link"))
        db.close()
    }

    @Test fun integrityCheckOk() {
        v17()
        val db = migrate()
        assertEquals("ok", db.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
        db.close()
    }

    @Test fun newTablesAcceptRowsAndIndexesExist() {
        v17()
        val db = migrate()
        db.execSQL("INSERT INTO place_card_log (id, at, placeId, title, kind, delivery) VALUES ('a', 1, 'wd:Q1', 't', 'PHOTO', 'PENDING')")
        db.query("SELECT photoGone, members, frameId FROM place_card_log").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2)) }
        db.execSQL("INSERT INTO moment_location (momentId, atMs, anchorKind, anchorId, lat, lon, accuracyM, fixAgeMs, source, savedAt) VALUES ('m', 1, 'FRAME', 'f', 1.0, 2.0, 5.0, 100, 'ZONE_STREAM', 2)")
        db.query("SELECT speedClass, method FROM moment_location").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertTrue(c.isNull(1)) }
        db.execSQL("INSERT INTO scene_centroid (sceneId, space, membershipRev, dim, vector, memberCount, builtAt) VALUES ('s', 'sp', 1, 2, x'0000803f00000000', 3, 4)")
        db.query("SELECT dim, memberCount FROM scene_centroid WHERE sceneId = 's' AND space = 'sp'").use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)); assertEquals(3, c.getInt(1)) }
        try { db.execSQL("INSERT INTO scene_centroid (sceneId, space, membershipRev, dim, vector, memberCount, builtAt) VALUES ('s', 'sp', 1, 2, x'00', 3, 4)"); fail("one row per (scene, space)") } catch (e: android.database.SQLException) { /* expected */ }
        val idx = db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('place_card_log','moment_location')").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(idx.toString(), idx.containsAll(setOf("index_place_card_log_at", "index_place_card_log_frameId", "index_moment_location_atMs", "index_moment_location_anchorId")))
        db.close()
    }

    @Test fun placeLinkTableTakesOneRowPerPlaceAndMomentAndHasItsIndexes() {
        v17()
        val db = migrate()
        db.execSQL("INSERT INTO place_link (placeId, momentId, atMs, anchorId, role, linkedAt) VALUES ('user:p', '68f6baa4', 1000, 'f', 'CARD', 2)")
        try { db.execSQL("INSERT INTO place_link (placeId, momentId, atMs, anchorId, role, linkedAt) VALUES ('user:p', '68f6baa4', 1000, 'g', 'CARD', 3)"); fail("one link per (place, Moment)") } catch (e: android.database.SQLException) { /* expected */ }
        db.execSQL("INSERT INTO place_link (placeId, momentId, atMs, anchorId, role, linkedAt) VALUES ('user:p', '68f6baa5', 2000, 'g', 'CARD', 3)")
        assertEquals(2, V1Fixture.count(db, "place_link"))
        val idx = db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'place_link'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(idx.toString(), idx.containsAll(setOf("index_place_link_momentId", "index_place_link_atMs", "index_place_link_anchorId")))
        db.close()
    }

    @Test fun modelAttributionTableTakesOneRowPerItemAndAllowsNullVersion() {
        v17(); val db = migrate()
        db.execSQL("INSERT INTO model_attribution (kind, refId, model, modelVersion, promptRev, recordedAt) VALUES ('MEMORY', 'm1', 'gpt-x', NULL, '3', 4)")
        db.query("SELECT model, modelVersion, promptRev FROM model_attribution WHERE kind = 'MEMORY' AND refId = 'm1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("gpt-x", c.getString(0)); assertTrue(c.isNull(1)); assertEquals("3", c.getString(2)) }
        try { db.execSQL("INSERT INTO model_attribution (kind, refId, model, recordedAt) VALUES ('MEMORY', 'm1', 'other', 5)"); fail("one row per (kind, refId)") } catch (e: android.database.SQLException) { /* expected */ }
        try { db.execSQL("INSERT INTO model_attribution (kind, refId, model, recordedAt) VALUES ('ANSWER', 'a1', NULL, 5)"); fail("a model name is required") } catch (e: android.database.SQLException) { /* expected */ }
    }

    /** Five CREATE TABLE and seven CREATE INDEX, all IF NOT EXISTS; no DROP, ALTER, RENAME, DELETE, UPDATE, INSERT. */
    @Test fun migrationBodyIsFiveTablesSevenIndexes() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_17_18"), src.indexOf("val MIGRATION_18_19"))).map { it.groupValues[1] }.toList()
        assertEquals(12, sql.size)
        assertEquals(5, sql.count { it.startsWith("CREATE TABLE IF NOT EXISTS `") })
        assertEquals(7, sql.count { it.startsWith("CREATE INDEX IF NOT EXISTS `") })
        sql.forEach { s -> listOf("DROP", "ALTER", "RENAME", "DELETE", "UPDATE", "INSERT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    /** The shipped SQL equals the spec DDL, which equals the places.db v2 table (byte-identical for place_card_log). */
    @Test fun placeCardLogDdlEqualsPlacesDbMigration() {
        val fromPlaces = Regex("CREATE TABLE IF NOT EXISTS `place_card_log`[^\"]*").find(readSource("place/PlacesDb.kt"))!!.value
        val fromContext = Regex("CREATE TABLE IF NOT EXISTS `place_card_log`[^\"]*").find(readSource("db/ContextDatabase.kt"))!!.value
        assertEquals(fromPlaces, fromContext)
    }

    private fun readSource(rel: String): String {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        return File(requireNotNull(d), "src/main/java/com/rokid/context/memory/$rel").readText()
    }

    /** G1: a migration that fails half way leaves the file at user_version 17, with its rows and without the new tables. */
    @Test fun brokenMigrationRollsBackToV17() {
        v17()
        val broken = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_17_18.migrate(db) // creates all four tables and all four indexes ...
                db.execSQL("CREATE TABLE `moment_location` (`x` TEXT)") // ... then fails: the table exists
            }
        }
        try { helper.runMigrationsAndValidate(name, 18, true, broken); fail("a broken migration must not succeed") } catch (e: Exception) { /* expected */ }
        val path = ApplicationProvider.getApplicationContext<android.content.Context>().getDatabasePath(name).path
        val raw = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(17, raw.version)
            val tables = raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            assertFalse(tables.toString(), tables.contains("place_card_log")); assertFalse(tables.contains("moment_location")); assertFalse(tables.contains("scene_centroid")); assertFalse(tables.contains("model_attribution")); assertFalse(tables.contains("place_link"))
            V1Fixture.tables(17).keys.forEach { t -> assertEquals("v17 table $t", 1, raw.rawQuery("SELECT COUNT(*) FROM `$t`", null).use { it.moveToFirst(); it.getInt(0) }) }
        } finally { raw.close() }
    }

    @Test fun allUpgradePaths1To18() {
        (1..17).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 18, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
