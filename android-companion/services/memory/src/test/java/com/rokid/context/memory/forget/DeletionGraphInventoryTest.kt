package com.rokid.context.memory.forget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails when a `canvas_*`, `moment*` or `cleanup_outbox` table is added without a deletion decision, or a decision names a table that is gone, or WipeDao disagrees with the registry. */
class DeletionGraphInventoryTest {
    private val mainDir: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "src/main/java/com/rokid/context/memory") }.first { it.isDirectory }
    /** Room's annotations are not reflectable (CLASS retention), so the entity declarations and the migration SQL are scanned instead. */
    private val tables: List<String> = mainDir.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
        val t = f.readText()
        Regex("tableName = \"((?:canvas_|moment)\\w*|cleanup_outbox)\"").findAll(t).map { it.groupValues[1] } + Regex("CREATE TABLE IF NOT EXISTS `((?:canvas_|moment)\\w*|cleanup_outbox)`").findAll(t).map { it.groupValues[1] }
    }.toSet().toList()

    @Test fun theScanFindsTheTablesItShould() = assertTrue("scan broken: ${tables.size} tables", tables.size >= 21)

    @Test fun everyCanvasTableIsRegisteredInTheDeletionGraph() {
        val registered = DeletionGraph.TABLES.map { it.name }.toSet()
        val missing = tables.filter { it !in registered }
        assertTrue("canvas tables with no deletion decision (add them to DeletionGraph and WipeDao): $missing", missing.isEmpty())
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
        val deleted = Regex("DELETE FROM ((?:canvas_|moment)\\w*)\"").findAll(src).map { it.groupValues[1] }.toSet()
        assertEquals(DeletionGraph.wipedTables.toSet(), deleted)
        DeletionGraph.TABLES.filter { it.fate == DeletionGraph.Fate.KEPT }.forEach { assertTrue("${it.name} is kept but WipeDao deletes it", it.name !in deleted) }
    }
}
