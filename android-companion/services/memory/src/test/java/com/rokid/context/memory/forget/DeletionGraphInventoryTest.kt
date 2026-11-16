package com.rokid.context.memory.forget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails when ANY table of `context.db` or `places.db` is added without a deletion decision, or a decision names a table that is gone, or WipeDao disagrees with the registry. The tables are read from Room's
 * EXPORTED SCHEMA (the newest `schemas/<database>/<version>.json`), which lists every entity including the FTS table, so no prefix filter can miss one (Forget lifecycle M1; it used to scan `canvas_`, `moment`, `object_`, `scene`).
 */
class DeletionGraphInventoryTest {
    private val mainDir: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "src/main/java/com/rokid/context/memory") }.first { it.isDirectory }
    private val schemaRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "schemas") }.first { it.isDirectory }

    /** Every table of the newest exported schema of each Room database of the module. */
    private val tables: List<String> = schemaRoot.listFiles().orEmpty().filter { it.isDirectory }.flatMap { dir ->
        val newest = dir.listFiles().orEmpty().filter { it.extension == "json" }.maxByOrNull { it.nameWithoutExtension.toInt() }!!
        Regex("\"tableName\"\\s*:\\s*\"(\\w+)\"").findAll(newest.readText()).map { it.groupValues[1] }.toList()
    }.toSet().toList()

    @Test fun theScanFindsTheTablesItShould() = assertTrue("scan broken: ${tables.size} tables", tables.size >= 55)

    @Test fun everyTableIsRegisteredInTheDeletionGraph() {
        val registered = DeletionGraph.TABLES.map { it.name }.toSet()
        val missing = tables.filter { it !in registered }
        assertTrue("tables with no deletion decision (add them to DeletionGraph, WipeDao and lifecycle.Families): $missing", missing.isEmpty())
    }

    @Test fun noRegisteredTableIsStale() {
        val stale = DeletionGraph.TABLES.map { it.name }.filter { it !in tables }
        assertTrue("registered but not in Room: $stale", stale.isEmpty())
        assertEquals("no duplicates", DeletionGraph.TABLES.size, DeletionGraph.TABLES.map { it.name }.toSet().size)
    }

    @Test fun everyKeptEntryStatesAReason() {
        (DeletionGraph.TABLES + DeletionGraph.PREFS + DeletionGraph.GLASSES_PREFS + DeletionGraph.GLASSES_FILES + DeletionGraph.DIRS).forEach { assertTrue("${it.name} needs a reason", it.why.length > 8) }
    }

    /** WipeDao is the code that deletes: its DELETE statements must match the registry exactly (kept tables are never deleted, wiped ones always are). */
    @Test fun wipeDaoDeletesExactlyTheWipedTables() {
        val src = File(mainDir, "db/WipeDao.kt").readText()
        val deleted = Regex("DELETE FROM (\\w+)\"").findAll(src).map { it.groupValues[1] }.toSet()
        assertEquals(DeletionGraph.wipedTables.toSet(), deleted)
        DeletionGraph.TABLES.filter { it.fate == DeletionGraph.Fate.KEPT }.forEach { assertTrue("${it.name} is kept but WipeDao deletes it", it.name !in deleted) }
    }
}
