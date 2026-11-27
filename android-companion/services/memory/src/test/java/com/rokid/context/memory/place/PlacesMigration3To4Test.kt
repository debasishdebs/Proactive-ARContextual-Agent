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
 * places.db v3 -> v4 (B1): [PlacesDatabase.MIGRATION_PLACES_3_4] creates the one table `place_image` and nothing else; every v3 row (places, pins, cells, areas, reference captions) survives byte for byte. Idempotent (a second run changes nothing).
 * The 1 -> 2 -> 3 -> 4 and 2 -> 3 -> 4 chains (an old phone) are tested too. places.db is SQLCipher: the encrypted open is in the androidTest only, this proves the SQL on plain SQLite. Validated against the exported `4.json`. Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlacesMigration3To4Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PlacesDatabase::class.java)
    private val name = "places-mig-34-test.db"

    private fun seedV3(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('wd:Q1',NULL,'Site A','archaeological_site',1.5,2.5,100,'A summary','wikidata','r1',111,1,0,0,10)")
        db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('user:00000000-0000-0000-0000-000000000001','wd:Q1','Room A1','room',1.5,2.5,10,'','user','',112,0,1,0,5)")
        db.execSQL("INSERT INTO cell (geohash5,fetchedAt,status) VALUES ('s0000',222,'OK')")
        db.execSQL("INSERT INTO area (blueZoneId,south,west,north,east,seededAt,status,name) VALUES ('z1',1.0,2.0,3.0,4.0,333,'DONE','Old town')")
        db.execSQL("INSERT INTO place_ref (placeId,caption,imageUrl,author,licence,capturedAt,bytes) VALUES ('wd:Q1','a stone gate',NULL,NULL,NULL,1,12)")
    }

    private fun count(db: SupportSQLiteDatabase, t: String) = db.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
    private fun tables(db: SupportSQLiteDatabase) = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    private fun schemaSql(db: SupportSQLiteDatabase) = db.query("SELECT name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table') ORDER BY name").use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    private fun assertV3Intact(db: SupportSQLiteDatabase, withRef: Boolean) {
        assertEquals(2, count(db, "place")); assertEquals(1, count(db, "cell")); assertEquals(1, count(db, "area"))
        db.query("SELECT name, pinned, seeded, bytes FROM place WHERE id = 'wd:Q1'").use { assertTrue(it.moveToFirst()); assertEquals("Site A", it.getString(0)); assertEquals(0, it.getInt(1)); assertEquals(1, it.getInt(2)); assertEquals(10, it.getInt(3)) }
        db.query("SELECT name, pinned FROM place WHERE id = 'user:00000000-0000-0000-0000-000000000001'").use { assertTrue(it.moveToFirst()); assertEquals("Room A1", it.getString(0)); assertEquals(1, it.getInt(1)) }
        db.query("SELECT status FROM cell WHERE geohash5 = 's0000'").use { assertTrue(it.moveToFirst()); assertEquals("OK", it.getString(0)) }
        db.query("SELECT status, name FROM area").use { assertTrue(it.moveToFirst()); assertEquals("DONE", it.getString(0)) }
        if (withRef) db.query("SELECT caption FROM place_ref WHERE placeId = 'wd:Q1'").use { assertTrue(it.moveToFirst()); assertEquals("a stone gate", it.getString(0)) }
        assertEquals(setOf("place", "cell", "area", "place_ref", "place_image"), tables(db)); assertEquals(0, count(db, "place_image"))
        assertEquals("ok", db.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
        // the empty table takes a full row and a marker row with every nullable column NULL
        db.execSQL("INSERT INTO place_image (placeId,status,tries,jpeg,width,height,sha1,filePage,author,licence,licenceUrl,space,dim,vector,vecVersion,embeddedAt,fetchedAt,bytes) VALUES ('wd:Q1','OK',0,x'FFD8',330,220,'sha','File:X.jpg','A','CC0','u','sp',2,x'0000803F00000000',1,5,6,10)")
        db.execSQL("INSERT INTO place_image (placeId,status,tries,fetchedAt,bytes) VALUES ('wd:Q2','NONE',1,7,0)")
        assertEquals(2, count(db, "place_image"))
    }

    @Test fun v3FileGainsAnEmptyPlaceImageTableAndKeepsEveryRow() {
        helper.createDatabase(name, 3).use { seedV3(it) }
        val db = helper.runMigrationsAndValidate(name, 4, true, PlacesDatabase.MIGRATION_PLACES_3_4) // validated against the exported 4.json
        assertV3Intact(db, withRef = true); db.close()
    }

    @Test fun theMigrationIsIdempotentAndTouchesNothingElse() {
        helper.createDatabase(name, 3).use { seedV3(it) }
        val before = helper.createDatabase("places-mig-34-ref.db", 3).use { schemaSql(it) }
        val db = helper.runMigrationsAndValidate(name, 4, true, PlacesDatabase.MIGRATION_PLACES_3_4)
        val once = schemaSql(db)
        assertEquals("only place_image is new", before.map { it.first }.toSet() + "place_image", once.map { it.first }.toSet())
        assertEquals("every v3 definition is byte for byte unchanged", before.toMap(), once.filter { it.first != "place_image" }.toMap())
        PlacesDatabase.MIGRATION_PLACES_3_4.migrate(db) // a second run (a retried upgrade) is a no-op
        assertEquals(once, schemaSql(db)); assertEquals(2, count(db, "place")); assertEquals(0, count(db, "place_image"))
        db.close()
    }

    @Test fun v2FileChainsThroughTwoToThreeAndThreeToFour() {
        helper.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('wd:Q1',NULL,'Site A','archaeological_site',1.5,2.5,100,'A summary','wikidata','r1',111,1,0,0,10)")
            db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('user:00000000-0000-0000-0000-000000000001','wd:Q1','Room A1','room',1.5,2.5,10,'','user','',112,0,1,0,5)")
            db.execSQL("INSERT INTO cell (geohash5,fetchedAt,status) VALUES ('s0000',222,'OK')")
            db.execSQL("INSERT INTO area (blueZoneId,south,west,north,east,seededAt,status) VALUES ('z1',1.0,2.0,3.0,4.0,333,'DONE')")
        }
        val db = helper.runMigrationsAndValidate(name, 4, true, PlacesDatabase.MIGRATION_PLACES_2_3, PlacesDatabase.MIGRATION_PLACES_3_4)
        assertV3Intact(db, withRef = false); db.close()
    }

    @Test fun v1FileChainsThroughAllThreeMigrations() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('wd:Q1',NULL,'Site A','archaeological_site',1.5,2.5,100,'A summary','wikidata','r1',111,1,0,0,10)")
            db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('user:00000000-0000-0000-0000-000000000001','wd:Q1','Room A1','room',1.5,2.5,10,'','user','',112,0,1,0,5)")
            db.execSQL("INSERT INTO cell (geohash5,fetchedAt,status) VALUES ('s0000',222,'OK')")
            db.execSQL("INSERT INTO area (blueZoneId,south,west,north,east,seededAt,status) VALUES ('z1',1.0,2.0,3.0,4.0,333,'DONE')")
        }
        val db = helper.runMigrationsAndValidate(name, 4, true, PlacesDatabase.MIGRATION_PLACES_1_2, PlacesDatabase.MIGRATION_PLACES_2_3, PlacesDatabase.MIGRATION_PLACES_3_4)
        assertV3Intact(db, withRef = false); db.close()
    }

    @Test fun theUpgradedFileOpensThroughRoomWithTheProductionMigrationList() = runBlocking {
        helper.createDatabase(name, 3).use { seedV3(it) }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(ctx, PlacesDatabase::class.java, name).addMigrations(PlacesDatabase.MIGRATION_PLACES_1_2, PlacesDatabase.MIGRATION_PLACES_2_3, PlacesDatabase.MIGRATION_PLACES_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(2, room.dao().placeCount()); assertEquals(1, room.dao().refCount()); assertEquals(0, room.dao().imageCount()); assertEquals(0L, room.dao().imageBytes())
            assertEquals("Fort", room.dao().areas().single().also { room.dao().setAreaName(it.blueZoneId, "Fort") }.let { room.dao().areaName("z1") })
        } finally { room.close() }
    }
}
