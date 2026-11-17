package com.rokid.context.memory.db

import android.content.Context
import android.os.Build
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/** Builds a v1 database file from the exported `1.json` (no hand-written v1 SQL), one row in every v1 table. */
internal object V1Fixture {
    const val SCHEMA_DIR = "com.rokid.context.memory.db.ContextDatabase"
    val FRAME_TABLES = listOf("frame_meta", "frame_state", "frame_source", "frame_cluster", "frame_vector")

    fun helper() = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ContextDatabase::class.java)

    private fun schema(version: Int) = JSONObject(
        InstrumentationRegistry.getInstrumentation().context.assets.open("$SCHEMA_DIR/$version.json").bufferedReader().use { it.readText() },
    ).getJSONObject("database")

    /** Real tables of schema [version] (FTS shadow tables excluded) with their column names/types. */
    fun tables(version: Int): Map<String, List<Pair<String, String>>> {
        val ents = schema(version).getJSONArray("entities")
        return (0 until ents.length()).map { ents.getJSONObject(it) }.filter { !it.has("ftsVersion") }.associate { e ->
            val f = e.getJSONArray("fields")
            e.getString("tableName") to (0 until f.length()).map { f.getJSONObject(it).let { c -> c.getString("columnName") to c.getString("affinity") } }
        }
    }

    fun seed(db: SupportSQLiteDatabase) {
        tables(1).forEach { (t, cols) ->
            val vals = cols.joinToString(",") { (_, aff) -> when (aff) { "INTEGER" -> "1"; "REAL" -> "1.5"; "BLOB" -> "x'0102'"; else -> "'v1'" } }
            db.execSQL("INSERT INTO `$t` (${cols.joinToString(",") { "`${it.first}`" }}) VALUES ($vals)")
        }
    }

    /** One row in every table of schema [version] (FTS shadow tables excluded), values derived from the column affinity. */
    fun seedVersion(db: SupportSQLiteDatabase, version: Int) {
        tables(version).forEach { (t, cols) ->
            val vals = cols.joinToString(",") { (_, aff) -> when (aff) { "INTEGER" -> "1"; "REAL" -> "1.5"; "BLOB" -> "x'0102'"; else -> "'v$version'" } }
            db.execSQL("INSERT INTO `$t` (${cols.joinToString(",") { "`${it.first}`" }}) VALUES ($vals)")
        }
    }

    fun create(name: String): Unit = helper().createDatabase(name, 1).use { seed(it) }

    fun tableNames(db: SupportSQLiteDatabase): Set<String> = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    fun count(db: SupportSQLiteDatabase, t: String): Int = db.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
}
