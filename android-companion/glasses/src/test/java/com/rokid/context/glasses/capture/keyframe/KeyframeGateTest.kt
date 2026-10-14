package com.rokid.context.glasses.capture.keyframe

import com.rokid.context.core.model.MotionState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyframeGateTest {
    private class MemStore : GateStore {
        val ring = ArrayDeque<Pair<Long, Long>>()
        var kept = 0L
        override fun lastKeyframes() = ring.toList()
        override fun push(d: Long, p: Long) { ring.addLast(d to p); while (ring.size > 5) ring.removeFirst() }
        override fun lastKeptAt() = kept
        override fun setLastKeptAt(t: Long) { kept = t }
    }

    private var now = 10_000L
    private val store = MemStore()
    private val gate = KeyframeGate(store) { now }
    private var seq = 0L

    /** Distinct noise scene per [scene]; [soft] divides the contrast (1 = sharp, 8 = softer but above the floor, 100 = below it). */
    private fun thumb(scene: Int, soft: Int = 1): Thumb {
        val r = Random(scene)
        val luma = ByteArray(160 * 120) { (128 + (r.nextInt(256) - 128) / soft).toByte() }
        return Thumb(160, 120, luma)
    }

    private fun cand(scene: Int, soft: Int = 1) =
        Candidate(seq++, thumb(scene, soft), Nv21Frame(640, 480, 90, ByteArray(16) { scene.toByte() }))

    private fun at(t: Long, c: Candidate, m: MotionState = MotionState.STILL, dwell: Boolean = false): GateResult {
        now = t; return gate.offer(c, m, dwell)
    }

    private fun reason(r: GateResult) = assertIs<GateResult.Skip>(r).reason

    @Test fun abaPingPongSuppressed() {
        assertIs<GateResult.Keep>(at(10_000, cand(1)))
        assertIs<GateResult.Keep>(at(12_000, cand(2)))
        assertEquals("DUPLICATE", reason(at(14_000, cand(1))))
    }

    @Test fun throttle1200ms() {
        assertIs<GateResult.Keep>(at(10_000, cand(1)))
        // not a new scene: 1.2 s minimum gap
        assertEquals("THROTTLED", reason(at(11_199, cand(1))))
        assertEquals("DUPLICATE", reason(at(11_200, cand(1))))
    }

    @Test fun newSceneMayFollowAfter500ms() {
        assertIs<GateResult.Keep>(at(10_000, cand(1)))
        assertEquals("THROTTLED", reason(at(10_499, cand(2))))
        assertIs<GateResult.Keep>(at(10_500, cand(2)))
        assertIs<GateResult.Keep>(at(11_000, cand(3)))
    }

    @Test fun sharpestOfWindowWins() {
        val soft = cand(5, soft = 8)
        val sharp = cand(5)
        val softer = cand(5, soft = 4)
        assertEquals("WINDOW", reason(at(10_000, soft, MotionState.WALKING)))
        assertEquals("WINDOW", reason(at(10_400, sharp, MotionState.WALKING)))
        assertEquals("WINDOW", reason(at(10_800, softer, MotionState.WALKING)))
        val keep = assertIs<GateResult.Keep>(assertNotNull(gate.flushWindow()))
        assertSame(sharp, keep.candidate)
        assertTrue(!keep.dwell)
    }

    @Test fun windowClosesByItself() {
        val first = cand(7)
        assertEquals("WINDOW", reason(at(10_000, first, MotionState.HEAD_MOVING)))
        val r = at(11_500, cand(7, soft = 8), MotionState.HEAD_MOVING)
        assertSame(first, assertIs<GateResult.Keep>(r).candidate)
    }

    @Test fun dwellBypassesLastFive() {
        at(10_000, cand(1)); at(12_000, cand(2)); at(14_000, cand(3))
        // scene 1 is in the ring but not the last keyframe: a dwell on it counts again
        val r = at(16_000, cand(1), MotionState.STILL, dwell = true)
        assertTrue(assertIs<GateResult.Keep>(r).dwell)
        // ...but not against the last keyframe itself
        assertEquals("DUPLICATE", reason(at(18_000, cand(1), MotionState.STILL, dwell = true)))
    }

    @Test fun blurryDwellStillSkipped() {
        at(10_000, cand(1))
        assertEquals("BLUR", reason(at(12_000, cand(2, soft = 100), MotionState.STILL, dwell = true)))
        assertEquals(1, store.ring.size)
    }

    @Test fun keepCarriesWinningCandidatePixels() {
        val loser = cand(9, soft = 8)
        val winner = cand(9)
        at(10_000, loser, MotionState.WALKING)
        at(10_300, winner, MotionState.WALKING)
        val keep = assertIs<GateResult.Keep>(assertNotNull(gate.flushWindow()))
        assertEquals(winner.seq, keep.candidate.seq)
        assertSame(winner.pixels.data, keep.candidate.pixels.data)
        assertSame(winner.thumb, keep.candidate.thumb)
        assertEquals(ImageHashes.dHash(winner.thumb), keep.dhash)
        assertEquals(ImageHashes.pHash(winner.thumb), keep.phash)
        assertEquals(keep.dhash to keep.phash, store.ring.last())
        assertEquals(now, store.kept)
    }

    @Test fun losersReleased() {
        for (i in 0 until 6) at(10_000L + i * 200, cand(4, soft = 1 + i), MotionState.WALKING)
        assertTrue(gate.retainedCount() <= 3)
        gate.flushWindow()
        assertEquals(0, gate.retainedCount())
    }

    @Test fun blurryMovingSamplesAreDroppedImmediately() {
        assertEquals("BLUR", reason(at(10_000, cand(1, soft = 100), MotionState.WALKING)))
        assertEquals(0, gate.retainedCount())
        assertNull(gate.flushWindow())
    }

    @Test fun stillSampleClosesPendingWindowAsDwell() {
        val moving = cand(6, soft = 8)
        at(10_000, moving, MotionState.WALKING)
        val still = cand(6)
        val r = assertIs<GateResult.Keep>(at(10_600, still, MotionState.STILL, dwell = true))
        assertSame(still, r.candidate)
        assertTrue(r.dwell)
        assertEquals(0, gate.retainedCount())
    }

    @Test fun forcedOfferBypassesChangeTestAndThrottleButNotBlur() {
        assertIs<GateResult.Keep>(at(10_000, cand(1)))
        assertEquals("DUPLICATE", reason(at(14_000, cand(1))))
        now = 14_100
        assertIs<GateResult.Keep>(gate.offer(cand(1), MotionState.STILL, false, force = true)) // a duplicate, forced
        now = 14_200 // inside the 0.5 s gap of that keep
        assertIs<GateResult.Keep>(gate.offer(cand(1), MotionState.WALKING, false, force = true)) // forced while moving too
        now = 20_000
        assertEquals("BLUR", reason(gate.offer(cand(2, soft = 100), MotionState.STILL, false, force = true)))
    }
}
