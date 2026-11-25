package com.rokid.context.canvas.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E2eJoinTest {
    @Test fun keepMatchesNearestFrameWithinTolerance() {
        val r = E2eJoin.join(listOf(E2eJoin.Keep(10_000), E2eJoin.Keep(20_000)), listOf(E2eJoin.Stored("a", 10_900), E2eJoin.Stored("b", 20_000 + 1_501)))
        assertEquals(1, r.matched); assertEquals(1, r.notStored)
        assertEquals("a", r.keeps[0].frameId); assertEquals(E2eJoin.Status.NOT_STORED, r.keeps[1].status)
        assertEquals(1, r.unusedFrames)
    }

    @Test fun oneFrameServesOneKeep() {
        val r = E2eJoin.join(listOf(E2eJoin.Keep(1_000), E2eJoin.Keep(1_200)), listOf(E2eJoin.Stored("a", 1_100)))
        assertEquals(1, r.matched); assertEquals(1, r.notStored)
    }

    @Test fun counterfactualExtraKeepsAreNeverMatchedJustCounted() {
        val r = E2eJoin.join(emptyList(), listOf(E2eJoin.Stored("a", 1)), counterfactualExtraKeeps = 14)
        assertEquals(14, r.neverCaptured)
        assertTrue(E2eJoin.counterfactualNote(r)!!.contains("14 frames that were never captured"))
        assertNull(E2eJoin.counterfactualNote(E2eJoin.join(emptyList(), emptyList())))
    }

    @Test fun recordHasNoFrameIdAndIsBounded() {
        val keeps = (0 until 2000).map { E2eJoin.Keep(it * 1000L) }
        val frames = keeps.map { E2eJoin.Stored("SECRETFRAME${it.keptAtMs}", it.keptAtMs) }
        val r = E2eJoin.join(keeps, frames)
        val rec = E2eJoin.record(E2eJoin.Params("trace", 0, 3_000_000, "s", "even", "run1", listOf("rp1")), r, listOf("t2", "t1"), mapOf("still" to 4, "blur" to 1))
        assertEquals("E2E", rec.replayClass)
        assertFalse(rec.resultJson.contains("SECRETFRAME")); assertFalse(rec.paramsJson.contains("SECRETFRAME"))
        assertTrue(rec.resultJson.contains("\"keepsTruncated\":true"))
        assertEquals("[\"t1\",\"t2\"]", rec.sourcesJson)
        assertTrue(rec.resultJson.toByteArray().size <= E2eJoin.RECORD_CAP_BYTES)
        assertNotNull(rec.fromMs)
    }
}
