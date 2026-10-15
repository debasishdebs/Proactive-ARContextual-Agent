package com.rokid.context.work.frames

import com.rokid.context.orchestration.SavedMemory
import org.junit.Assert.assertEquals
import org.junit.Test

class NudgeOffererTest {
    private val offers = java.util.Collections.synchronizedList(mutableListOf<Triple<String, Boolean, Long?>>())
    private val o = NudgeOfferer { id, e, t -> offers += Triple(id, e, t) }

    @Test fun dwellOrNewPlaceIsEligible() {
        val frames = listOf(KeyframeCandidate("a", dwell = true, ts = 10), KeyframeCandidate("b", dwell = false, ts = 20))
        o.onExtracted(frames, listOf(SavedMemory("m1", listOf("a"), 0), SavedMemory("m2", listOf("b"), 0), SavedMemory("c", listOf("b"), null)), emptyMap())
        assertEquals(listOf(Triple("m1", true, 10L), Triple("m2", false, 20L)), offers) // m2: no dwell, no place; captions skipped
    }

    @Test fun newPlaceOnlyOnce() {
        val f = listOf(KeyframeCandidate("b", false, 5))
        o.onExtracted(f, listOf(SavedMemory("m1", listOf("b"), 0)), mapOf("b" to "Cafe"))
        o.onExtracted(f, listOf(SavedMemory("m2", listOf("b"), 0)), mapOf("b" to "Office"))
        o.onExtracted(f, listOf(SavedMemory("m3", listOf("b"), 0)), mapOf("b" to "Office"))
        // the first place after a restart is unknown (the baseline), not a "new place"; a different one then is
        assertEquals(listOf(false, true, false), offers.map { it.second })
    }

    @Test fun threadSafeBaseline() {
        val f = listOf(KeyframeCandidate("b", false, 5))
        val threads = (1..8).map { t -> Thread { repeat(200) { i -> o.onExtracted(f, listOf(SavedMemory("m$t-$i", listOf("b"), 0)), mapOf("b" to "Cafe")) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals("one place only: never new", 0, offers.count { it.second })
        assertEquals(1600, offers.size)
    }
}
