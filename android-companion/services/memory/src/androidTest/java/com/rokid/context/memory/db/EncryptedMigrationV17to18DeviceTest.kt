package com.rokid.context.memory.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.context.memory.DbKeyStore
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
 * v18 build spec WP1 / gate G1: a REAL ENCRYPTED v17 -> v18 upgrade, in the androidTest APK sandbox only (never the app's data). The v17 file is created from the exported `17.json`
 * through Room's MigrationTestHelper with the SQLCipher factory the app uses and the real Keystore-backed [DbKeyStore] key. Runs on the emulator in V (not run by the unit tests).
 */
@RunWith(AndroidJUnit4::class)
class EncryptedMigrationV17to18DeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val name = "enc-migration-1718.db"
    private val file: File get() = ctx.getDatabasePath(name)
    private val key: ByteArray by lazy { DbKeyStore.passphrase(ctx) }

    @get:Rule val helper = MigrationTestHelper(inst, ContextDatabase::class.java, emptyList(), SupportOpenHelperFactory(DbKeyStore.passphrase(InstrumentationRegistry.getInstrumentation().targetContext).also { System.loadLibrary("sqlcipher") }))

    @Before fun guardAndClean() {
        check(ctx.packageName.endsWith(".test")) { "refusing to run outside the androidTest APK sandbox" }
        clean()
    }
    @After fun cleanUp() { if (ctx.packageName.endsWith(".test")) clean() }
    private fun clean() { listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() } }

    private fun assertEncryptedOnDisk() {
        assertFalse("the file must not be plaintext SQLite", EncryptionMigrator.isPlaintextSqlite(file))
        assertTrue(file.length() > 0)
    }

    @Test fun encryptedV17UpgradesToV18KeepingEverything() = runBlocking(Dispatchers.IO) {
        val v17 = helper.createDatabase(name, 17)
        V1Fixture.seedVersion(v17, 17)
        val before = V1Fixture.tables(17).keys.associateWith { V1Fixture.count(v17, it) }
        v17.close()
        assertEncryptedOnDisk()

        val v18 = helper.runMigrationsAndValidate(name, 18, true, MIGRATION_17_18)
        before.forEach { (t, n) -> assertEquals("rows of $t", n, V1Fixture.count(v18, t)) }
        assertEquals(0, V1Fixture.count(v18, "place_card_log")); assertEquals(0, V1Fixture.count(v18, "place_link")); assertEquals(0, V1Fixture.count(v18, "moment_location")); assertEquals(0, V1Fixture.count(v18, "scene_centroid")); assertEquals(0, V1Fixture.count(v18, "model_attribution"))
        assertEquals("ok", v18.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
        v18.close()
        assertEncryptedOnDisk()

        // reopen as the app does (Room + the same factory + the same key + the production migration), twice, and use both new DAOs
        repeat(2) {
            val db = Room.databaseBuilder(ctx, ContextDatabase::class.java, name)
                .openHelperFactory(DatabaseFactory.ensureOpenParams({ key.copyOf() }))
                .addMigrations(MIGRATION_17_18, MIGRATION_18_19).build()
            try {
                assertEquals(19, db.openHelper.writableDatabase.version)
                before.forEach { (t, n) -> assertEquals("reopen rows of $t", n, V1Fixture.count(db.openHelper.writableDatabase, t)) }
                runBlocking {
                    db.momentLocationDao().insertIfAbsent(MomentLocationEntity("m1", 1, "FRAME", "f1", 1.0, 2.0, 5.0, 10, "ZONE_STREAM", null, null, 3))
                    assertEquals(1, db.momentLocationDao().count())
                    db.placeCardLogDao().put(PlaceCardLogEntity("c$it", 1, "wd:Q1", "t", "PHOTO", null, null, null, null, "PENDING"))
                }
            } finally { db.close() }
            assertEncryptedOnDisk()
        }
    }
}
