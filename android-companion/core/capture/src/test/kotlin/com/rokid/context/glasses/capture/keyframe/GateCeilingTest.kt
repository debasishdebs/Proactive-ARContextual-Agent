package com.rokid.context.glasses.capture.keyframe

import com.rokid.context.core.model.MotionState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The gate reports its own hard-ceiling refusals (2 x cap keeps in the rolling minute), whatever happened to the keeps afterwards. */
class GateCeilingTest {
    private class MemStore : GateStore {
        val ring = ArrayDeque<Pair<Long, Long>>(); var kept = 0L
        override fun lastKeyframes() = ring.toList()
        override fun push(d: Long, p: Long) { ring.addLast(d to p); while (ring.size > 5) ring.removeFirst() }
        override fun lastKeptAt() = kept
        override fun setLastKeptAt(t: Long) { kept = t }
    }

    private var now = 10_000L
    private val gate = KeyframeGate(MemStore()) { now }
    private val hits = ArrayList<Long>().also { gate.setCeilingListener { t -> it += t } }
    private var seq = 0L
    private fun cand(scene: Int): Candidate {
        val r = Random(scene)
        return Candidate(seq++, Thumb(160, 120, ByteArray(160 * 120) { (128 + r.nextInt(256) - 128).toByte() }), Nv21Frame(640, 480, 90, ByteArray(4)))
    }
    private fun forcedKeep(scene: Int) { gate.offer(cand(scene), MotionState.STILL, dwellArmed = false, force = true, capPerMin = 1, hardCap = true); now += 600 }

    @Test fun aGlanceCappedAtTwiceTheCapIsACeilingHitEvenIfTheKeepsNeverEncoded() {
        forcedKeep(1); forcedKeep(2)                       // two gate keeps (the caller may have failed to encode both)
        assertEquals(2, gate.keptInMinute(now))
        now += 1_000
        val r = gate.offer(cand(3), MotionState.STILL, dwellArmed = true, capPerMin = 1, hardCap = true)
        assertEquals(KeyframeGate.CAPPED, (r as GateResult.Skip).reason)
        assertEquals(listOf(now), hits)
    }

    @Test fun oneKeepAgainstCapOneIsTheCapNotTheCeiling() {
        forcedKeep(1)
        now += 1_000
        val r = gate.offer(cand(3), MotionState.STILL, dwellArmed = true, capPerMin = 1, hardCap = true)
        assertEquals(KeyframeGate.CAPPED, (r as GateResult.Skip).reason)
        assertTrue(hits.isEmpty(), "$hits")
    }

    @Test fun aWindowFlushRefusedByTheCeilingIsReported() {
        forcedKeep(1); forcedKeep(2)
        now += 1_000
        assertEquals(KeyframeGate.WINDOW, (gate.offer(cand(4), MotionState.HEAD_MOVING, dwellArmed = false, capPerMin = 1) as GateResult.Skip).reason)
        now += 1_000
        val r = gate.flushWindow(capPerMin = 1, hardCap = false)
        assertEquals(KeyframeGate.CAPPED, (r as GateResult.Skip).reason)
        assertEquals(1, hits.size)
    }

    @Test fun aWindowFlushOverTheCapButBelowTheCeilingIsNotReported() {
        forcedKeep(1)
        now += 1_000
        gate.offer(cand(4), MotionState.HEAD_MOVING, dwellArmed = false, capPerMin = 1)
        now += 1_000
        assertEquals(KeyframeGate.CAPPED, (gate.flushWindow(capPerMin = 1) as GateResult.Skip).reason)
        assertTrue(hits.isEmpty())
    }
}
