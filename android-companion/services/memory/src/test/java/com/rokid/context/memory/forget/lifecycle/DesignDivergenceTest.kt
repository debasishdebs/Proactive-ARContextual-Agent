package com.rokid.context.memory.forget.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DESIGN_DIVERGENCE list is complete (the six the M0-M2 report found), described, and every entry names a golden test that exists. */
class DesignDivergenceTest {
    @Test fun eightDivergencesEachPinnedByAGolden() {
        assertEquals(8, DesignDivergence.ALL.size)
        assertEquals(DesignDivergence.ALL.size, DesignDivergence.ALL.map { it.id }.toSet().size)
        val golden = Class.forName("com.rokid.context.memory.forget.GoldenDeleteSetTest").methods.map { it.name }.toSet()
        for (e in DesignDivergence.ALL) {
            assertTrue("${e.id} is described", e.design.isNotBlank() && e.v1.isNotBlank())
            assertTrue("${e.id} names a golden that exists: ${e.golden}", e.golden in golden)
        }
    }
}
