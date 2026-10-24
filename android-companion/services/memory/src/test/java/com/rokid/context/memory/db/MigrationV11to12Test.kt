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

/**
 * v11 → v12 (sensor features per Moment second): one new table, `moment_sensor`, started from the REAL exported schemas (plain SQLite, Robolectric). An encrypted (SQLCipher) migration run
 * is NOT covered here: that needs the emulator AVD MyEmulator and is a separate androidTest that has not been written or run.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationV11to12Test {
    @get:Rule val helper = V1Fixture.helper()
    private val name = "migration-test-1112"
    private val all = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
    private val newTables = setOf("moment_sensor")

    private fun v11() = helper.createDatabase(name, 11).also { V1Fixture.seedVersion(it, 11); it.close() }
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(name, 12, true, MIGRATION_11_12)

    @Test fun upgradeKeepsEveryV11RowAndCreatesOneEmptyTable() {
        v11()
        val db = migrate()
        V1Fixture.tables(11).keys.forEach { t -> assertEquals("v11 table $t", 1, V1Fixture.count(db, t)) }
        newTables.forEach { t -> assertTrue(t, V1Fixture.tableNames(db).contains(t)); assertEquals("$t starts empty", 0, V1Fixture.count(db, t)) }
        db.close()
    }

    @Test fun noExistingTableChanged() {
        val v11 = V1Fixture.tables(11); val v12 = V1Fixture.tables(12)
        assertEquals(v11.keys + newTables, v12.keys)
        v11.forEach { (t, cols) -> assertEquals("table $t is unchanged", cols, v12[t]) }
    }

    @Test fun theRowHoldsTheSevenFeaturesTheStateTheContextTheSourceTraceAndTheClockQuality() {
        val cols = V1Fixture.tables(12).getValue("moment_sensor").map { it.first }
        assertEquals(
            listOf(
                "momentId", "phoneSecond", "traceId", "traceRevision", "clockQuality", "uncertaintyMs",
                "gyroMax", "gyroMean", "gyroRms", "aboveOnFrac", "accVar", "stepHz", "rotRad", "motionState", "contextCode", "nGyro", "nAcc",
            ),
            cols,
        )
    }

    @Test fun aSecondIsKeyedByMomentAndPhoneSecond() {
        v11()
        val db = migrate()
        val ins = "INTO moment_sensor (momentId, phoneSecond, traceId, traceRevision, clockQuality, uncertaintyMs, gyroMax, gyroMean, gyroRms, aboveOnFrac, accVar, stepHz, rotRad, motionState, nGyro, nAcc) VALUES"
        db.execSQL("INSERT $ins ('00000001', 1, 't', 1, 'OFFSET_ESTIMATED', 5, 0, 0, 0, 0, 0, 0, 0, 'STILL', 1, 1)")
        db.execSQL("INSERT OR IGNORE $ins ('00000001', 1, 'u', 2, 'OFFSET_ESTIMATED', 5, 0, 0, 0, 0, 0, 0, 0, 'STILL', 1, 1)")
        assertEquals(1, V1Fixture.count(db, "moment_sensor"))
        db.close()
    }

    @Test fun theFrozenV11SchemaIsUntouched() {
        assertFalse(V1Fixture.tables(11).containsKey("moment_sensor"))
    }

    /** The migration holds only CREATE TABLE / INDEX IF NOT EXISTS: no ALTER, DROP, RENAME, DELETE, UPDATE, INSERT, no copy of rows. */
    @Test fun migrationIsAdditiveOnly() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").isFile) d = d.parentFile
        val src = File(requireNotNull(d), "src/main/java/com/rokid/context/memory/db/ContextDatabase.kt").readText()
        val sql = Regex("execSQL\\(\"([^\"]*)\"\\)").findAll(src.substring(src.indexOf("val MIGRATION_11_12"))).map { it.groupValues[1] }.toList()
        assertEquals(2, sql.size)
        sql.forEach { s -> assertTrue(s, s.startsWith("CREATE TABLE IF NOT EXISTS") || s.startsWith("CREATE INDEX IF NOT EXISTS")) }
        sql.forEach { s -> listOf("ALTER", "DROP", "RENAME", "DELETE", "UPDATE", "INSERT").forEach { w -> assertFalse("$w in $s", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) } }
    }

    @Test fun migratedMatchesExportedSchema() { v11(); migrate().close() }

    /** Every canonical lineage file (v1 and v2 originals, v3 to v11) reaches v12 with its rows intact. */
    @Test fun allUpgradePaths1To12() {
        (1..11).forEach { from ->
            val n = "$name-from$from"
            helper.createDatabase(n, from).also { if (from == 1) V1Fixture.seed(it) else V1Fixture.seedVersion(it, from); it.close() }
            val db = helper.runMigrationsAndValidate(n, 12, true, *all)
            V1Fixture.tables(from).keys.forEach { t -> assertEquals("v$from table $t", 1, V1Fixture.count(db, t)) }
            newTables.forEach { t -> assertEquals("$t empty", 0, V1Fixture.count(db, t)) }
            db.close()
        }
    }
}
