package com.rokid.context.canvas.dumpimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The import writes ONLY through the app's normal write paths: no raw SQL from input, no DAO insert of a Moment, memory, frame, mark or card, no new table. The one DAO the importer writes itself is `moment_location`
 * (`insertIfAbsent`, the same call the recorder and the GPS backfill make) and the `frame_source` edge the extraction writes; both are pinned here.
 */
class ImportSourceRulesTest {
    private val root = File("..").let { if (File(it, "services").isDirectory) it else File("../..") }
    private val files = listOf("services/orchestration/src/main/java/com/rokid/context/canvas/dumpimport", "phone/src/main/java/com/rokid/context/phone/canvas/api/ImportRoutes.kt")
        .flatMap { File(root, it).let { f -> if (f.isDirectory) f.listFiles()!!.toList() else listOf(f) } }
        .filter { it.name.endsWith(".kt") }
    private fun code(f: File) = f.readLines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n")

    @Test fun noRawSqlNoQueryNoOpenHelper() {
        assertTrue("the import sources are seen", files.size >= 3)
        for (f in files) {
            val c = code(f)
            for (t in listOf("@Query", "@Insert", "execSQL", "rawQuery", "openHelper", "SupportSQLiteQuery", "SimpleSQLiteQuery", "INSERT INTO", "compileStatement")) assertTrue("${f.name} must not use $t", t !in c)
        }
    }

    @Test fun onlyTheReviewedDaoWritesAppear() {
        val writes = Regex("""\.(insert\w*|upsert\w*|put\w*|delete\w*|update\w*|record|wipe\w*)\(""")
        val found = files.flatMap { f -> writes.findAll(code(f).replace("file.delete()", "")).map { it.groupValues[1] } }.toSet()
        // reviewed: the location row (insertIfAbsent, as the recorder and the GPS backfill write it) and the frame_source edge (insertAll, as the extraction writes it); everything else goes through
        // ImportFrameWriter (frame ingest), ImportMemoryWriter (appendFact), recordMark (MarkRecorder) and build (MomentBuilder)
        assertEquals(setOf("insertIfAbsent", "insertAll"), found)
    }

    @Test fun neverImportsADaoOrDatabaseBuilderOfItsOwn() {
        for (f in files) assertTrue("${f.name} builds no database", "Room.databaseBuilder" !in code(f) && "DatabaseFactory" !in code(f))
    }
}
