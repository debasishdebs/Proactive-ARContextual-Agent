package com.rokid.context.ai.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic flow, Source -> Aggregate -> Process -> Condition -> Outcome (specs/2026-10-24-generic-flow-families.md): every registered type sits in exactly one family. */
class NodeFamiliesTest {
    @Test fun theFiveFamiliesInFlowOrder() {
        assertEquals(listOf("Source", "Aggregate", "Process", "Condition", "Outcome"), Family.entries.map { it.label })
    }

    @Test fun everyTypeIsMappedAsTheFamiliesTableSays() {
        val expected = mapOf(
            NodeTypes.INPUT_PHOTOS to Family.SOURCE, NodeTypes.INPUT_MOMENTS to Family.SOURCE, NodeTypes.INPUT_SCENES to Family.SOURCE,
            NodeTypes.IMU_HEAD_MOTION to Family.SOURCE, NodeTypes.IMU_SETTLE to Family.SOURCE, NodeTypes.HUB_CONTEXT to Family.SOURCE, NodeTypes.SIGNAL_IN to Family.SOURCE,
            NodeTypes.VISION_EXTRACT to Family.PROCESS, NodeTypes.PROMPT to Family.PROCESS, NodeTypes.SUMMARY to Family.PROCESS, NodeTypes.CRITIC to Family.PROCESS, NodeTypes.PY_FN to Family.PROCESS,
            NodeTypes.GATE_THRESHOLD to Family.CONDITION, NodeTypes.GATE_NOVELTY to Family.CONDITION, NodeTypes.EXPR_GUARD to Family.CONDITION,
            NodeTypes.LOCAL_CRITIC to Family.CONDITION, NodeTypes.SWITCH to Family.CONDITION, NodeTypes.GATE_RATE_CAP to Family.CONDITION,
            NodeTypes.CAPTURE_IMAGE to Family.OUTCOME, NodeTypes.POLICY_CAPTURE_RATE to Family.OUTCOME, NodeTypes.SIGNAL_OUT to Family.OUTCOME, NodeTypes.MEMORY_WRITE to Family.OUTCOME,
        )
        assertEquals("a new type needs a family here", expected.keys, NodeTypes.all.map { it.type }.toSet())
        expected.forEach { (t, f) -> assertEquals(t, f, NodeTypes.byType(t)!!.family) }
    }

    @Test fun noTypeIsAnAggregateYet() {
        assertTrue(NodeTypes.all.none { it.family == Family.AGGREGATE })
    }

    @Test fun categoriesAndLanesAreUntouched() {
        // the family regroups the palette only: the old category (still served) and the domain of each type stay as they were
        assertEquals(Category.CHECKS, NodeTypes.byType(NodeTypes.LOCAL_CRITIC)!!.category)
        assertEquals(Category.ACTIONS_CAPTURE, NodeTypes.byType(NodeTypes.CAPTURE_IMAGE)!!.category)
        assertEquals(Domain.RT_GLASSES, NodeTypes.byType(NodeTypes.CAPTURE_IMAGE)!!.domain)
        assertEquals(Domain.RT_PHONE, NodeTypes.byType(NodeTypes.SIGNAL_OUT)!!.domain)
    }
}
