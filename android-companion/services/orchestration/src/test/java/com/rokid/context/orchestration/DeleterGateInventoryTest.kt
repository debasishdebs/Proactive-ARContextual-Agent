package com.rokid.context.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The inventory of delete entry points: a source file of `:services:orchestration` or `:phone` (outside the DAO/service implementations in `:services:memory`) that calls a
 * deleting API must mention the [DataOpGate] (take it itself) or be listed here with the reason it runs inside a gated caller. A NEW deleter that does not use the gate fails this test.
 */
class DeleterGateInventoryTest {
    private val deleting = listOf(
        ".deleteLabel(", ".deleteLabelsForFrames(", ".deleteRunRowsOfLabels(", ".deleteWatchRuns(", ".forgetRangeTx(", "ForgetService(", "CanvasSlice3Forget(", "MomentForget(", ".wipeMemory(",
        ".deleteSliceOutputsForFrames(", ".deleteCanvasOutputsForFrames(",
    )
    /** Files that call a deleting API and are NOT themselves gated, each with why that is fine. */
    private val insideGatedCallers = mapOf("HubGraph.kt" to "it only constructs CanvasSlice3Forget for CanvasForgetCoordinator.forgetRange (gated) and calls the gated WatchRowCleanup")

    @Test fun everyDeleterOutsideTheMemoryModuleTakesTheGate() {
        val roots = listOf(File("src/main"), File("../../phone/src/main")).filter { it.exists() }
        assertTrue("the scan roots exist: ${roots.map { it.absolutePath }}", roots.size == 2)
        val offenders = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }.filter { f ->
            val src = f.readText()
            val code = src.lines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n")
                .replace("WatchRowCleanup.deleteWatchRuns(", "") // the gated wrapper itself
            deleting.any { it in code } && !code.contains("DataOpGate") && f.name !in insideGatedCallers
        }.map { it.name }
        assertEquals("a delete path that does not take the DataOpGate", emptyList<String>(), offenders)
    }
}
