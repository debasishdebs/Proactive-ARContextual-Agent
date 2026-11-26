package com.rokid.context.canvas.moment

import com.rokid.context.memory.db.ModelAttributionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v18 WP11a source rules: where the model attribution (`model_attribution`: which model wrote a stored text) may be touched. A new file that names the table, entity, DAO or kind fails here until a person adds it to
 * the allow-list. The row holds names and a number only: no photo, no text, no prompt (the entity has no such field), and it never reaches AI prompts, Ask retrieval, exports or logs. The Developer dump is the one reader.
 */
class ModelAttributionSourceRulesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "phone").isDirectory && File(it, "services").isDirectory }
    private val tokens = listOf("modelAttributionDao", "ModelAttribution", "model_attribution", "AttributionKind")
    private val mains = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "graphify-out" }
        .filter { it.isFile && it.name.endsWith(".kt") && "/src/main/" in it.path }.toList()
    private fun code(f: File) = f.readLines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n")
    private fun names(f: File) = code(f).let { c -> tokens.any { it in c } }

    private val writers = setOf("ImportStores.kt", "ExtractSave.kt", "ClusterSave.kt", "PipelineRecords.kt", "QueueDrainWorker.kt", "ResummarizeWorker.kt", "AnswerService.kt", "DerivePorts.kt", "ObjectAudit.kt")
    private val deletes = setOf(
        "ModelAttribution.kt", "ContextDatabase.kt", "DerivedStaleDao.kt", "ObjectDao.kt", "ForgetDao.kt", "MemoryDaos.kt", "WipeDao.kt",
        "ForgetService.kt", "ForgetExecutor.kt", "MomentForget.kt", "PlaceRefSweep.kt", "Families.kt", "ForgetPolicy.kt", "DeletionGraph.kt", "DesignDivergence.kt",
    )
    private val allowed = writers + deletes + setOf("DumpReader.kt")

    @Test fun attributionAppearsOnlyInTheAllowList() {
        assertTrue("the scan sees the production sources", mains.size > 500 && mains.any { it.name == "ModelAttribution.kt" })
        assertEquals("files that name the model attribution outside the allow-list", emptySet<String>(), mains.filter(::names).map { it.name }.toSet() - allowed)
        assertTrue("every allow-list entry is real (a stale entry is removed)", mains.filter(::names).map { it.name }.toSet().containsAll(allowed))
    }

    @Test fun theRowHoldsNamesAndANumberNeverTextOrPhotoData() {
        val fields = ModelAttributionEntity::class.java.declaredFields.filter { !it.isSynthetic }.associate { it.name to it.type.simpleName }
        assertEquals(mapOf("kind" to "String", "refId" to "String", "model" to "String", "modelVersion" to "String", "promptRev" to "String", "recordedAt" to "long"), fields)
        assertTrue("no byte array or blob in the entity", fields.values.none { it.contains("byte", ignoreCase = true) })
    }

    @Test fun neverInAiPromptsAskRetrievalExportOrConnectors() {
        val banned = mains.filter { f ->
            val p = f.path
            "/services/ai/" in p || "/services/connectors/" in p || f.name in setOf("PayloadSources.kt", "ExportDao.kt", "MemoryExport.kt", "CanvasApi.kt", "ConfigExport.kt", "DiagnosticsExport.kt") ||
                f.name.contains("Retriev") || f.name.contains("Prompt") || f.name.contains("Ask")
        }
        assertTrue("the banned set is not empty", banned.size > 20)
        assertEquals("the attribution never reaches AI prompts, Ask retrieval, export or connectors", emptyList<String>(), banned.filter(::names).map { it.name })
    }

    @Test fun neverInALogLineAndTheDumpIsTheOnlyReader() {
        val logLines = mains.filter(::names).flatMap { f -> code(f).lines().filter { "PcLog." in it || "Log.i(" in it || "Log.w(" in it }.filter { l -> tokens.any { it in l } }.map { f.name } }
        assertEquals("no log line names the attribution", emptyList<String>(), logLines)
        val dump = mains.filter { it.path.contains("/canvas/dump/") || it.name.startsWith("Dump") }
        assertTrue("the dump sources are seen", dump.any { it.name == "DumpReader.kt" })
        assertEquals(listOf("DumpReader.kt"), dump.filter(::names).map { it.name })
        val readers = mains.filter { f -> "modelAttributionDao()" in code(f) && (".get(" in code(f).substringAfter("modelAttributionDao()").take(40) || ".ofRefs(" in code(f)) }.map { it.name }.toSet()
        assertEquals("only the dump reads attribution rows", setOf("DumpReader.kt"), readers)
    }

    @Test fun deleteAllHasNoCallerOutsideTheWipe() {
        assertEquals(emptyList<String>(), mains.filter { it.name != "ModelAttribution.kt" && "modelAttributionDao().deleteAll(" in code(it) }.map { it.name })
    }
}
