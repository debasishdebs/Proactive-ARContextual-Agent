package com.rokid.context.memory.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v4 → v5 (wave B intent + wave 3 capture time): four additive nullable columns on frame_meta, started from a REAL v4 file. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration4To5Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-45"
    private val newCols = listOf("intentLabel", "intentConf", "intentAt", "capturedAtMs")

    private fun v4() = helper.createDatabase(name, 4).also { V1Fixture.seedVersion(it, 4); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5)

    @Test fun v4RowsSurvive() {
        v4()
        val db = migrate()
        V1Fixture.tables(4).keys.forEach { t -> assertEquals("table $t", 1, V1Fixture.count(db, t)) }
        assertEquals("v4", db.query("SELECT wireFrameId FROM frame_meta").use { it.moveToFirst(); it.getString(0) })
        db.close()
    }

    @Test fun newColumnsExistAndAreNull() {
        v4()
        val db = migrate()
        db.query("SELECT ${newCols.joinToString(",")} FROM frame_meta").use {
            assertTrue(it.moveToFirst())
            newCols.indices.forEach { i -> assertTrue("${newCols[i]} is NULL", it.isNull(i)) }
        }
        // An old writer that omits the new columns still inserts (default NULL).
        db.execSQL("INSERT INTO frame_meta (frameId, motion, dwell, createdAt) VALUES ('n', 'STILL', 0, 1)")
        assertTrue(db.query("SELECT capturedAtMs FROM frame_meta WHERE frameId = 'n'").use { it.moveToFirst(); it.isNull(0) })
        db.close()
    }

    @Test fun noOtherTableChanged() {
        val v4 = V1Fixture.tables(4); val v5 = V1Fixture.tables(5)
        assertEquals(v4.keys, v5.keys)
        v4.forEach { (t, cols) ->
            if (t == "frame_meta") assertEquals("existing columns of $t keep their order", cols, v5[t]!!.take(cols.size))
            else assertEquals("columns of $t", cols, v5[t])
        }
        assertEquals(newCols, v5["frame_meta"]!!.drop(v4["frame_meta"]!!.size).map { it.first })
        assertEquals(listOf("TEXT", "REAL", "INTEGER", "INTEGER"), v5["frame_meta"]!!.drop(v4["frame_meta"]!!.size).map { it.second })
    }

    @Test fun migratedMatchesExportedSchema() { v4(); migrate().close() }

    @Test fun v3ToV5Chain() {
        helper.createDatabase(name, 3).also { V1Fixture.seedVersion(it, 3); it.close() }
        val db = helper.runMigrationsAndValidate(name, 5, true, MIGRATION_3_4, MIGRATION_4_5)
        V1Fixture.tables(3).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(db, it)) }
        assertTrue(db.query("SELECT capturedAtMs FROM frame_meta").use { it.moveToFirst(); it.isNull(0) })
        db.close()
    }

    @Test fun v1ToV5Chain() {
        helper.createDatabase(name, 1).also { V1Fixture.seed(it); it.close() }
        val db = helper.runMigrationsAndValidate(name, 5, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        V1Fixture.tables(1).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(db, it)) }
        db.close()
    }

    // ---- DAO over the migrated shape ----
    private val mem by lazy { DatabaseFactory.inMemory(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()) }

    private suspend fun meta(id: String, createdAt: Long, intentLabel: String? = null) =
        mem.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, null, null, null, null, null, createdAt, intentLabel = intentLabel))

    @Test fun setIntentThenReadBack() = runBlocking {
        meta("a", 10)
        assertEquals(1, mem.frameMetaDao().setIntent("a", "DRIVING", 0.8f, 99))
        val a = mem.frameMetaDao().get("a")!!
        assertEquals("DRIVING", a.intentLabel); assertEquals(0.8f, a.intentConf!!, 0f); assertEquals(99L, a.intentAt)
        assertEquals(0, mem.frameMetaDao().setIntent("missing", "OTHER", 0.1f, 1))
        mem.close()
    }

    @Test fun newestUnlabelledSkipsLabelledAndOldRows() = runBlocking {
        meta("old", 5); meta("mid", 20); meta("new", 30, intentLabel = "WALKING"); meta("newer", 40)
        val dao = mem.frameMetaDao()
        assertEquals("newer", dao.newestUnlabelled(10)!!.frameId)
        dao.setIntent("newer", "OTHER", 0.5f, 1)
        assertEquals("mid", dao.newestUnlabelled(10)!!.frameId)
        assertEquals(null, dao.newestUnlabelled(50))
        mem.close()
    }

    @Test fun setCapturedAtWritesOnlyTheCaptureTime() = runBlocking {
        meta("a", 10)
        assertEquals(1, mem.frameMetaDao().setCapturedAt("a", 1234))
        val a = mem.frameMetaDao().get("a")!!
        assertEquals(1234L, a.capturedAtMs); assertEquals(10L, a.createdAt); assertEquals(null, a.intentLabel)
        mem.close()
    }
}
