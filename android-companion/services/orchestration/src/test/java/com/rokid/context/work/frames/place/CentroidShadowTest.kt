package com.rokid.context.work.frames.place

import com.rokid.context.memory.retrieval.MatchThresholds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CentroidShadowTest {
    private val lines = ArrayList<String>()
    private val cents = mapOf("user:a" to floatArrayOf(1f, 0f), "user:b" to floatArrayOf(0f, 1f))
    private fun shadow(on: Boolean) = CentroidShadow({ on }, { _, ids -> CentroidShadow.PinCentroids(cents.filterKeys { it in ids }) }, { lines += it })

    @Test fun offWritesNothing() = runBlocking { shadow(false).observe(floatArrayOf(1f, 0f), "nomic-test", listOf("user:a"), { true }); assertTrue(lines.isEmpty()) }

    @Test fun oneCountsOnlyLineWithNoIdOrScorePerPlace() = runBlocking {
        shadow(true).observe(floatArrayOf(0.9f, 0.1f), "nomic-v1", listOf("user:a", "user:b", "user:c"), { true })
        val l = lines.single()
        assertTrue(l, l.startsWith("place shadow3 v=1 thrV=${MatchThresholds.VERSION} space=nomic pins=3 withCentroid=2 noCentroid=1 ranked=2 "))
        assertTrue(l, l.contains("band=borderline")); assertFalse(l, l.contains("user:"))
        assertEquals(0, MatchThresholds.VERSION)
    }

    @Test fun aFencedAttemptLogsNothing() = runBlocking { shadow(true).observe(floatArrayOf(1f, 0f), "nomic-v1", listOf("user:a"), { false }); assertTrue(lines.isEmpty()) }
}
