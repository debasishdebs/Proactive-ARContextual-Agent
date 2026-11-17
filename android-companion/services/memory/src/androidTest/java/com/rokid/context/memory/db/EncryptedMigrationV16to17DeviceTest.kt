package com.rokid.context.memory.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.context.memory.DbKeyStore
import com.rokid.context.memory.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * V1 (proxy ruling 2026-11-17): a REAL ENCRYPTED v16 -> v17 upgrade, in the androidTest APK sandbox only (never the app's data). The v16 file is created from the exported
 * `16.json` through Room's MigrationTestHelper with the same SQLCipher SupportOpenHelperFactory the app uses; the key is the real Keystore-backed [DbKeyStore] passphrase.
 * Asserts: rows preserved, FTS search works, `ask.sourceStates` null for old rows, reopen (through Room, as production) works, the file is not plaintext, a wrong key cannot open it.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedMigrationV16to17DeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val name = "enc-migration-1617.db"
    private val file: File get() = ctx.getDatabasePath(name)
    private val key: ByteArray by lazy { DbKeyStore.passphrase(ctx) }

    @get:Rule val helper = MigrationTestHelper(inst, ContextDatabase::class.java, emptyList(), SupportOpenHelperFactory(DbKeyStore.passphrase(InstrumentationRegistry.getInstrumentation().targetContext).also { System.loadLibrary("sqlcipher") }))

    @Before fun guardAndClean() {
        check(ctx.packageName.endsWith(".test")) { "refusing to run outside the androidTest APK sandbox" }
        clean()
    }
    @After fun cleanUp() { if (ctx.packageName.endsWith(".test")) clean() }
    private fun clean() { listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() } }

    private fun count(db: SupportSQLiteDatabase, t: String) = V1Fixture.count(db, t)

    private fun createV16(): Map<String, Int> {
        val v16 = helper.createDatabase(name, 16)
        V1Fixture.seedVersion(v16, 16)
        // a recognisable memory row (FTS content-sync triggers index it), plus a second ask/nudge so counts are not all 1
        v16.execSQL("UPDATE memory SET text = 'walrus on the pier', searchText = 'walrus on the pier', type = 'note', tier = 'SHORT', reviewState = 'OK'")
        v16.execSQL("INSERT INTO memory (id, frameId, text, type, tags, tier, confidence, reviewState, trusted, createdAt, derived, searchText) VALUES ('m2', NULL, 'दूध खत्म', 'note', '', 'SHORT', 0.5, 'OK', 0, 2, 0, 'दूध खत्म')")
        v16.execSQL("UPDATE ask SET question = 'where is the walrus', answer = 'on the pier'")
        v16.execSQL("INSERT INTO ask (id, ts, question, answer, sourcesJson, rating, source, redacted) VALUES ('a2', 2, 'q2', 'a2', '[]', 0, 'phone_typed', 0)")
        val counts = V1Fixture.tables(16).keys.associateWith { count(v16, it) }
        assertTrue(counts.getValue("ask") == 2 && counts.getValue("memory") == 2 && counts.getValue("frame") >= 1 && counts.getValue("nudge") >= 1 && counts.getValue("canvas_pipeline") >= 1)
        v16.close()
        return counts
    }

    private fun assertEncryptedOnDisk() {
        assertFalse("the file must not be plaintext SQLite", EncryptionMigrator.isPlaintextSqlite(file))
        assertTrue(file.length() > 0)
    }

    @Test fun encryptedV16UpgradesToV17KeepingEverything() = runBlocking(Dispatchers.IO) {
        val before = createV16()
        assertEncryptedOnDisk()

        val v17 = helper.runMigrationsAndValidate(name, 17, true, MIGRATION_16_17)
        before.forEach { (t, n) -> assertEquals("rows of $t", n, count(v17, t)) }
        v17.query("SELECT id, question, answer, sourceStates FROM ask ORDER BY id").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("a2", c.getString(0)); assertTrue("old row sourceStates is null", c.isNull(3))
            assertTrue(c.moveToNext()); assertEquals("where is the walrus", c.getString(1)); assertEquals("on the pier", c.getString(2)); assertTrue(c.isNull(3))
        }
        assertEquals("ok", v17.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
        v17.query("SELECT COUNT(*) FROM memory_fts WHERE memory_fts MATCH 'walrus'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        v17.close()
        assertEncryptedOnDisk()

        // reopen as the app does (Room + the same factory + the same key + all production migrations), twice
        repeat(2) {
            val db = Room.databaseBuilder(ctx, ContextDatabase::class.java, name)
                .openHelperFactory(DatabaseFactory.ensureOpenParams({ key.copyOf() }))
                .addMigrations(MIGRATION_16_17).build()
            try {
                assertEquals(17, db.openHelper.writableDatabase.version)
                before.forEach { (t, n) -> assertEquals("reopen rows of $t", n, count(db.openHelper.writableDatabase, t)) }
                assertEquals(1, MemoryRepository(db.memoryDao()).search("walrus", 5).size)
                assertEquals(1, MemoryRepository(db.memoryDao()).search("दूध", 5).size)
                db.openHelper.writableDatabase.execSQL("UPDATE ask SET sourceStates = '{\"v\":1}' WHERE id = 'a2'")
            } finally { db.close() }
            assertEncryptedOnDisk()
        }
    }

    @Test fun aWrongKeyCannotOpenTheEncryptedFile() {
        createV16(); helper.runMigrationsAndValidate(name, 17, true, MIGRATION_16_17).close()
        val wrong = ByteArray(32) { (it + 1).toByte() }
        assertFalse("test key must differ", wrong.contentEquals(key))
        val db = Room.databaseBuilder(ctx, ContextDatabase::class.java, name).openHelperFactory(SupportOpenHelperFactory(wrong)).build()
        try {
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM ask").close()
            fail("a wrong key opened the database")
        } catch (e: Exception) { /* expected: not a database / file is not a database */ } finally { runCatching { db.close() } }
        // and the right key still opens it: nothing was rewritten or dropped to plaintext
        assertEncryptedOnDisk()
        val ok = Room.databaseBuilder(ctx, ContextDatabase::class.java, name).openHelperFactory(SupportOpenHelperFactory(key.copyOf())).build()
        try { assertEquals(2, count(ok.openHelper.writableDatabase, "ask")) } finally { ok.close() }
    }
}
