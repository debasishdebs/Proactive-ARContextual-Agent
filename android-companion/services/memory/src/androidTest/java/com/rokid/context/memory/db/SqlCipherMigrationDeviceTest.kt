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

    /** A keyed v1-shaped file (v2 file minus the five frame tables, user_version 1) opens through both production builders and migrates v1 → v4. */
    @Test fun encryptedDbMigratesTo4FromV1Shape() = runBlocking(Dispatchers.IO) {
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
                assertEquals(4, db.openHelper.writableDatabase.version)
                assertCanvasTables(db)
            } finally { db.close() }
            assertFalse(EncryptionMigrator.isPlaintextSqlite(file))
        }
    }

    private val canvasTables = listOf("canvas_pipeline", "canvas_pipeline_version", "canvas_run", "canvas_run_item", "canvas_score", "canvas_publish")

    /** Takes a freshly created v4 file back to the v3 shape of frame_cluster / frame_meta (SQLite DROP COLUMN needs the indices gone first). */
    private fun dropV4Columns(raw: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        raw.execSQL("DROP INDEX IF EXISTS `index_frame_cluster_state`"); raw.execSQL("DROP INDEX IF EXISTS `index_frame_meta_clusterId`")
        listOf("state", "attempts", "claimId", "claimedAt", "lastError", "updatedAt", "motionClass", "memberCount", "lastTs").forEach { raw.execSQL("ALTER TABLE `frame_cluster` DROP COLUMN `$it`") }
        raw.execSQL("ALTER TABLE `frame_meta` DROP COLUMN `routedAt`")
    }

    private fun assertCanvasTables(db: ContextDatabase) {
        val names = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(names.containsAll(canvasTables))
    }

    /** A keyed v2-shaped file (current file minus the six canvas tables, user_version 2) migrates to v4 through both builders, keeping its v2 rows. */
    @Test fun encryptedDbMigratesTo4FromV2Shape() = runBlocking(Dispatchers.IO) {
        System.loadLibrary("sqlcipher")
        listOf(true, false).forEach { deferred ->
            clean()
            val first = DatabaseFactory.build(ctx, encrypted = true) { key.copyOf() }
            try {
                MemoryRepository(first.memoryDao()).add(mem("m1", "keys on the shelf"))
                first.frameStateDao().insertIgnore(listOf(FrameStateEntity("f1", "PENDING", 0, null, null, null, 1)))
            } finally { first.close() }
            net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(file.path, key.copyOf(), null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null).use { raw ->
                canvasTables.forEach { raw.execSQL("DROP TABLE IF EXISTS `$it`") }
                dropV4Columns(raw) // the file was just created at v4; v3 → v4 must find the v3 shape
                raw.execSQL("PRAGMA user_version = 2")
            }
            val db = if (deferred) DatabaseFactory.buildDeferred(ctx, { true }) { key.copyOf() } else DatabaseFactory.build(ctx, encrypted = true) { key.copyOf() }
            try {
                assertEquals(4, db.openHelper.writableDatabase.version)
                assertEquals(listOf("m1"), searchIds(db, "shelf"))
                assertEquals(1, db.frameStateDao().get("f1")?.let { 1 })
                assertCanvasTables(db)
                assertEquals(0, db.canvasDao().runs(5).size)
            } finally { db.close() }
            assertFalse(EncryptionMigrator.isPlaintextSqlite(file))
        }
    }

    /**
     * A keyed v3-shaped file (the wave-2 columns and indices removed, user_version 3) with a closed and an open cluster, a frame_meta row and
     * a canvas pipeline row migrates v3 → v4 through both builders: cluster states derive from closedAt, the canvas row survives.
     */
    @Test fun encryptedDbMigratesTo4FromV3Shape() = runBlocking(Dispatchers.IO) {
        System.loadLibrary("sqlcipher")
        listOf(true, false).forEach { deferred ->
            clean()
            val first = DatabaseFactory.build(ctx, encrypted = true) { key.copyOf() }
            try { MemoryRepository(first.memoryDao()).add(mem("m1", "keys on the shelf")) } finally { first.close() }
            net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(file.path, key.copyOf(), null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null).use { raw ->
                dropV4Columns(raw)
                raw.execSQL("INSERT INTO frame_cluster (clusterId, openedAt, closedAt, closeReason, repFrameIds, memoryId) VALUES ('closed-c', 1, 2, 'gap', '[]', NULL), ('open-c', 3, NULL, NULL, '[]', NULL)")
                raw.execSQL("INSERT INTO frame_meta (frameId, wireFrameId, sessionId, motion, dwell, createdAt) VALUES ('f1', 'w1', 's', 'STILL', 0, 1)")
                raw.execSQL("INSERT INTO canvas_pipeline (id, name, draftJson, draftRev, builtIn, createdAt, updatedAt) VALUES ('p1', 'kept', '{}', 1, 0, 1, 1)")
                raw.execSQL("PRAGMA user_version = 3")
            }
            val db = if (deferred) DatabaseFactory.buildDeferred(ctx, { true }) { key.copyOf() } else DatabaseFactory.build(ctx, encrypted = true) { key.copyOf() }
            try {
                assertEquals(4, db.openHelper.writableDatabase.version)
                assertEquals(listOf("m1"), searchIds(db, "shelf"))
                assertEquals("CLOSED", db.frameClusterDao().get("closed-c")?.state)
                assertEquals("OPEN", db.frameClusterDao().get("open-c")?.state)
                assertEquals(null, db.frameMetaDao().get("f1")?.routedAt)
                assertEquals("f1", db.frameMetaDao().get("f1")?.frameId)
                assertCanvasTables(db)
                assertEquals("kept", db.openHelper.writableDatabase.query("SELECT name FROM canvas_pipeline WHERE id = 'p1'").use { it.moveToFirst(); it.getString(0) })
            } finally { db.close() }
            assertFalse(EncryptionMigrator.isPlaintextSqlite(file))
        }
    }
}
