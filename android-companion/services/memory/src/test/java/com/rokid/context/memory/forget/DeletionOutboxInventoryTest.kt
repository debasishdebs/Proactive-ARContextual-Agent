package com.rokid.context.memory.forget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The deletion inventory of the cleanup outbox (v0.9.5). Every production file that DELETES a photo, frame, trace or derived artefact through the shared cascades (`forgetRangeTx`,
 * `deleteSliceOutputsForFrames`, `deleteCanvasOutputsForFrames`, `deleteFrame`) must write its `cleanup_outbox` rows through the one helper (`CleanupOutbox.queue`); a new deleter that
 * is not in this list (or that does not queue) fails here, so a later change cannot add a deletion path and forget the glasses and the phone files. The DAO layer itself (`db/`) holds the
 * primitives and is exempt; its callers are what is listed.
 */
class DeletionOutboxInventoryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "src/main/java/com/rokid/context/memory") }.first { it.isDirectory }
    private val callPattern = Regex("""\b(forgetRangeTx|deleteSliceOutputsForFrames|deleteCanvasOutputsForFrames|deleteFrame)\(""")
    private val deleters = setOf("forget/ForgetService.kt", "forget/MomentForget.kt")

    private fun sources() = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).path to it.readText() }.toList()

    @Test fun theScanSeesTheSources() = assertTrue(sources().size > 30)

    @Test fun everyProductionDeleterIsListedAndQueuesThroughTheOneHelper() {
        val found = sources().filter { (path, text) -> !path.startsWith("db/") && callPattern.containsMatchIn(text) }.map { it.first }.toSet()
        assertEquals("a file that deletes through the cascades must be listed here AND queue its outbox rows", deleters, found)
        for ((path, text) in sources().filter { it.first in deleters }) {
            assertTrue("$path must write the outbox through CleanupOutbox", text.contains("CleanupOutbox.queue(") || text.contains("queueCleanup("))
        }
    }

    @Test fun theOnlyWriterOfOutboxRowsIsTheHelperTheLateFramePathAndTheWipe() {
        val writers = sources().filter { (path, text) -> !path.startsWith("db/") && (text.contains("CleanupOutboxEntity(") || text.contains("insertOutbox(")) }.map { it.first }.toSet()
        assertEquals(setOf("forget/CleanupOutbox.kt", "FrameIntake.kt"), writers) // Delete all writes its ALL row inside WipeDao (db/)
    }
}
