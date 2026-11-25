package com.rokid.context.canvas.moment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v18 WP10 source rules: where the Scene centroid (`scene_centroid`) may be touched. A new file that names the table, entity or DAO fails here until a person adds it to the allow-list. The centroid never reaches
 * AI prompts, Ask, exports, logs or the canvas API, and (for now) not the Developer dump either: the producer WP and any reader are new, deliberate allow-list entries.
 */
class SceneCentroidSourceRulesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "phone").isDirectory && File(it, "services").isDirectory }
    private val tokens = listOf("sceneCentroidDao", "SceneCentroidEntity", "scene_centroid")
    private val mains = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "graphify-out" }
        .filter { it.isFile && it.name.endsWith(".kt") && "/src/main/" in it.path }.toList()
    private fun code(f: File) = f.readLines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n")

    private val allowed = setOf(
        "SceneCentroid.kt", "ContextDatabase.kt", "DatabaseFactory.kt",
        // the Scene deletion, the Forget choke points and the wipe
        "DerivedStaleDao.kt", "ForgetService.kt", "ForgetExecutor.kt", "MomentForget.kt", "WipeDao.kt", "Families.kt", "ForgetPolicy.kt", "DeletionGraph.kt", "DesignDivergence.kt",
    )

    @Test fun sceneCentroidDaoEntityAndTableAppearOnlyInTheAllowList() {
        assertTrue("the scan sees the production sources", mains.size > 500 && mains.any { it.name == "SceneCentroid.kt" })
        val found = mains.filter { f -> val c = code(f); tokens.any { it in c } }.map { it.name }.toSet()
        assertEquals("files that name the Scene centroid outside the allow-list", emptySet<String>(), found - allowed)
    }

    @Test fun neverInAiPromptsAskExportOrTheCanvasApi() {
        val banned = mains.filter { f ->
            val p = f.path
            "/services/ai/" in p || "/services/connectors/" in p || f.name in setOf("PayloadSources.kt", "ExportDao.kt", "MemoryExport.kt", "CanvasApi.kt", "CanvasBackendImpl.kt", "ConfigExport.kt") ||
                "/orchestration/ask/" in p || f.name.contains("Ask") || f.name.contains("Prompt")
        }
        assertTrue("the banned set is not empty", banned.size > 20)
        val hits = banned.filter { f -> val c = code(f); tokens.any { it.lowercase() in c.lowercase() } || "SceneCentroid" in c }.map { it.name }
        assertEquals("the centroid never reaches AI, Ask, export or the canvas API", emptyList<String>(), hits)
    }

    @Test fun neverInExportLogsOrTheDump() {
        val exportFiles = mains.filter { it.name in setOf("ExportDao.kt", "MemoryExport.kt", "ConfigExport.kt", "DiagnosticsExport.kt") }
        assertTrue(exportFiles.size >= 3)
        for (f in exportFiles) { val c = code(f); assertTrue(f.name, tokens.none { it.lowercase() in c.lowercase() } && "SceneCentroid" !in c) }
        val dump = mains.filter { it.path.contains("/canvas/dump/") || it.name.startsWith("Dump") }
        assertTrue("the dump sources are seen", dump.any { it.name == "DumpReader.kt" })
        assertEquals("the Developer dump may not include the centroid yet", emptyList<String>(), dump.filter { f -> val c = code(f); tokens.any { it in c } || "SceneCentroid" in c }.map { it.name })
        val logs = mains.filter { it.path.contains("/log/") || it.name.contains("Log") && !it.name.contains("Catalog") }
        assertTrue("log sources are seen", logs.size > 3)
        assertEquals("no log or log writer names the centroid", emptyList<String>(), logs.filter { f -> val c = code(f); tokens.any { it in c } || "SceneCentroid" in c }.map { it.name })
    }

    @Test fun deleteAllHasNoCallerOutsideTheWipe() {
        val callers = mains.filter { it.name != "SceneCentroid.kt" && "sceneCentroidDao().deleteAll(" in code(it) }.map { it.name }
        assertEquals(emptyList<String>(), callers)
    }
}
