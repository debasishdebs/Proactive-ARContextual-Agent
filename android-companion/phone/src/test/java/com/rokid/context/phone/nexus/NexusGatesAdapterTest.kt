package com.rokid.context.phone.nexus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.0 T-provider: the adapter over PCA's existing fences answers "blocked" whenever a gate throws (fail closed). */
class NexusGatesAdapterTest {
    private fun boom(): Nothing = throw IllegalStateException("gate failed")

    private fun open() = PcaNexusGates(sourcesLive = { true }, zoneFence = { false }, askFence = { false }, paused = { false }, worn = { true })

    @Test fun openGatesPassThrough() = runTest {
        val g = open()
        assertTrue(g.sourcesLive(setOf("m1"))); assertFalse(g.zoneFenceBlocked()); assertFalse(g.askFenceBlocked()); assertFalse(g.paused()); assertTrue(g.worn())
    }

    @Test fun closedGatesPassThrough() = runTest {
        val g = PcaNexusGates(sourcesLive = { false }, zoneFence = { true }, askFence = { true }, paused = { true }, worn = { false })
        assertFalse(g.sourcesLive(setOf("m1"))); assertTrue(g.zoneFenceBlocked()); assertTrue(g.askFenceBlocked()); assertTrue(g.paused()); assertFalse(g.worn())
    }

    @Test fun aThrowingGateIsBlockedNeverOpen() = runTest {
        val g = PcaNexusGates(sourcesLive = { boom() }, zoneFence = { boom() }, askFence = { boom() }, paused = { boom() }, worn = { boom() })
        assertFalse("provenance error = not live", g.sourcesLive(setOf("m1")))
        assertTrue("zone fence error = blocked", g.zoneFenceBlocked())
        assertTrue("ask fence error = blocked", g.askFenceBlocked())
        assertTrue("pause state error = paused", g.paused())
        assertFalse("wear error = not worn", g.worn())
    }

    @Test fun cancellationIsNotSwallowed() = runTest {
        val g = PcaNexusGates(sourcesLive = { throw CancellationException("cancelled") }, zoneFence = { false }, askFence = { false }, paused = { false }, worn = { true })
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { g.sourcesLive(setOf("m1")) } }
        assertEquals(true, g.worn())
    }
}
