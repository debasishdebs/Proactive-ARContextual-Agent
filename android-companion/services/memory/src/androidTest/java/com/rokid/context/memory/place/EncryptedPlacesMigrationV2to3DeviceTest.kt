package com.rokid.context.memory.place

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.context.memory.DbKeyStore
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EncryptionMigrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * places.db v2 -> v3 on a REAL ENCRYPTED file (SQLCipher, Keystore-backed key), inside the androidTest APK sandbox only. The v2 file is built from the exported `2.json` with representative synthetic rows
 * (seeded places, a pinned user place as the pins mirror, a cell, an area and a card-history row); the result is validated against `3.json` and reopened through Room with the production migration list. Emulator only.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedPlacesMigrationV2to3DeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val name = "enc-places-migration-23.db"
    private val file: File get() = ctx.getDatabasePath(name)
    private val key: ByteArray by lazy { DbKeyStore.passphrase(ctx) }

    @get:Rule val helper = MigrationTestHelper(inst, PlacesDatabase::class.java, emptyList(), SupportOpenHelperFactory(DbKeyStore.passphrase(InstrumentationRegistry.getInstrumentation().targetContext).also { System.loadLibrary("sqlcipher") }))

    @Before fun guardAndClean() { check(ctx.packageName.endsWith(".test")) { "refusing to run outside the androidTest APK sandbox" }; clean() }
    @After fun cleanUp() { if (ctx.packageName.endsWith(".test")) clean() }
    private fun clean() { listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() } }
    private fun assertEncryptedOnDisk() { assertFalse("the file must not be plaintext SQLite", EncryptionMigrator.isPlaintextSqlite(file)); assertTrue(file.length() > 0) }

    private fun count(db: SupportSQLiteDatabase, t: String) = db.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
    private fun tables(db: SupportSQLiteDatabase) = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun seed(db: SupportSQLiteDatabase) {
        for (i in 1..5) db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('wd:Q$i',NULL,'Site $i','monument',1.5,2.5,100,'Summary $i','wikidata','r1',111,1,0,0,10)")
        for (i in 1..2) db.execSQL("INSERT INTO place (id,parentId,name,kind,lat,lon,radiusM,summary,source,sourceRev,fetchedAt,seeded,pinned,lastUsedDay,bytes) VALUES ('user:00000000-0000-0000-0000-00000000000$i','wd:Q1','Pin $i','room',1.5,2.5,10,'','user','',112,0,1,0,5)")
        db.execSQL("INSERT INTO cell (geohash5,fetchedAt,status) VALUES ('s0000',222,'OK')")
        db.execSQL("INSERT INTO area (blueZoneId,south,west,north,east,seededAt,status) VALUES ('z1',1.0,2.0,3.0,4.0,333,'DONE')")
        db.execSQL("INSERT INTO place_card_log (id,at,placeId,title,kind,members,frameId,confPct,cardId,delivery,photoGone) VALUES ('h1',10,'wd:Q1','Site 1','PHOTO',NULL,'f1',NULL,'c1','PENDING',0)")
    }

    @Test fun encryptedV2UpgradesToV3KeepingThePack() = runBlocking(Dispatchers.IO) {
        helper.createDatabase(name, 2).use { seed(it) }
        assertEncryptedOnDisk()
        val v3 = helper.runMigrationsAndValidate(name, 3, true, PlacesDatabase.MIGRATION_PLACES_2_3) // validated against the exported 3.json
        assertEquals(7, count(v3, "place")); assertEquals(1, count(v3, "cell")); assertEquals(1, count(v3, "area"))
        assertEquals(2, v3.query("SELECT COUNT(*) FROM place WHERE pinned = 1").use { it.moveToFirst(); it.getInt(0) })
        v3.query("SELECT name FROM place WHERE id = 'user:00000000-0000-0000-0000-000000000002'").use { assertTrue(it.moveToFirst()); assertEquals("Pin 2", it.getString(0)) }
        v3.query("SELECT name FROM area").use { assertTrue(it.moveToFirst()); assertTrue("an existing area has no name", it.isNull(0)) }
        assertEquals(setOf("place", "cell", "area", "place_ref"), tables(v3)); assertEquals(0, count(v3, "place_ref"))
        assertEquals("ok", v3.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
        v3.close(); assertEncryptedOnDisk()

        repeat(2) {
            val db = Room.databaseBuilder(ctx, PlacesDatabase::class.java, name)
                .openHelperFactory(DatabaseFactory.ensureOpenParams({ key.copyOf() }))
                .addMigrations(PlacesDatabase.MIGRATION_PLACES_1_2, PlacesDatabase.MIGRATION_PLACES_2_3, PlacesDatabase.MIGRATION_PLACES_3_4).build()
            try {
                assertEquals(4, db.openHelper.writableDatabase.version) // the production list now ends at v4: this is the v2 -> v3 -> v4 chain from an old phone
                runBlocking {
                    assertEquals(7, db.dao().placeCount()); assertEquals(2, db.dao().pinnedCount()); assertEquals(1, db.dao().cells().size); assertEquals(1, db.dao().areas().size)
                    assertEquals(1, db.dao().setAreaName("z1", "Fort"))
                }
            } finally { db.close() }
            assertEncryptedOnDisk()
        }
    }
}
