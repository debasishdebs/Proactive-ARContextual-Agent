package com.rokid.context.work.frames

import com.rokid.context.orchestration.SavedMemory
import org.junit.Assert.assertEquals
import org.junit.Test

class NudgeOffererTest {
    private val offers = mutableListOf<Triple<String, Boolean, Long?>>()
    private val o = NudgeOfferer { id, e, t -> offers += Triple(id, e, t) }

    @Test fun dwellOrNewPlaceIsEligible() {
        val frames = listOf(KeyframeCandidate("a", dwell = true, ts = 10), KeyframeCandidate("b", dwell = false, ts = 20))
        o.onExtracted(frames, listOf(SavedMemory("m1", listOf("a"), 0), SavedMemory("m2", listOf("b"), 0), SavedMemory("c", listOf("b"), null)), emptyMap())
        assertEquals(listOf(Triple("m1", true, 10L), Triple("m2", false, 20L)), offers) // m2: no dwell, no place; captions skipped
    }

    @Test fun newPlaceOnlyOnce() {
        val f = listOf(KeyframeCandidate("b", false, 5))
        o.onExtracted(f, listOf(SavedMemory("m1", listOf("b"), 0)), mapOf("b" to "Cafe"))
        o.onExtracted(f, listOf(SavedMemory("m2", listOf("b"), 0)), mapOf("b" to "Cafe"))
        assertEquals(listOf(true, false), offers.map { it.second })
    }
}
