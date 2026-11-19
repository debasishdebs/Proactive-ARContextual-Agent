package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.PlanCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Multilingual Ask is a runtime developer flag, not a plan change: the default capture plan hash, the built-in Query plan and the Default Query pipeline are exactly what they were. */
class MultilingualFlagUnchangedTest {
    @Test fun theDefaultPlanHashAndTheBuiltInQueryPlanAreUnchanged() {
        assertEquals("f5782e0d8610", PlanCompiler.compile(DefaultPipelines.defaultCapture()).plan!!.configHash)
        assertNull(FlowPlan.BUILT_IN_QUERY.condense)
        assertEquals(listOf("utterance", "embed", "retrieve", "answer", "deliver"), DefaultPipelines.defaultQuery().nodes.map { it.id })
        assertEquals(FlowPlan.BUILT_IN_QUERY, (FlowPlan.compile(DefaultPipelines.defaultQuery()) as FlowPlan.Compiled.Ok).plan)
    }
}
