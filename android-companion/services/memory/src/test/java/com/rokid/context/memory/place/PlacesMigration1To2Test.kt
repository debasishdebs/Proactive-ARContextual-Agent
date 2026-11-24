package com.rokid.context.memory.place

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * places.db v1 -> v2 (spec 2026-11-24 B1): additive and lossless. A v1 file is built from the exported `1.json` (no hand-written v1 SQL), filled with synthetic rows in every v1 table, upgraded by
 * [PlacesDatabase.MIGRATION_PLACES_1_2], validated against `2.json`, and then opened through Room as production does (the SQLCipher variant needs the native library and runs on a device).
 * Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlacesMigration1To2Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlacesDatabase::class.java)
    private val name = "places-mig-test.db"

    private fun seedV1(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('wd:Q1',NULL,'Site A','archaeological_site',1.5,2.5,100,'A summary','wikidata','r1',111,1,0,19000,64)")
        db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('user:00000000-0000-0000-0000-000000000001','wd:Q1','Room A1','archaeological_site',1.5,2.5,20,'note','user','',0,0,1,19001,32)")
        db.execSQL("INSERT INTO cell (geohash5,fetchedAt,status) VALUES ('s0000',222,'OK')")
        db.execSQL("INSERT INTO area (blueZoneId,south,west,north,east,seededAt,status) VALUES ('z1',1.0,2.0,3.0,4.0,333,'DONE')")
    }

    private fun count(db: SupportSQLiteDatabase, t: String) = db.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }

    @Test fun v1FileUpgradesAdditivelyAndLosslessly() {
        helper.createDatabase(name, 1).use { seedV1(it) }
        val db = helper.runMigrationsAndValidate(name, 2, true, PlacesDatabase.MIGRATION_PLACES_1_2) // validates the result against the exported 2.json
        assertEquals(2, count(db, "place")); assertEquals(1, count(db, "cell")); assertEquals(1, count(db, "area"))
        assertEquals(0, count(db, "place_card_log"))
        db.query("SELECT name, pinned, summary FROM place WHERE id = 'user:00000000-0000-0000-0000-000000000001'").use { assertTrue(it.moveToFirst()); assertEquals("Room A1", it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals("note", it.getString(2)) }
        db.query("SELECT status, fetchedAt FROM cell WHERE geohash5 = 's0000'").use { assertTrue(it.moveToFirst()); assertEquals("OK", it.getString(0)); assertEquals(222L, it.getLong(1)) }
        db.query("SELECT south, status FROM area").use { assertTrue(it.moveToFirst()); assertEquals(1.0, it.getDouble(0), 0.0); assertEquals("DONE", it.getString(1)) }
        val idx = db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='place_card_log'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(idx.containsAll(listOf("index_place_card_log_at", "index_place_card_log_frameId")))
        db.close()
    }
}
