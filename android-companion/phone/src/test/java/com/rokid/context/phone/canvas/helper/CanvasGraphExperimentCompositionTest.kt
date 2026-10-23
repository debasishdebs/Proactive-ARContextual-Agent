package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.Executors
import com.rokid.context.canvas.ExperimentExecutors
import com.rokid.context.canvas.PythonExecutor
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.db.DeletionEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** WP8: the one composition line of the phone. `py.fn` reaches the runner only through `extraExecutors`; the live interpreter's map is untouched. */
class CanvasGraphExperimentCompositionTest {
    private val fence: suspend () -> HelperFence = { HelperFence(1, DeletionEpoch("i", 0, 0), 0) }

    @Test fun theRunnerMapHasPyFnAndEveryBuiltIn() {
        val composed = CanvasBackendImpl.composeExecutors(ExperimentExecutors.extras(null, fence))
        assertTrue(composed["py.fn"] is PythonExecutor)
        assertEquals(Executors.all().keys + "py.fn", composed.keys)
    }

    @Test fun theLiveInterpretersMapNeverHasIt() {
        assertFalse("py.fn" in Executors.all())
    }

    @Test fun theGraphPassesTheHelperServiceThroughTheExperimentExecutorsAndTheGatedPortToTheBackend() {
        val src = File("src/main/java/com/rokid/context/phone/canvas/CanvasGraph.kt").readText()
        assertTrue(src.contains("extraExecutors = com.rokid.context.canvas.ExperimentExecutors.forApp(app, helper.service)"))
        assertTrue("the runner's logger is the graph's gated port", src.contains("helperJobs = helper.service, extraExecutors = com.rokid.context.canvas.ExperimentExecutors.forApp(app, helper.service), log = port)"))
        val impl = File("src/main/java/com/rokid/context/phone/canvas/api/CanvasBackendImpl.kt").readText()
        assertTrue(impl.contains("ConfirmTokens(clock), hot, clock, log = log,"))
    }
}
