package com.rokid.context.memory.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryMaintenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Rehearses MIGRATION_17_18 on a COPY of the owner's real plain-SQLite context.db. Skipped unless REHEARSAL_DB_DIR names a folder holding context.db (plus -wal/-shm if any).
 * The folder must be outside git. The test never touches it: it copies the files to a fresh scratch sub-folder and migrates that.
 * Output is counts and timing only. places.db is SQLCipher with a Keystore key, so it cannot be opened on a host and is not covered.
 * Run: REHEARSAL_DB_DIR=<dir> tools/dev/run_gradle_locked.sh <repo> <log> :services:memory:testDebugUnitTest --tests '*MigrationV17to18RealCopyRehearsalTest*'
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV17to18RealCopyRehearsalTest {
    private fun env(k: String): String? = (System.getenv(k) ?: System.getProperty(k))?.takeIf { it.isNotBlank() }
    private fun insideGitTree(f: File): Boolean { var d: File? = f.absoluteFile.canonicalFile; while (d != null) { if (File(d, ".git").exists()) return true; d = d.parentFile }; return false }

    private fun counts(db: SQLiteDatabase): Map<String, Int> {
        val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return names.associateWith { t -> db.rawQuery("SELECT COUNT(*) FROM `$t`", null).use { it.moveToFirst(); it.getInt(0) } }
    }
    private fun scalar(db: SQLiteDatabase, sql: String): String = db.rawQuery(sql, null).use { it.moveToFirst(); it.getString(0) }

    @Test fun migratesRealCopyV17ToV18() {
        val dirPath = env("REHEARSAL_DB_DIR")
        assumeTrue("REHEARSAL_DB_DIR not set: rehearsal skipped", dirPath != null)
        val src = File(dirPath!!)
        assertFalse("the rehearsal folder must not sit inside a git tree", insideGitTree(src))
        assertTrue("context.db missing in the folder", File(src, "context.db").isFile)
        val head = File(src, "context.db").inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        assertEquals("context.db is not plain SQLite (encrypted?)", "SQLite format 3\u0000", String(head, Charsets.ISO_8859_1))

        val scratch = File(src, "run-" + System.currentTimeMillis()).also { it.mkdirs() }
        listOf("context.db", "context.db-wal", "context.db-shm").forEach { n -> File(src, n).takeIf { it.isFile }?.copyTo(File(scratch, n)) }
        val target = File(scratch, "context.db")

        val raw = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
        val before = counts(raw); val beforeVersion = raw.version; val beforeIntegrity = scalar(raw, "PRAGMA integrity_check")
        raw.close()
        assertEquals(17, beforeVersion); assertEquals("ok", beforeIntegrity)
        println("REHEARSAL before: user_version=$beforeVersion integrity=$beforeIntegrity tables=${before.size}")
        before.forEach { (t, n) -> println("REHEARSAL before $t=$n") }

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun open() = Room.databaseBuilder(ctx, ContextDatabase::class.java, target.path).addCallback(MemoryMaintenance.openCheck)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18).build()

        val t0 = System.nanoTime()
        val db = open(); db.openHelper.writableDatabase; db.close()
        val ms1 = (System.nanoTime() - t0) / 1_000_000
        println("REHEARSAL first open (migrate) ms=$ms1")

        fun check(label: String): Map<String, Int> {
            val r = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(label, 18, r.version)
                assertEquals(label, "ok", scalar(r, "PRAGMA integrity_check"))
                val after = counts(r)
                before.forEach { (t, n) -> assertEquals("$label table $t", n, after[t]) }
                assertEquals(label, before.keys + setOf("place_card_log", "moment_location", "scene_centroid", "model_attribution", "place_link"), after.keys)
                assertEquals(label, 0, after["place_card_log"]); assertEquals(label, 0, after["moment_location"]); assertEquals(label, 0, after["scene_centroid"]); assertEquals(label, 0, after["place_link"]); assertEquals(label, 0, after["model_attribution"])
                val idx = r.rawQuery("SELECT name FROM sqlite_master WHERE type='index'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
                assertTrue(idx.toString(), idx.containsAll(setOf("index_place_card_log_at", "index_place_card_log_frameId", "index_moment_location_atMs", "index_moment_location_anchorId")))
                println("REHEARSAL $label: user_version=18 integrity=ok tables=${after.size} indexes=${idx.size} place_card_log=0 moment_location=0 scene_centroid=0 model_attribution=0")
                return after
            } finally { r.close() }
        }
        val a1 = check("after first open")

        val t1 = System.nanoTime()
        val db2 = open(); db2.openHelper.writableDatabase; db2.close()
        println("REHEARSAL second open ms=${(System.nanoTime() - t1) / 1_000_000}")
        assertEquals(a1, check("after second open"))
        println("REHEARSAL files bytes: db=${target.length()} scratch=${scratch.name}")
    }
}
