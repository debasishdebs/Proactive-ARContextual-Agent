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

    // ---- Codex v2 r1 P1-1: the real fences as one pass ----

    private fun ask() = com.rokid.context.memory.place.ZoneFence()

    @Test fun anOpenPassRunsTheDeliveryOnceUnderTheFences() {
        val place = com.rokid.context.memory.place.ZoneFence(); val a = ask()
        val pass = fencePass(place, { a.ticket() }, { false })
        var n = 0
        assertEquals("ran", pass.act { n++; "ran" }); assertEquals(1, n)
    }

    @Test fun aZoneHoldAfterTheTicketClosesThePassEvenAfterItWasReleased() {
        val place = com.rokid.context.memory.place.ZoneFence(); val a = ask()
        val pass = fencePass(place, { a.ticket() }, { false })
        place.hold(); place.release()
        var n = 0
        assertEquals(null, pass.act { n++; "ran" }); assertEquals("nothing was delivered", 0, n)
    }

    @Test fun asksOwnFenceClosesThePassToo() {
        val place = com.rokid.context.memory.place.ZoneFence(); val a = ask()
        val pass = fencePass(place, { a.ticket() }, { false })
        a.hold()
        assertEquals(null, pass.act { "ran" })
        // a held fence at pass time is closed from the start; the switch off (no ticket) falls back to the blocked() read
        assertEquals(null, fencePass(place, { null }, { true }).act { "ran" })
        assertEquals("ran", fencePass(com.rokid.context.memory.place.ZoneFence(), { null }, { false }).act { "ran" })
    }

    @Test fun aHoldThatArrivesDuringTheDeliveryWaitsForIt() {
        val place = com.rokid.context.memory.place.ZoneFence()
        val inside = java.util.concurrent.CountDownLatch(1); val go = java.util.concurrent.CountDownLatch(1)
        val pass = fencePass(place, { null }, { false })
        val t = Thread { pass.act { inside.countDown(); go.await(5, java.util.concurrent.TimeUnit.SECONDS); "ran" } }.also { it.start() }
        assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val holder = Thread { place.hold() }.also { it.start() }
        Thread.sleep(150)
        assertTrue("hold() waits for the dispatch that already passed the check", holder.isAlive)
        go.countDown(); t.join(5_000); holder.join(5_000)
        assertTrue(place.held)
    }

    @Test fun theAdapterUsesItsFactoryAndOtherwiseTheSinksDefault() {
        val g = PcaNexusGates({ true }, { true }, { false }, { false }, { true })
        assertEquals("the default re-reads the flags: the held zone refuses", null, g.pass().act { "ran" })
        val h = PcaNexusGates({ true }, { true }, { false }, { false }, { true }, passFactory = { fencePass(com.rokid.context.memory.place.ZoneFence(), { null }, { false }) })
        assertEquals("ran", h.pass().act { "ran" })
    }
}
