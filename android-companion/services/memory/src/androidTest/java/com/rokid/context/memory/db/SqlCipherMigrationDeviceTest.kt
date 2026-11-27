package com.rokid.context.memory.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** SQLCipher paths on a real device (Robolectric cannot load the native library). Controller-run. */
@RunWith(AndroidJUnit4::class)
class SqlCipherMigrationDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val name = "sqlcipher_device_test.db"
    private val file: File get() = ctx.getDatabasePath(name)
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val store = object : EncryptionMigrator.ModeStore {
        override var current: Boolean? = false; override var target: Boolean? = null; override var stage: Boolean? = null; override var failed = false
    }

    /** The file DatabaseFactory.build/buildDeferred open (`context.db`) inside THIS test APK's own sandbox. */
    private val prodNamed: File get() = ctx.getDatabasePath(DatabaseFactory.NAME)
    private val marker: File get() = File(ctx.filesDir, "migration-gate.marker")
    private val suffixes = listOf("", "-wal", "-shm", "-journal", ".migrating", ".old")

    /**
     * Safety gate: this test deletes and rewrites `context.db`. It only ever runs in the androidTest APK, whose application id is the module
     * namespace + ".test" (com.rokid.context.services.memory.test) with its own data dir, never the app's. It refuses to run if it is not in
     * such a package, or if a `context.db` exists that this test did not create (no marker file): that would be user data.
     */
    private val guard by lazy {
        MigrationFixtureGuard(ctx.packageName, { prodNamed.exists() }, { marker.exists() }, { marker.writeText("created by SqlCipherMigrationDeviceTest") }, { marker.delete() }, ::clean)
    }
    /** Ownership is recorded only after both guards pass; a refused run deletes nothing (JUnit still runs @After). */
    /** Builds each historical starting shape from the exported `schemas/<N>.json` (androidTest assets), keyed with this test's own key (never the production file, guarded above). */
    @get:Rule val helper = MigrationTestHelper(inst, ContextDatabase::class.java, emptyList(), SupportOpenHelperFactory(key.copyOf()).also { System.loadLibrary("sqlcipher") })

    @Before fun guardAndClean() { guard.acquire() }
    @After fun cleanAndUnmark() { guard.release() }

    private fun clean() { suffixes.forEach { File(file.path + it).delete(); File(prodNamed.path + it).delete() } }

    private fun room(encrypted: Boolean): ContextDatabase = Room.databaseBuilder(ctx, ContextDatabase::class.java, name).apply {
        if (encrypted) { System.loadLibrary("sqlcipher"); openHelperFactory(SupportOpenHelperFactory(key.copyOf())) }
    }.build()

    private fun mem(id: String, text: String) = MemoryEntity(id, null, text, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "")

    private suspend fun searchIds(db: ContextDatabase, q: String) = MemoryRepository(db.memoryDao()).search(q, 5).map { it.id }

    @Test fun plaintextToEncryptedAndBackKeepsRowsAndSearch() = runBlocking(Dispatchers.IO) {
        room(false).let { db ->
            try { MemoryRepository(db.memoryDao()).apply { add(mem("m1", "Milk carton in the fridge")); add(mem("m2", "दूध खत्म हो गया")) } } finally { db.close() }
        }
        val migrator = EncryptionMigrator(file, store, SqlCipherConverter) { key.copyOf() }
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
        val db = Room.databaseBuilder(ctx, ContextDatabase::class.java, name).openHelperFactory(DeferredOpenHelperFactory({ true }, { key.copyOf() })).build()
        try {
            MemoryRepository(db.memoryDao()).add(mem("m1", "keys on the shelf"))
            assertEquals(listOf("m1"), searchIds(db, "shelf"))
        } finally { db.close() }
        assertFalse(EncryptionMigrator.isPlaintextSqlite(file))
    }

    private val schemaDir = "com.rokid.context.memory.db.ContextDatabase"
    /** The latest exported schema version: the chain must land here. */
    private val latest = 19

    private fun schemaTables(version: Int): Set<String> {
        val json = inst.context.assets.open("$schemaDir/$version.json").bufferedReader().use { it.readText() }
        val entities = org.json.JSONObject(json).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): String = db.query(sql).use { it.moveToFirst(); it.getString(0) }

    /** Rows every historical shape carries: a memory (v1 columns, unchanged through v7; FTS triggers index it) and, from v2, a frame_state row. */
    private fun seedBase(db: SupportSQLiteDatabase, version: Int) {
        db.execSQL("INSERT INTO memory (id, frameId, text, type, tags, tier, confidence, reviewState, trusted, createdAt, derived, searchText) VALUES ('m1', NULL, 'keys on the shelf', 'note', '', 'SHORT', 0.8, 'OK', 0, 1, 0, 'keys on the shelf')")
        if (version >= 2) db.execSQL("INSERT INTO frame_state (frameId, state, attempts, lastError, claimedAt, claimId, updatedAt) VALUES ('f1', 'PENDING', 0, NULL, NULL, NULL, 1)")
    }

    private fun seedFor(db: SupportSQLiteDatabase, version: Int) {
        when (version) {
            3 -> {
                db.execSQL("INSERT INTO frame_cluster (clusterId, openedAt, closedAt, closeReason, repFrameIds, memoryId) VALUES ('closed-c', 1, 2, 'gap', '[]', NULL), ('open-c', 3, NULL, NULL, '[]', NULL)")
                db.execSQL("INSERT INTO frame_meta (frameId, wireFrameId, sessionId, motion, dwell, createdAt) VALUES ('fm1', 'w1', 's', 'STILL', 0, 1)")
                db.execSQL("INSERT INTO canvas_pipeline (id, name, draftJson, draftRev, builtIn, createdAt, updatedAt) VALUES ('p1', 'kept', '{}', 1, 0, 1, 1)")
                db.execSQL("INSERT INTO canvas_publish (pipelineId, version, hash, publishedAt, rolledBackAt, snapshotJson) VALUES ('p1', 1, 'h', 1, NULL, '{}')")
            }
            4 -> db.execSQL("INSERT INTO frame_meta (frameId, wireFrameId, sessionId, motion, dwell, createdAt, routedAt) VALUES ('fm1', 'w1', 's', 'STILL', 0, 1, NULL)")
            5 -> {
                db.execSQL("INSERT INTO canvas_publish (pipelineId, version, hash, publishedAt, rolledBackAt, snapshotJson) VALUES ('p1', 1, 'h', 1, NULL, '{}')")
                db.execSQL("INSERT INTO canvas_run (runId, pipelineId, version, pipelineHash, pipelineJson, status, frameCount, estCalls, calls, failures, startedAt, finishedAt, modelsJson) VALUES ('r1', 'p1', 1, 'h', '{}', 'DONE', 1, 1, 1, 0, 1, 2, '[]')")
                db.execSQL("INSERT INTO canvas_run_item (runId, frameId, nodeId, status) VALUES ('r1', 'fm1', 'n', 'OK')")
            }
            6 -> {
                db.execSQL("INSERT INTO canvas_publish (pipelineId, version, hash, publishedAt, rolledBackAt, snapshotJson, kind, mode) VALUES ('p1', 1, 'h', 1, NULL, '{}', 'interpreter', 'SHADOW')")
                db.execSQL("INSERT INTO canvas_live_trace (publishId, frameId, nodeId, mode, status, at) VALUES (1, 'fm1', '_batch', 'SHADOW', 'OK', 1)")
            }
            7 -> db.execSQL("INSERT INTO canvas_helper_job (jobId, capability, state, frameIdsJson, createdAt, updatedAt) VALUES ('j1', 'py', 'QUEUED', '[\"f1\"]', 1, 1)")
        }
    }

    private fun assertSurvivors(raw: SupportSQLiteDatabase, version: Int) {
        fun one(sql: String) = scalar(raw, sql)
        when (version) {
            3 -> {
                assertEquals("CLOSED", one("SELECT state FROM frame_cluster WHERE clusterId = 'closed-c'")); assertEquals("OPEN", one("SELECT state FROM frame_cluster WHERE clusterId = 'open-c'"))
                assertEquals("fm1", one("SELECT frameId FROM frame_meta WHERE frameId = 'fm1' AND routedAt IS NULL"))
                assertEquals("kept", one("SELECT name FROM canvas_pipeline WHERE id = 'p1'"))
                assertEquals("prompts", one("SELECT kind FROM canvas_publish WHERE pipelineId = 'p1'"))
            }
            4 -> assertEquals("fm1", one("SELECT frameId FROM frame_meta WHERE frameId = 'fm1' AND intentLabel IS NULL AND capturedAtMs IS NULL"))
            5 -> {
                assertEquals("prompts", one("SELECT kind FROM canvas_publish WHERE pipelineId = 'p1'"))
                assertEquals("r1", one("SELECT runId FROM canvas_run WHERE runId = 'r1' AND abGroup IS NULL"))
                assertEquals("n", one("SELECT nodeId FROM canvas_run_item WHERE runId = 'r1' AND calls IS NULL"))
            }
            6 -> {
                assertEquals("interpreter/SHADOW", one("SELECT kind || '/' || mode FROM canvas_publish WHERE pipelineId = 'p1'"))
                assertEquals("p1", one("SELECT pipelineId FROM canvas_publish WHERE pipelineId = 'p1' AND groupId IS NULL AND planJson IS NULL AND configHash IS NULL AND trialEndsAt IS NULL"))
                assertEquals("fm1", one("SELECT frameId FROM canvas_live_trace WHERE publishId = 1"))
            }
            7 -> {
                assertEquals("py/QUEUED/[\"f1\"]", one("SELECT capability || '/' || state || '/' || frameIdsJson FROM canvas_helper_job WHERE jobId = 'j1'"))
                assertEquals("j1", one("SELECT jobId FROM canvas_helper_job WHERE jobId = 'j1' AND jobType = '' AND sessionGen = 0 AND leaseGen = 0 AND frameForgetRev = 0 AND payloadJson IS NULL AND confirmedAtMs IS NULL"))
            }
        }
    }

    /**
     * One keyed fixture that is a faithful snapshot of the HISTORICAL schema [version] (built from its exported `<version>.json`, not by trimming the current schema),
     * seeded with rows, then migrated through both production builders to the CURRENT latest version (v19): rows survive, every v19 table exists, integrity_check passes.
     */
    private fun migratesToLatestFrom(version: Int) = runBlocking(Dispatchers.IO) {
        System.loadLibrary("sqlcipher")
        listOf(true, false).forEach { deferred ->
            clean()
            helper.createDatabase(DatabaseFactory.NAME, version).use { raw -> seedBase(raw, version); seedFor(raw, version) }
            val db = if (deferred) DatabaseFactory.buildDeferred(ctx, { true }) { key.copyOf() } else DatabaseFactory.build(ctx, encrypted = true) { key.copyOf() }
            try {
                val w = db.openHelper.writableDatabase
                assertEquals("v$version -> latest", latest, w.version)
                assertEquals(listOf("m1"), searchIds(db, "shelf"))
                if (version >= 2) assertEquals("f1", scalar(w, "SELECT frameId FROM frame_state WHERE frameId = 'f1'"))
                assertSurvivors(w, version)
                val names = w.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
                assertTrue("missing ${schemaTables(latest) - names}", names.containsAll(schemaTables(latest)))
                assertEquals("PRAGMA integrity_check", "ok", scalar(w, "PRAGMA integrity_check"))
            } finally { db.close() }
            assertFalse(EncryptionMigrator.isPlaintextSqlite(prodNamed))
        }
    }

    @Test fun encryptedDbMigratesToLatestFromV1Shape() = migratesToLatestFrom(1)
    @Test fun encryptedDbMigratesToLatestFromV2Shape() = migratesToLatestFrom(2)
    @Test fun encryptedDbMigratesToLatestFromV3Shape() = migratesToLatestFrom(3)
    @Test fun encryptedDbMigratesToLatestFromV4Shape() = migratesToLatestFrom(4)
    @Test fun encryptedDbMigratesToLatestFromV5Shape() = migratesToLatestFrom(5)
    @Test fun encryptedDbMigratesToLatestFromV6Shape() = migratesToLatestFrom(6)
    @Test fun encryptedDbMigratesToLatestFromV7Shape() = migratesToLatestFrom(7)
}
