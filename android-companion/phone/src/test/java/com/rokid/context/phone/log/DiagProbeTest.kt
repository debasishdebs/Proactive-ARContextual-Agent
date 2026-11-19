package com.rokid.context.phone.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagProbeTest {
    @Test fun pruneKeepsUnfinishedWorkAndDropsFinishedOnesAboveTheCap() {
        val m = java.util.concurrent.ConcurrentHashMap<String, String>()
        repeat(10) { m["done$it"] = "SUCCEEDED/0" }
        repeat(3) { m["run$it"] = "RUNNING/1" }
        DiagProbe.pruneWorkStates(m, 5)
        assertTrue(m.size <= 5)
        assertTrue((0 until 3).all { m.containsKey("run$it") })
    }

    @Test fun pruneDoesNothingAtOrBelowTheCapAndNeverDropsUnfinished() {
        val m = HashMap<String, String>()
        repeat(4) { m["w$it"] = "ENQUEUED/0" }
        DiagProbe.pruneWorkStates(m, 4); assertEquals(4, m.size)
        DiagProbe.pruneWorkStates(m, 2); assertEquals(4, m.size)
    }
}
