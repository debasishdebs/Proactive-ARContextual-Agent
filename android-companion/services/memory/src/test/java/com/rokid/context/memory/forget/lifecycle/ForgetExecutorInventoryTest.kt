package com.rokid.context.memory.forget.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Forget lifecycle M4 (design 2.4): the ForgetExecutor is the ONE place that deletes forgettable rows. This source scan counts every call of a deleting DAO method outside the DAO implementations (`memory/db/`) and
 * the lifecycle package (executor and families). The LEGACY paths that still hold their own copy (flag OFF) are on a RATCHET: their count may not grow, and when a path is removed its entry must be lowered
 * ([ratchet]). A call in any other file fails. [permanent] are files that are not a Forget at all (Delete all is allowlisted by the design; a rebuild; a View's `invalidate`).
 */
class ForgetExecutorInventoryTest {
    private val deleting = listOf(
        ".deleteMemories(", ".deleteFrame(", ".deleteFrameSourcesFor(", ".deleteFrameSourcesOfFrames(", ".deleteFrameVectorsFor(", ".deleteFrameMetaFor(", ".deleteFrameStateFor(", ".deleteVectorsFor(", ".deleteEdgesFrom(", ".deleteEdgesTo(",
        ".invalidate(", ".redactAsk(", ".redactNudge(", ".forgetRangeTx(", ".deleteLabel(", ".deleteLabels(", ".deleteLabelsForFrames(", ".deleteLabelsBetween(", ".deleteRunRowsOfLabels(", ".dropHolding(", ".dropBetween(",
        ".deleteCanvasOutputsForFrames(", ".deleteSliceOutputsForFrames(", ".scrubOutputsOfLockedFrames(", ".wipeMemory(", ".expireForOrigins(", ".scrubParams(", ".scrubLogs(", ".scrubParamsForOrigins(", ".scrubLogsForOrigins(",
        ".purgeSensorBetween(", "momentDao().remove(", "sceneDao().remove(",
    )

    /** Legacy copies of the Forget bodies, used only while `forgetV2` is OFF: file -> deleting calls it still holds. Lower an entry when the legacy body is removed. */
    private val ratchet = mapOf(
        "ForgetService.kt" to 30, // cascade, forgetMomentTx, expirePhotosTx, dropCanvasOutputs (flag OFF)
        "MomentForget.kt" to 8,   // run (flag OFF)
        "MarkRecorder.kt" to 2,   // the mark undo (flag OFF; ON goes through ForgetExecutor.deleteMarks)
        "RetentionWorker.kt" to 1, // vectors of a described photo's retention pass: not yet moved
        "OutboxDrainer.kt" to 1,  // a frame row the outbox drain removes after its files went
    )
    /** Not a Forget: Delete all (allowlisted by the design: it clears every table and the files), a rebuild of derived memories, an origin fence, and a View's `invalidate`. */
    private val permanent = mapOf(
        "MemoryWipe.kt" to "Delete all (flag OFF; ON goes through ForgetExecutor.wipeMemory)",
        "ResummarizeWorker.kt" to "rebuild of invalidated derived memories (replaces a memory, never a Forget)",
        "PipelineRecords.kt" to "rewrites the edges of a memory the pipeline itself re-describes",
        "OriginFencedIntake.kt" to "expires and scrubs the proposals of an origin that was redacted before they were stored",
        "CxrGlassesLink.kt" to "View.invalidate, not a database call",
        "DiagControlRetry.kt" to "View.invalidate, not a database call",
    )

    private fun sources(): List<File> {
        val roots = listOf(File("src/main"), File("../orchestration/src/main"), File("../glasses-link/src/main"), File("../../phone/src/main"))
        assertTrue("the scan roots exist", roots.all { it.exists() })
        return (roots + File("..").listFiles().orEmpty().map { File(it, "src/main") }).distinct().filter { it.exists() }.flatMap { r -> r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }.distinctBy { it.canonicalPath }
    }
    private fun code(f: File) = f.readLines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n")
    private fun hits(f: File): Int { val c = code(f); return deleting.sumOf { p -> c.windowed(p.length).count { it == p } } }
    private fun scanned() = sources().filter { f -> val p = f.path.replace('\\', '/'); !p.contains("/memory/db/") && !p.contains("/lifecycle/") && !p.contains("src/main/java/com/rokid/context/memory/db/") && hits(f) > 0 }

    @Test fun onlyTheExecutorAndTheListedLegacyPathsDelete() {
        val seen = sources().map { it.name }
        assertTrue("the scan sees the executor, the legacy service and the wipe", "ForgetExecutor.kt" in seen && "ForgetService.kt" in seen && "MemoryWipe.kt" in seen)
        val offenders = scanned().filter { it.name !in ratchet && it.name !in permanent }.map { it.name }
        assertEquals("a file outside the ForgetExecutor deletes forgettable rows; route it through the executor", emptyList<String>(), offenders)
    }

    @Test fun theLegacyRatchetOnlyGoesDown() {
        val byName = scanned().associate { it.name to hits(it) }
        for ((name, max) in ratchet) {
            val n = byName[name] ?: 0
            assertTrue("$name holds $n deleting calls, more than its ratchet of $max: a new legacy delete", n <= max)
            assertEquals("$name holds $n deleting calls, fewer than its ratchet of $max: lower the ratchet", max, n)
        }
    }

    @Test fun everyFamilyThatDeletesImplementsSelfDelete() {
        val deleters = setOf(Family.PHOTO, Family.MEMORY, Family.MOMENT, Family.SCENE, Family.MARK, Family.TRACE, Family.RUN_RESULT, Family.ANSWER, Family.NUDGE, Family.PROPOSAL)
        for (f in Families.ALL) assertEquals("${f.family}", f.family in deleters, f.selfDeletes)
        // derived fields are deleted with their owning Moment or Scene (`MomentDao.remove`, `SceneDao.remove`), never alone
        assertTrue(!Families.of(Family.DERIVED_FIELD).selfDeletes)
    }
}
