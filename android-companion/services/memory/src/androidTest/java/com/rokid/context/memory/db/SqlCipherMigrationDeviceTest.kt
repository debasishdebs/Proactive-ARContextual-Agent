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

    private val canvasTables = listOf("canvas_pipeline", "canvas_pipeline_version", "canvas_run", "canvas_run_item", "canvas_score", "canvas_publish", "canvas_live_trace")
    private val v6Tables = listOf("canvas_live_trace")

    private fun open() = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(file.path, key.copyOf(), null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null)

    /** v6 → v5 shape: the live trace table and the five slice-2 columns (SQLite DROP COLUMN). */
    private fun dropV6(raw: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        raw.execSQL("DROP TABLE IF EXISTS `canvas_live_trace`")
        listOf("kind", "mode", "rollbackReason").forEach { raw.execSQL("ALTER TABLE `canvas_publish` DROP COLUMN `$it`") }
        raw.execSQL("ALTER TABLE `canvas_run` DROP COLUMN `abGroup`")
        raw.execSQL("ALTER TABLE `canvas_run_item` DROP COLUMN `calls`")
    }

    /** v5 → v4 shape: the four frame_meta columns of wave B and wave 3. */
    private fun dropV5(raw: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        listOf("intentLabel", "intentConf", "intentAt", "capturedAtMs").forEach { raw.execSQL("ALTER TABLE `frame_meta` DROP COLUMN `$it`") }
    }

    /** v4 → v3 shape of frame_cluster / frame_meta (SQLite DROP COLUMN needs the indices gone first). */
    private fun dropV4(raw: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        raw.execSQL("DROP INDEX IF EXISTS `index_frame_cluster_state`"); raw.execSQL("DROP INDEX IF EXISTS `index_frame_meta_clusterId`")
        listOf("state", "attempts", "claimId", "claimedAt", "lastError", "updatedAt", "motionClass", "memberCount", "lastTs").forEach { raw.execSQL("ALTER TABLE `frame_cluster` DROP COLUMN `$it`") }
        raw.execSQL("ALTER TABLE `frame_meta` DROP COLUMN `routedAt`")
    }

    /** Takes a freshly created current-version (v6) file down to the shape of canonical [version] (1..5) and sets user_version. Own fixture file and key: never the production database. */
    private fun shapeAs(raw: net.zetetic.database.sqlcipher.SQLiteDatabase, version: Int) {
        if (version <= 5) dropV6(raw)
        if (version <= 4) dropV5(raw)
        if (version <= 3) dropV4(raw)
        if (version <= 2) canvasTables.forEach { raw.execSQL("DROP TABLE IF EXISTS `$it`") }
        if (version <= 1) listOf("frame_meta", "frame_state", "frame_source", "frame_cluster", "frame_vector").forEach { raw.execSQL("DROP TABLE IF EXISTS `$it`") }
        raw.execSQL("PRAGMA user_version = $version")
    }

    private fun assertCanvasTables(db: ContextDatabase) {
        val names = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(names.containsAll(canvasTables))
    }

    /** One keyed fixture at canonical [version], migrated to v6 through both production builders, own key, never the production file (S41). */
    private fun migratesToV6From(version: Int, seed: (net.zetetic.database.sqlcipher.SQLiteDatabase) -> Unit = {}, assertAfter: suspend (ContextDatabase) -> Unit = {}) = runBlocking(Dispatchers.IO) {
        System.loadLibrary("sqlcipher")
        listOf(true, false).forEach { deferred ->
            clean()
            val first = DatabaseFactory.build(ctx, encrypted = true) { key.copyOf() }
            try {
                MemoryRepository(first.memoryDao()).add(mem("m1", "keys on the shelf"))
                first.frameStateDao().insertIgnore(listOf(FrameStateEntity("f1", "PENDING", 0, null, null, null, 1)))
            } finally { first.close() }
            open().use { raw -> shapeAs(raw, version); seed(raw) }
            val db = if (deferred) DatabaseFactory.buildDeferred(ctx, { true }) { key.copyOf() } else DatabaseFactory.build(ctx, encrypted = true) { key.copyOf() }
            try {
                assertEquals(6, db.openHelper.writableDatabase.version)
                assertEquals(listOf("m1"), searchIds(db, "shelf"))
                assertCanvasTables(db)
                assertEquals(0, db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM canvas_live_trace").use { it.moveToFirst(); it.getInt(0) })
                assertAfter(db)
            } finally { db.close() }
            assertFalse(EncryptionMigrator.isPlaintextSqlite(file))
        }
    }

    /** A keyed v1-shaped file (no frame or canvas tables, user_version 1) migrates v1 → v6. */
    @Test fun encryptedDbMigratesTo6FromV1Shape() = migratesToV6From(1, assertAfter = { db ->
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity("f9", "PENDING", 0, null, null, null, 1)))
        assertEquals(1, db.frameStateDao().get("f9")?.let { 1 })
    })

    /** A keyed v2-shaped file (no canvas tables) migrates v2 → v6, keeping its v2 rows. */
    @Test fun encryptedDbMigratesTo6FromV2Shape() = migratesToV6From(2, assertAfter = { db ->
        assertEquals(1, db.frameStateDao().get("f1")?.let { 1 })
        assertEquals(0, db.canvasDao().runs(5).size)
    })

    /** A keyed v3 file with a closed and an open cluster, a frame_meta row and a canvas pipeline + publish row migrates v3 → v6. */
    @Test fun encryptedDbMigratesTo6FromV3Shape() = migratesToV6From(3, seed = { raw ->
        raw.execSQL("INSERT INTO frame_cluster (clusterId, openedAt, closedAt, closeReason, repFrameIds, memoryId) VALUES ('closed-c', 1, 2, 'gap', '[]', NULL), ('open-c', 3, NULL, NULL, '[]', NULL)")
        raw.execSQL("INSERT INTO frame_meta (frameId, wireFrameId, sessionId, motion, dwell, createdAt) VALUES ('fm1', 'w1', 's', 'STILL', 0, 1)")
        raw.execSQL("INSERT INTO canvas_pipeline (id, name, draftJson, draftRev, builtIn, createdAt, updatedAt) VALUES ('p1', 'kept', '{}', 1, 0, 1, 1)")
        raw.execSQL("INSERT INTO canvas_publish (pipelineId, version, hash, publishedAt, rolledBackAt, snapshotJson) VALUES ('p1', 1, 'h', 1, NULL, '{}')")
    }, assertAfter = { db ->
        assertEquals("CLOSED", db.frameClusterDao().get("closed-c")?.state)
        assertEquals("OPEN", db.frameClusterDao().get("open-c")?.state)
        assertEquals(null, db.frameMetaDao().get("fm1")?.routedAt)
        assertEquals("kept", db.openHelper.writableDatabase.query("SELECT name FROM canvas_pipeline WHERE id = 'p1'").use { it.moveToFirst(); it.getString(0) })
        assertEquals("prompts", db.canvasDao().activePublishes().single().kind)
    })

    /** A keyed v4 file (no intent columns) migrates v4 → v6. */
    @Test fun encryptedDbMigratesTo6FromV4Shape() = migratesToV6From(4, seed = { raw ->
        raw.execSQL("INSERT INTO frame_meta (frameId, wireFrameId, sessionId, motion, dwell, createdAt) VALUES ('fm1', 'w1', 's', 'STILL', 0, 1)")
    }, assertAfter = { db ->
        val m = db.frameMetaDao().get("fm1")!!
        assertEquals(null, m.intentLabel); assertEquals(null, m.capturedAtMs)
    })

    /** A keyed v5 file with a canvas publish, run and item migrates v5 → v6: old rows keep their values, new columns take defaults. */
    @Test fun encryptedDbMigratesTo6FromV5Shape() = migratesToV6From(5, seed = { raw ->
        raw.execSQL("INSERT INTO canvas_publish (pipelineId, version, hash, publishedAt, rolledBackAt, snapshotJson) VALUES ('p1', 1, 'h', 1, NULL, '{}')")
        raw.execSQL("INSERT INTO canvas_run (runId, pipelineId, version, pipelineHash, pipelineJson, status, frameCount, estCalls, calls, failures, startedAt, finishedAt, modelsJson) VALUES ('r1', 'p1', 1, 'h', '{}', 'DONE', 1, 1, 1, 0, 1, 2, '[]')")
        raw.execSQL("INSERT INTO canvas_run_item (runId, frameId, nodeId, status) VALUES ('r1', 'fm1', 'n', 'OK')")
    }, assertAfter = { db ->
        val pub = db.canvasDao().activePublishes().single()
        assertEquals("prompts", pub.kind); assertEquals(null, pub.mode); assertEquals(null, pub.rollbackReason)
        assertEquals(null, db.canvasDao().run("r1")?.abGroup)
        assertEquals(null, db.canvasDao().items("r1").single().calls)
    })
}
