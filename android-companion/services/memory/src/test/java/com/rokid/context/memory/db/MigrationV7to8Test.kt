package com.rokid.context.memory.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** v7 → v8 (canvas slice 3b, WP3): additive columns on `canvas_helper_job` and the new `canvas_helper_cleanup` table, started from the REAL exported schemas. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV7to8Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-78"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
    private val newJobCols = listOf(
        "jobType", "ownerRef", "sessionGen", "leaseGen", "lastHeartbeatMs", "deadlineMs", "bundleHash", "payloadJson", "resultJson", "resultHash", "confirmExpiresAtMs",
        "confirmedAtMs", "wipeGen", "installId", "deletionCounter", "frameForgetRev", "itemCount", "sourceChars", "failCode", "voidReason",
    )

    private fun v7() = helper.createDatabase(name, 7).also { V1Fixture.seedVersion(it, 7); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 8, true, MIGRATION_7_8)

    @Test fun upgradeKeepsExistingHelperRowsWithDefaults() {
        v7()
        val db = migrate()
        assertEquals("the seeded v7 helper row survives", 1, V1Fixture.count(db, "canvas_helper_job"))
        db.query("SELECT jobId, capability, state, frameIdsJson, jobType, sessionGen, leaseGen, wipeGen, installId, deletionCounter, frameForgetRev, itemCount, sourceChars FROM canvas_helper_job").use {
            assertTrue(it.moveToFirst())
            assertEquals("v7", it.getString(0)); assertEquals("v7", it.getString(1)); assertEquals("v7", it.getString(2))
            assertEquals("", it.getString(4)); (5..12).forEach { i -> if (i == 8) assertEquals("", it.getString(i)) else assertEquals("col $i", 0L, it.getLong(i)) }
        }
        db.close()
    }

    @Test fun newColumnsExistWithTheirDefaultsForAnOldWriter() {
        v7()
        val db = migrate()
        // A writer that knows only the v7 columns still inserts, and reads the v8 defaults back.
        db.execSQL("INSERT INTO canvas_helper_job (jobId, capability, state, createdAt, updatedAt) VALUES ('old', 'py', 'QUEUED', 1, 1)")
        db.query("SELECT ${newJobCols.joinToString(",")} FROM canvas_helper_job WHERE jobId = 'old'").use {
            assertTrue(it.moveToFirst())
            val nullable = setOf("ownerRef", "lastHeartbeatMs", "deadlineMs", "bundleHash", "payloadJson", "resultJson", "resultHash", "confirmExpiresAtMs", "confirmedAtMs", "failCode", "voidReason")
            newJobCols.forEachIndexed { i, c -> if (c in nullable) assertTrue("$c null", it.isNull(i)) else assertFalse("$c has a default", it.isNull(i)) }
            assertEquals("", it.getString(0)); assertEquals("", it.getString(13))
        }
        db.close()
    }

    @Test fun cleanupTableExistsEmptyAndAutoGenerates() {
        v7()
        val db = migrate()
        assertTrue(V1Fixture.tableNames(db).contains("canvas_helper_cleanup")); assertEquals(0, V1Fixture.count(db, "canvas_helper_cleanup"))
        db.execSQL("INSERT INTO canvas_helper_cleanup (jobId, reason, createdAt) VALUES ('*', 'wipe', 1)")
        db.execSQL("INSERT INTO canvas_helper_cleanup (jobId, reason, createdAt) VALUES ('j', 'void', 2)")
        assertEquals(listOf(1L, 2L), db.query("SELECT cleanupId FROM canvas_helper_cleanup ORDER BY cleanupId").use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } })
        db.close()
    }

    @Test fun noOtherTableChangedAndTheNewColumnsAreAppended() {
        val v7 = V1Fixture.tables(7); val v8 = V1Fixture.tables(8)
        assertEquals(v7.keys + "canvas_helper_cleanup", v8.keys)
        v7.forEach { (t, cols) ->
            if (t == "canvas_helper_job") {
                assertEquals("v7 columns of $t keep their order", cols, v8[t]!!.take(cols.size))
                assertEquals(newJobCols.toSet(), v8[t]!!.drop(cols.size).map { it.first }.toSet())
                assertEquals(20, v8[t]!!.size - cols.size)
            } else assertEquals("table $t is unchanged", cols, v8[t])
        }
        assertEquals(listOf("cleanupId", "jobId", "reason", "createdAt"), v8["canvas_helper_cleanup"]!!.map { it.first })
        assertTrue("no payload-carrying column besides the job table", v8["canvas_helper_cleanup"]!!.none { it.first.contains("payload", true) || it.first.contains("result", true) })
    }

    @Test fun theFrozenV7SchemaIsUntouched() {
        val v7 = V1Fixture.tables(7)
        assertEquals(listOf("jobId", "capability", "state", "leaseOwner", "leaseUntilMs", "frameIdsJson", "createdAt", "updatedAt"), v7["canvas_helper_job"]!!.map { it.first })
        assertFalse(v7.containsKey("canvas_helper_cleanup"))
    }

    /** The migration source holds only ADD COLUMN and CREATE TABLE IF NOT EXISTS: no DROP, RENAME, DELETE, UPDATE, no copy of rows. */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val body = src.substring(src.indexOf("val MIGRATION_7_8"), src.indexOf("val MIGRATION_8_9"))
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(21, sql.size)
        sql.forEach { s -> assertTrue(s, s.startsWith("ALTER TABLE `canvas_helper_job` ADD COLUMN") || s.startsWith("CREATE TABLE IF NOT EXISTS `canvas_helper_cleanup`")) }
        sql.forEach { s -> listOf("DROP", "RENAME", "DELETE", "UPDATE", "INSERT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v7(); migrate().close() }

    /** Every canonical lineage file (v1 and v2 originals, v3 to v7) reaches v8 with its rows intact. */
    @Test fun allUpgradePaths1To8() {
        (1..7).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 8, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            assertTrue(V1Fixture.tableNames(db).contains("canvas_helper_cleanup")); assertEquals(0, V1Fixture.count(db, "canvas_helper_cleanup"))
            db.close()
        }
    }
}
