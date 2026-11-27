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
 * Layered place confirmation B3 (D9): a REAL ENCRYPTED v18 -> v19 upgrade (the three nullable `place_card_log` columns), in the androidTest APK sandbox only (never the app's data). The v18 file is created from the
 * exported `18.json` through Room's MigrationTestHelper with the SQLCipher factory the app uses and the real Keystore-backed [DbKeyStore] key. WRITTEN, NOT RUN (emulator rehearsal pending).
 */
@RunWith(AndroidJUnit4::class)
class EncryptedMigrationV18to19DeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val name = "enc-migration-1819.db"
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

    @Test fun encryptedV18UpgradesToV19KeepingEverything() = runBlocking(Dispatchers.IO) {
        val v18 = helper.createDatabase(name, 18)
        V1Fixture.seedVersion(v18, 18)
        val before = V1Fixture.tables(18).keys.associateWith { V1Fixture.count(v18, it) }
        v18.close()
        assertEncryptedOnDisk()

        val v19 = helper.runMigrationsAndValidate(name, 19, true, MIGRATION_18_19)
        before.forEach { (t, n) -> assertEquals("rows of $t", n, V1Fixture.count(v19, t)) }
        v19.query("SELECT eval, label, labelAt FROM place_card_log").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2)) }
        assertEquals("ok", v19.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
        v19.close()
        assertEncryptedOnDisk()

        // reopen as the app does (Room + the same factory + the same key + the production migration), twice, and use the new columns
        repeat(2) {
            val db = Room.databaseBuilder(ctx, ContextDatabase::class.java, name)
                .openHelperFactory(DatabaseFactory.ensureOpenParams({ key.copyOf() }))
                .addMigrations(MIGRATION_18_19).build()
            try {
                assertEquals(19, db.openHelper.writableDatabase.version)
                before.forEach { (t, n) -> assertEquals("reopen rows of $t", n + if (t == "place_card_log") it else 0, V1Fixture.count(db.openHelper.writableDatabase, t)) }
                runBlocking {
                    db.placeCardLogDao().put(PlaceCardLogEntity("c$it", 1, "wd:Q1", "t", "PHOTO", null, null, null, null, "PENDING", eval = "{\"v\":1}"))
                    assertEquals(1, db.placeCardLogDao().setCardLabel("c$it", CardLabel.RIGHT, 7L))
                    assertEquals(CardLabel.RIGHT, db.placeCardLogDao().cardLog("c$it")!!.label)
                }
            } finally { db.close() }
            assertEncryptedOnDisk()
        }
    }
}
