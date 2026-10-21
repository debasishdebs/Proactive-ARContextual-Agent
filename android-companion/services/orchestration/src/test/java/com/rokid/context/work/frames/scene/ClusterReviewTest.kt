package com.rokid.context.work.frames.scene

import com.rokid.context.ai.contract.ClusterOutput
import com.rokid.context.ai.contract.ClusterResult
import com.rokid.context.ai.contract.Fact
import com.rokid.context.memory.db.ReviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cluster facts take wave 1's local critic verdict (no model critic per cluster). */
class ClusterReviewTest {
    private fun out(vararg conf: Double) = ClusterOutput(
        listOf(ClusterResult("c1", "s", conf.map { Fact("fact", "object", it, emptyList(), listOf("f1")) })), emptyList(),
    )

    @Test fun confidentFactsAreTrusted() {
        val r = ClusterReview.of(out(0.9, 0.8), "k")
        assertTrue(r.trusted); assertEquals(ReviewState.OK, r.factReview); assertEquals(0.85, r.confidence, 0.001)
    }

    @Test fun unsureFactsNeedReview() {
        val r = ClusterReview.of(out(0.5, 0.6), "k")
        assertFalse(r.trusted); assertEquals(ReviewState.NEEDS_REVIEW, r.factReview); assertEquals(0.55, r.confidence, 0.001)
    }

    @Test fun noFactsIsTrusted() {
        val r = ClusterReview.of(out(), "k")
        assertTrue(r.trusted); assertEquals(1.0, r.confidence, 0.001)
    }
}
