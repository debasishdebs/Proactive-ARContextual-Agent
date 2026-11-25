package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.Executors
import com.rokid.context.canvas.NodeExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP6: `CanvasBackendImpl.create` gains `helperJobs` and `extraExecutors`; without them it behaves as before (WP8 composes, WP6 only opens the seam). */
class CanvasBackendCreateDefaultsTest {
    @Test fun noExtraExecutorsLeavesTheProductionSetUntouched() {
        // v2.2: the three model-node executors ride along (their node types resolve only while Developer mode and canvasModelNodes are ON, so nothing can reach them by default)
        val model = com.rokid.context.canvas.ModelNodeExecutors.all()
        assertEquals(Executors.all().keys + model.keys, CanvasBackendImpl.composeExecutors(emptyMap()).keys)
        assertEquals(Executors.all() + model, CanvasBackendImpl.composeExecutors(emptyMap()))
        assertTrue(model.keys.none { it in Executors.all() }) // the live interpreter's map never has them
    }

    @Test fun anExtraExecutorIsAddedAndNeverReplacesABuiltIn() {
        val extra = Executors.all().values.first()
        val composed = CanvasBackendImpl.composeExecutors(mapOf("py.fn" to extra))
        assertSame(extra, composed["py.fn"])
        assertTrue(composed.keys.containsAll(Executors.all().keys))
        val clash = Executors.all().keys.first()
        val bad = runCatching { CanvasBackendImpl.composeExecutors(mapOf(clash to extra)) }
        assertTrue("an extra executor may not replace a built-in node", bad.isFailure)
    }

    @Test fun theCreateSignatureKeepsItsOldParametersAndDefaultsTheNewOnes() {
        val src = java.io.File("src/main/java/com/rokid/context/phone/canvas/api/CanvasBackendImpl.kt").readText()
        assertTrue(src.contains("helperJobs: com.rokid.context.canvas.helper.HelperJobPort? = null"))
        assertTrue(src.contains("extraExecutors: Map<String, NodeExecutor> = emptyMap()"))
    }
}
