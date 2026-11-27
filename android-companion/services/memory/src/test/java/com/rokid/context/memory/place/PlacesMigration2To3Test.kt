package com.rokid.context.memory.place

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * places.db v2 -> v3 (v18 WP3): the card history moved to context.db, so [PlacesDatabase.MIGRATION_PLACES_2_3] drops `place_card_log` and `area` gains the nullable `name` (v18 WP11b); pins, cells and areas survive with a NULL name. places.db is SQLCipher: the encrypted open is rehearsed on the emulator only, this test proves the SQL on plain SQLite. The 1 -> 2 -> 3 chain (a v1 phone) is tested here too.
 * Files are built from the exported `1.json` / `2.json`; the result is validated against `3.json`. Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlacesMigration2To3Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlacesDatabase::class.java)
    private val name = "places-mig-23-test.db"

    private fun seedPack(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('wd:Q1',NULL,'Site A','archaeological_site',1.5,2.5,100,'A summary','wikidata','r1',111,1,0,0,10)")
        db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('user:00000000-0000-0000-0000-000000000001','wd:Q1','Room A1','room',1.5,2.5,10,'','user','',112,0,1,0,5)")
        db.execSQL("INSERT INTO cell (geohash5,fetchedAt,status) VALUES ('s0000',222,'OK')")
        db.execSQL("INSERT INTO area (blueZoneId,south,west,north,east,seededAt,status) VALUES ('z1',1.0,2.0,3.0,4.0,333,'DONE')")
    }

    private fun count(db: SupportSQLiteDatabase, t: String) = db.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
    private fun tables(db: SupportSQLiteDatabase) = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    private fun indexes(db: SupportSQLiteDatabase) = db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='place_card_log'").use { it.count }

    private fun assertPackIntact(db: SupportSQLiteDatabase) {
        assertEquals(2, count(db, "place")); assertEquals(1, count(db, "cell")); assertEquals(1, count(db, "area"))
        db.query("SELECT name, pinned FROM place WHERE id = 'user:00000000-0000-0000-0000-000000000001'").use { assertTrue(it.moveToFirst()); assertEquals("Room A1", it.getString(0)); assertEquals(1, it.getInt(1)) }
        db.query("SELECT status FROM cell WHERE geohash5 = 's0000'").use { assertTrue(it.moveToFirst()); assertEquals("OK", it.getString(0)) }
        db.query("SELECT status, name FROM area").use { assertTrue(it.moveToFirst()); assertEquals("DONE", it.getString(0)); assertTrue("an existing area has no name", it.isNull(1)) }
        db.execSQL("UPDATE area SET name = 'Old town' WHERE blueZoneId = 'z1'") // the column takes a value
        db.query("SELECT name FROM area WHERE blueZoneId = 'z1'").use { assertTrue(it.moveToFirst()); assertEquals("Old town", it.getString(0)) }
        assertEquals(setOf("place", "cell", "area", "place_ref"), tables(db)); assertEquals(0, count(db, "place_ref"))
        db.execSQL("INSERT INTO place_ref (placeId,caption,imageUrl,author,licence,capturedAt,bytes) VALUES ('wd:Q1','a stone gate',NULL,NULL,NULL,1,12)") // the table takes a row, nullable credit columns
        assertEquals(1, count(db, "place_ref")); assertEquals(0, indexes(db))
    }

    @Test fun v2FileDropsTheCardHistoryTableAndKeepsThePack() {
        helper.createDatabase(name, 2).use { db ->
            seedPack(db)
            db.execSQL("INSERT INTO place_card_log (id,at,placeId,title,kind,members,frameId,confPct,cardId,delivery,photoGone) VALUES ('h1',10,'wd:Q1','Site A','PHOTO',NULL,'f1',NULL,'c1','PENDING',0)")
        }
        val db = helper.runMigrationsAndValidate(name, 3, true, PlacesDatabase.MIGRATION_PLACES_2_3) // validated against the exported 3.json
        assertPackIntact(db); db.close()
    }

    @Test fun v1FileChainsThroughBothMigrations() {
        helper.createDatabase(name, 1).use { seedPack(it) }
        val db = helper.runMigrationsAndValidate(name, 3, true, PlacesDatabase.MIGRATION_PLACES_1_2, PlacesDatabase.MIGRATION_PLACES_2_3)
        assertPackIntact(db); db.close()
    }

    @Test fun theUpgradedFileOpensThroughRoomWithTheProductionMigrationList() = runBlocking {
        helper.createDatabase(name, 2).use { seedPack(it) }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(ctx, PlacesDatabase::class.java, name).addMigrations(PlacesDatabase.MIGRATION_PLACES_1_2, PlacesDatabase.MIGRATION_PLACES_2_3, PlacesDatabase.MIGRATION_PLACES_3_4).allowMainThreadQueries().build()
        try { assertEquals(2, room.dao().placeCount()); assertEquals(1, room.dao().cells().size); assertEquals(1, room.dao().areas().size); assertEquals(null, room.dao().areas().single().name); assertEquals(1, room.dao().setAreaName("z1", "Fort")); assertEquals("Fort", room.dao().areaName("z1")) } finally { room.close() }
    }
}
