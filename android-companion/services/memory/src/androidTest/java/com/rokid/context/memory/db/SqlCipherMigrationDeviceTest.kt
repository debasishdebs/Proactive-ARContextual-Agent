package com.rokid.context.memory.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.context.memory.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** SQLCipher paths on a real device (Robolectric cannot load the native library). Controller-run. */
@RunWith(AndroidJUnit4::class)
class SqlCipherMigrationDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "sqlcipher_device_test.db"
    private val file: File get() = ctx.getDatabasePath(name)
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val store = object : EncryptionMigrator.ModeStore {
        override var current: Boolean? = false; override var target: Boolean? = null; override var stage: Boolean? = null; override var failed = false
    }

    @Before @After fun clean() { listOf("", "-wal", "-shm", "-journal", ".migrating", ".old").forEach { File(file.path + it).delete() } }

    private fun room(encrypted: Boolean): ContextDatabase = Room.databaseBuilder(ctx, ContextDatabase::class.java, name).apply {
        if (encrypted) { System.loadLibrary("sqlcipher"); openHelperFactory(SupportOpenHelperFactory(key)) }
    }.build()

    private fun mem(id: String, text: String) = MemoryEntity(id, null, text, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "")

    private suspend fun searchIds(db: ContextDatabase, q: String) = MemoryRepository(db.memoryDao()).search(q, 5).map { it.id }

    @Test fun plaintextToEncryptedAndBackKeepsRowsAndSearch() = runBlocking(Dispatchers.IO) {
        room(false).let { db ->
            try { MemoryRepository(db.memoryDao()).apply { add(mem("m1", "Milk carton in the fridge")); add(mem("m2", "दूध खत्म हो गया")) } } finally { db.close() }
        }
        val migrator = EncryptionMigrator(file, store, SqlCipherConverter) { key }
        store.target = true
        assertTrue(migrator.prepare { false })
        assertFalse(EncryptionMigrator.isPlaintextSqlite(file))
        room(true).let { db ->
            try {
                assertEquals(2, db.memoryDao().count())
                assertEquals(listOf("m1"), searchIds(db, "milk")); assertEquals(listOf("m2"), searchIds(db, "दूध"))
            } finally { db.close() }
        }
        store.target = false
        assertFalse(migrator.prepare { true })
        assertTrue(EncryptionMigrator.isPlaintextSqlite(file))
        room(false).let { db -> try { assertEquals(listOf("m1"), searchIds(db, "fridge")) } finally { db.close() } }
    }

    @Test fun theDeferredFactoryOpensAnEncryptedDatabaseOffMain() = runBlocking(Dispatchers.IO) {
        val db = Room.databaseBuilder(ctx, ContextDatabase::class.java, name).openHelperFactory(DeferredOpenHelperFactory({ true }, { key })).build()
        try {
            MemoryRepository(db.memoryDao()).add(mem("m1", "keys on the shelf"))
            assertEquals(listOf("m1"), searchIds(db, "shelf"))
        } finally { db.close() }
        assertFalse(EncryptionMigrator.isPlaintextSqlite(file))
    }

    /** A keyed v1-shaped file (v2 file minus the five frame tables, user_version 1) opens through both production builders and migrates. */
    @Test fun encryptedDbMigratesTo2() = runBlocking(Dispatchers.IO) {
        System.loadLibrary("sqlcipher")
        // Create a keyed v2 database through the production builder, step it back to a v1 shape, and reopen through build() and buildDeferred().
        listOf(true, false).forEach { deferred ->
            clean()
            val first = DatabaseFactory.build(ctx, encrypted = true) { key.copyOf() }
            try { MemoryRepository(first.memoryDao()).add(mem("m1", "keys on the shelf")) } finally { first.close() }
            // Drop the five frame tables and set user_version = 1 to emulate a v1 file (same key, same file).
            net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(file.path, key.copyOf(), null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null).use { raw ->
                listOf("frame_meta", "frame_state", "frame_source", "frame_cluster", "frame_vector").forEach { raw.execSQL("DROP TABLE IF EXISTS `$it`") }
                raw.execSQL("PRAGMA user_version = 1")
            }
            val db = if (deferred) DatabaseFactory.buildDeferred(ctx, { true }) { key.copyOf() } else DatabaseFactory.build(ctx, encrypted = true) { key.copyOf() }
            try {
                assertEquals(listOf("m1"), searchIds(db, "shelf"))
                db.frameStateDao().insertIgnore(listOf(FrameStateEntity("f1", "PENDING", 0, null, null, null, 1)))
                assertEquals(1, db.frameStateDao().get("f1")?.let { 1 })
                assertEquals(2, db.openHelper.writableDatabase.version)
            } finally { db.close() }
            assertFalse(EncryptionMigrator.isPlaintextSqlite(file))
        }
    }
}
