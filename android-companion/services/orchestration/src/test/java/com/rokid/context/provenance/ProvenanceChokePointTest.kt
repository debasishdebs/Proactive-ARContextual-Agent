package com.rokid.context.provenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Inventory (spec 2026-11-16 A6; patterns of PromptKeyImageUseTest and PlaceSurfaceInventoryTest): text built FROM photos is checked in ONE place, [ProvenanceGate]. Fails when
 *  1. main code re-implements the raw liveness checks (wipe-generation compare, tombstone reads, ForgetFence reads) outside the gate, the Forget engine and the DAOs, beyond the RATCHET of already-known sites below
 *     (a count can only go down: migrate a site to the gate, then lower its number; a new file or a higher count fails);
 *  2. a new writer of derived text appears (a file that builds a MemoryEntity, AskEntity, NudgeEntity or CanvasRunItemEntity, or calls memories.add) that is not in [WRITERS], or an allowed writer does not ask the gate or hold a lease;
 *  3. a new model-call site sends photo-derived text without declaring its sources (PromptKeyImageUseTest covers declaration and liveness; here, a call that builds its prompt from `photo`/caption/OCR variables with NONE).
 */
class ProvenanceChokePointTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private fun mainFiles() = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }.filter { it.extension == "kt" && "/src/main/" in it.path.replace('\\', '/') }.toList()
    private fun rel(f: File) = f.relativeTo(root).path.replace('\\', '/')

    /** The only places allowed to read the raw primitives for good: the gate, the Forget engine and the fence itself, the DAOs that declare them, the frame pipeline that handles photo bytes (out of scope: nothing is derived yet). */
    private val HOMES = listOf("provenance/ProvenanceGate.kt", "memory/forget/", "memory/db/", "data/frames/RokidFrameInbox.kt", "memory/FrameIntake.kt")

    private val RAW = listOf(
        "MemoryWipe.generation.get() !=" to Regex("""MemoryWipe\.generation\.get\(\)\s*!="""),
        "ForgetFence raw read" to Regex("""ForgetFence\.(allLive|liveIds|goneAmong)\("""),
        "tombstone read" to Regex("""forgetDao\(\)\.tombstoned\(|\bdao\.tombstoned\(|tombstonedAmong\("""),
    )

    /** Known sites outside the gate, per file: (primitive count). Migrate one to the gate, then lower the number here. Never raise it. */
    private val RATCHET = mapOf(
        "phone/src/main/java/com/rokid/context/phone/canvas/api/CanvasBackendImpl.kt" to 6,
        "services/orchestration/src/main/java/com/rokid/context/canvas/AskDebug.kt" to 2,
        "services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt" to 1,
        "services/orchestration/src/main/java/com/rokid/context/canvas/LiveExtract.kt" to 3,
        "services/orchestration/src/main/java/com/rokid/context/canvas/moment/DaoMomentSource.kt" to 1,
        "services/orchestration/src/main/java/com/rokid/context/canvas/moment/MomentBuilder.kt" to 1,
        "services/orchestration/src/main/java/com/rokid/context/data/runtime/QueueRuntimeStore.kt" to 1,
        "services/orchestration/src/main/java/com/rokid/context/orchestration/ExtractSave.kt" to 2,
        "services/orchestration/src/main/java/com/rokid/context/orchestration/PipelineRecords.kt" to 1, // existing(): the extraction lock on a frame listing
        "services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ResummarizeWorker.kt" to 2,
        "services/orchestration/src/main/java/com/rokid/context/work/OrchestrationScheduler.kt" to 1,
        "services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt" to 2,
        "services/orchestration/src/main/java/com/rokid/context/work/frames/intent/IntentSamplers.kt" to 1,
        "services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSamplers.kt" to 1,
        "services/orchestration/src/main/java/com/rokid/context/work/frames/scene/ClusterSave.kt" to 3,
    )

    internal fun rawCount(text: String) = RAW.sumOf { (_, re) -> text.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }.sumOf { re.findAll(it).count() } }

    @Test fun rawLivenessChecksLiveInTheGateOnly() {
        val files = mainFiles()
        assertTrue("scan sees the sources", files.size > 300)
        val over = mutableListOf<String>()
        files.forEach { f ->
            val p = rel(f)
            if (HOMES.any { it in p }) return@forEach
            val n = rawCount(f.readText())
            val allowed = RATCHET[p] ?: 0
            if (n > allowed) over += "$p: $n raw liveness checks (allowed $allowed). Ask ProvenanceGate instead."
        }
        assertEquals("photo-derived liveness checked outside the gate", emptyList<String>(), over)
    }

    @Test fun theRatchetOnlyListsRealSites() {
        val stale = RATCHET.filter { (p, n) -> n > 0 && rawCount(File(root, p).also { assertTrue("$p exists", it.exists()) }.readText()) < n }.keys
        assertEquals("ratchet entries above the real count: lower them", emptySet<String>(), stale)
    }

    /** Writers of derived text. Each must ask the gate (or hold a lease and verify through it) in the same file. */
    private val WRITERS = mapOf(
        "services/orchestration/src/main/java/com/rokid/context/orchestration/PipelineRecords.kt" to "ProvenanceGate",
        "services/orchestration/src/main/java/com/rokid/context/orchestration/AnswerService.kt" to "ProvenanceGate",
        "services/orchestration/src/main/java/com/rokid/context/orchestration/nudge/NudgeEngine.kt" to "ProvenanceGate",
        "services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ResummarizeWorker.kt" to "ProvenanceGate",
        "services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt" to "insertRunItemsLeased", // lease + wipe + tombstones inside the DAO transaction
        "services/memory/src/main/java/com/rokid/context/memory/legacy/LegacyImporter.kt" to "", // one-time import of the user's own legacy rows: no photo provenance
    )
    private val WRITER_CALL = Regex("""\b(MemoryEntity|AskEntity|NudgeEntity|CanvasRunItemEntity)\(|memories\.add\(|asks\.add\(|nudges\.addIgnore\(""")

    internal fun writersIn(files: List<File>): Map<String, String> = files.filter { f ->
        val p = rel(f)
        "/memory/db/" !in p && f.readLines().any { l -> val t = l.trimStart(); !t.startsWith("//") && !t.startsWith("*") && !t.contains("data class ") && WRITER_CALL.containsMatchIn(t) }
    }.associate { rel(it) to it.readText() }

    @Test fun everyWriterOfDerivedTextAsksTheGate() {
        val found = writersIn(mainFiles())
        assertTrue("scan sees the writers: ${found.keys}", found.size >= 4)
        val unknown = found.keys - WRITERS.keys
        assertEquals("new writer of photo-derived text outside the gate: add it to the gate and to WRITERS", emptySet<String>(), unknown)
        WRITERS.forEach { (p, token) -> if (token.isNotEmpty()) assertTrue("$p must reference $token", token in File(root, p).readText()) }
    }

    @Test fun theScanFailsOnAPlantedUnfencedWriteAndRawCheck() {
        val dir = createTempDir("planted")
        try {
            val planted = File(dir, "services/orchestration/src/main/java/x/Planted.kt").apply { parentFile.mkdirs(); writeText("fun f() { if (MemoryWipe.generation.get() != g || db.forgetDao().tombstoned(ids).isNotEmpty()) return; memories.add(MemoryEntity(1)) }\n") }
            assertEquals(2, rawCount(planted.readText()))
            assertTrue(WRITER_CALL.containsMatchIn(planted.readText()))
            val clean = "fun f() { gate.check(sources, generation) }"
            assertEquals(0, rawCount(clean))
            assertEquals(0, rawCount("// MemoryWipe.generation.get() != g\n * ForgetFence.allLive(x)"))
        } finally { dir.deleteRecursively() }
    }

    /** A prompt built from photo-derived variables must declare stored sources: a call that interpolates these into its prompt with `PayloadSources.NONE`, or with no `sources` argument at all (the default is NONE), fails. */
    @Test fun noModelCallBuildsPhotoDerivedTextWithSourcesNone() {
        val derived = Regex("""\b(caption|ocrText|sceneText|sceneSummary|photoLines|photoText)\b""")
        val bad = mutableListOf<String>()
        mainFiles().filter { "/model/provider/" !in rel(it) }.forEach { f ->
            val text = f.readText()
            Regex("""\bRoleCall\(""").findAll(text).forEach { m ->
                val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
                if (before.endsWith("class ") || before.endsWith("fun ")) return@forEach
                var depth = 0; var end = text.length
                for (i in m.range.last until text.length) { when (text[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) { end = i; break } } } }
                val call = text.substring(m.range.first, end + 1)
                val none = Regex("""\bsources\s*=\s*(com\.rokid\.context\.model\.)?PayloadSources\.NONE""").containsMatchIn(call)
                val omitted = !Regex("""\bsources\s*=""").containsMatchIn(call) // the default is NONE too
                if (derived.containsMatchIn(call) && (none || omitted)) bad += "${rel(f)}:${text.substring(0, m.range.first).count { it == '\n' } + 1}"
            }
        }
        assertEquals("model calls carrying photo-derived text with sources NONE", emptyList<String>(), bad)
    }
}
