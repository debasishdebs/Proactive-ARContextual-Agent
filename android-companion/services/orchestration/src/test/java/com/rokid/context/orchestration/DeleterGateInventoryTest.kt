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
        ".deleteLabel(", ".deleteLabelsForFrames(", ".deleteRunRowsOfLabels(", ".deleteWatchRuns(", ".forgetRangeTx(", "ForgetService(", "CanvasSlice3Forget(", "MomentForget(", "ScopedForget(", ".wipeMemory(",
        ".deleteSliceOutputsForFrames(", ".deleteCanvasOutputsForFrames(", ".deleteRun(", ".pruneRuns(",
    )
    /** Files that call a deleting API and are NOT themselves gated, each with why that is fine. */
    private val insideGatedCallers = mapOf("HubGraph.kt" to "it only constructs CanvasSlice3Forget for CanvasForgetCoordinator.forgetRange (gated) and calls the gated WatchRowCleanup",
        "RetentionPreview.kt" to "it constructs a ForgetService only because Retention needs one, and calls only the read-only Retention.plan: nothing is ever expired or removed",
        "CanvasApi.kt" to "it only routes DELETE runs/:id to CanvasBackendImpl.deleteRun, which takes the DataOpGate (CanvasApi maps ExportInProgress to 409)")

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

    /** A range-forget entry point must run the same post-commit cleanup as a fact or photo Forget (`ForgetActions.apply`: export cache, reminders, notifications, outbox follow-up), or its leftovers keep forgotten text. */
    @Test fun everyRangeForgetEntryPointRunsThePostCommitCleanup() {
        val roots = listOf(File("src/main"), File("../../phone/src/main")).filter { it.exists() }
        val callers = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }.filter { f ->
            f.readLines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n").contains(".forgetRange(")
        }
        assertTrue("the scan sees the coordinator: ${callers.map { it.name }}", callers.any { it.name == "CanvasForgetCoordinator.kt" })
        val offenders = callers.filter { f -> val t = f.readText(); !t.contains("afterCascade(") && !t.contains("ForgetActions.apply(") && !t.contains("ForgetActions.afterRange(") }.map { it.name }
        assertEquals("a range Forget entry point without the post-commit cleanup", emptyList<String>(), offenders)
    }

    /**
     * v0.9.7 slice C: every GRANULAR Forget a user can start (Forget fact, Forget photo, Forget Moment, Forget Scene) runs through `ForgetActions.forgetScopedOutcome` (the Forget scope setting). A phone file that calls the
     * unscoped `forgetFactOutcome` / `forgetMomentOutcome` fails this test unless it is listed here with the reason it is not a scoped Forget.
     */
    private val unscopedByRuling = mapOf("MarkGraph.kt" to "mark undo: it removes the photo of the mark being undone (an Undo, not a Forget action; decision D1 of the slice C handoff)")

    @Test fun everyGranularForgetEntryOfThePhoneUsesTheForgetScope() {
        val unscoped = listOf("forgetFactOutcome(", "forgetMomentOutcome(", "ForgetActions.forgetMoment(", "ForgetActions.forgetFact(")
        val phone = File("../../phone/src/main").also { assertTrue("the phone sources exist", it.exists()) }
        val code = { f: File -> f.readLines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n") }
        val files = phone.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue("the scan sees the production Forget port and the mark graph", files.any { it.name == "ProductionForget.kt" } && files.any { it.name == "MarkGraph.kt" })
        val offenders = files.filter { f -> unscoped.any { it in code(f) } && f.name !in unscopedByRuling }.map { it.name }
        assertEquals("a granular Forget that ignores the Forget scope", emptyList<String>(), offenders)
        // ruling O6: the mark undo must use the variant that never deletes a pin
        val mg = code(files.single { it.name == "MarkGraph.kt" })
        assertTrue("MarkGraph undoes a mark's photo without deleting pins", mg.contains("forgetMomentKeepPinsOutcome(") && !mg.contains("forgetMomentOutcome("))
        // the previewer's Forget fact / Forget photo (ProductionForget, moved here from MemoryDetailRoute by the preview migration) run at the Forget scope; Forget mark is a single-object undo through the same entry, and the scope does not apply to it
        assertTrue("the previewer Forget is scoped", files.single { it.name == "ProductionForget.kt" }.readText().contains("forgetScopedOutcome("))
    }
}
