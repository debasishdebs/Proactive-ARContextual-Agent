package com.rokid.context.canvas.moment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v18 WP4 source rules: where the Moment location may be touched. A new file that names the table, entity or DAO fails here until a person adds it to the allow-list. */
class MomentLocationSourceRulesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "phone").isDirectory && File(it, "services").isDirectory }
    private val tokens = listOf("momentLocationDao", "MomentLocationEntity", "moment_location")
    private val mains = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "graphify-out" }
        .filter { it.isFile && it.name.endsWith(".kt") && "/src/main/" in it.path }.toList()
    private fun code(f: File) = f.readLines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n")

    private val allowed = setOf(
        "MomentLocation.kt", "ContextDatabase.kt", "DatabaseFactory.kt",
        // the WP2 choke points and the wipe
        "ForgetService.kt", "ForgetExecutor.kt", "CanvasSlice3Dao.kt", "MomentForget.kt", "WipeDao.kt", "Families.kt", "Ref.kt", "ForgetPolicy.kt", "DeletionGraph.kt", "DesignDivergence.kt",
        // WP4 and WP5
        "MomentLocationRecorder.kt", "MomentLocationGraph.kt", "MomentLocationImport.kt", "MomentLocationRoutes.kt", "CanvasExtras.kt", "HubGraph.kt", "PrivacySettings.kt",
        // v1.7 Developer dump API (design 2026-11-26): the owner's ONE explicit exception. He asked to pull the saved location of his own Moments to his own laptop for offline experiments, behind Developer mode, the devDump
        // switch and a 10-minute arming. DumpReader is the single reader; its route (DumpRoutes.kt) only carries the JSON it builds and never names the store (pinned below). Every other path stays forbidden.
        "DumpReader.kt",
        // Developer IMPORT (owner round 7, 26 Nov): the importer is the one writer of a location a dump carried back (source IMPORTED, original time and place, never logged), behind Developer mode, a paired session and the devImport flag.
        "ImportCore.kt",
    )

    @Test fun momentLocationDaoEntityAndTableAppearOnlyInTheAllowList() {
        assertTrue("the scan sees the production sources", mains.size > 500 && mains.any { it.name == "MomentLocation.kt" })
        val found = mains.filter { f -> val c = code(f); tokens.any { it in c } }.map { it.name }.toSet()
        val extra = found - allowed
        assertTrue("files that name the Moment location outside the allow-list: $extra", extra.isEmpty())
    }

    @Test fun neverInAiPromptsAskExportOrTheCanvasApi() {
        val banned = mains.filter { f ->
            val p = f.path
            "/services/ai/" in p || "/services/connectors/" in p || f.name in setOf("PayloadSources.kt", "ExportDao.kt", "MemoryExport.kt", "CanvasApi.kt", "CanvasBackendImpl.kt", "ConfigExport.kt") ||
                "/orchestration/ask/" in p || f.name.contains("Ask") || f.name.contains("Prompt")
        }
        assertTrue("the banned set is not empty", banned.size > 20)
        val hits = banned.filter { f -> val c = code(f); tokens.any { it.lowercase() in c.lowercase() } || "MomentLocation" in c }.map { it.name }
        assertEquals("the location never reaches AI, Ask, export or the canvas API", emptyList<String>(), hits)
    }

    @Test fun deleteAllHasTheSingleCaller() {
        val callers = mains.filter { it.name != "ForgetPolicy.kt" && ".deleteAllMomentLocations(" in code(it) }.map { it.name }
        assertEquals(listOf("MomentLocationGraph.kt"), callers)
    }

    @Test fun momentLocationNeverInExport() {
        val exportFiles = mains.filter { it.name in setOf("ExportDao.kt", "MemoryExport.kt", "ConfigExport.kt", "DiagnosticsExport.kt") }
        assertTrue(exportFiles.size >= 3)
        for (f in exportFiles) { val c = code(f); assertTrue(f.name, tokens.none { it.lowercase() in c.lowercase() } && "MomentLocation" !in c) }
    }

    @Test fun dumpExceptionIsExactlyOneReaderAndItsRouteNeverNamesTheStore() {
        val dump = mains.filter { it.path.contains("/canvas/dump/") || it.name.startsWith("Dump") }
        assertTrue("the dump sources are seen", dump.any { it.name == "DumpReader.kt" } && dump.any { it.name == "DumpRoutes.kt" })
        val naming = dump.filter { f -> val c = code(f); tokens.any { it in c } }.map { it.name }
        assertEquals("only the dump reader names the location store", listOf("DumpReader.kt"), naming)
        // the route, the arming and the source interface never touch the DAO, the entity or the table, and no DAO of the dump package writes
        val daoCode = code(mains.first { it.name == "DumpDao.kt" })
        assertTrue("DumpDao is SELECT only", Regex("@(Insert|Update|Delete|Upsert)\\b").find(daoCode) == null && !Regex("(?i)\\b(INSERT|UPDATE|DELETE)\\b").containsMatchIn(daoCode.lines().filter { it.contains("@Query") }.joinToString("\n")))
        assertTrue("DumpDao does not read the location table", tokens.none { it.lowercase() in daoCode.lowercase() })
    }

    @Test fun dumpReaderIsNotInAnyBannedPathAndNoOtherRouteMayServeTheLocation() {
        // the allow-list entry must not leak into the banned set: the reader lives outside ai, connectors, ask, prompt, export and the canvas API files
        val reader = mains.first { it.name == "DumpReader.kt" }
        assertTrue(!reader.path.contains("/services/ai/") && !reader.path.contains("/connectors/") && !reader.path.contains("/ask/") && !reader.name.contains("Ask") && !reader.name.contains("Prompt"))
        val servingLocation = mains.filter { f -> f.path.contains("/phone/canvas/") && f.name != "CanvasExtras.kt" && f.name != "MomentLocationRoutes.kt" && code(f).let { c -> tokens.any { it in c } } }.map { it.name }
        assertEquals("no canvas route file other than the import route names the location store", emptyList<String>(), servingLocation)
    }
}
