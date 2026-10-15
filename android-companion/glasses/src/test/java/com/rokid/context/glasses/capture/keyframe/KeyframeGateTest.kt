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

    private fun at(t: Long, c: Candidate, m: MotionState = MotionState.STILL, dwell: Boolean = false, force: Boolean = false): GateResult {
        now = t; return gate.offer(c, m, dwell, force)
    }

    private fun reason(r: GateResult) = assertIs<GateResult.Skip>(r).reason

    @Test fun abaPingPongSuppressed() {
        assertIs<GateResult.Keep>(at(10_000, cand(1)))
        assertIs<GateResult.Keep>(at(13_000, cand(2)))
        assertEquals("DUPLICATE", reason(at(16_000, cand(1))))
    }

    @Test fun throttle1200ms() {
        assertIs<GateResult.Keep>(at(10_000, cand(1)))
        // not a new scene: skipped inside 1.2 s as THROTTLED, and never kept
        assertEquals("THROTTLED", reason(at(11_199, cand(1))))
        assertEquals(1, store.ring.size)
        assertEquals(10_000L, store.kept)
        // the same frame after 1.2 s is a DUPLICATE (the ring, not the clock, rejects it)
        assertEquals("DUPLICATE", reason(at(11_200, cand(1))))
        assertEquals(1, store.ring.size)
        // a NEW scene is kept once the 3 s non-dwell cap has passed (K1; it was 0.5 s before)
        assertIs<GateResult.Keep>(at(13_000, cand(2)))
        assertIs<GateResult.Keep>(at(16_000, cand(3)))
        assertEquals(3, store.ring.size)
    }

    @Test fun newDwellSceneMayFollowAfter500ms() {
        assertIs<GateResult.Keep>(at(10_000, cand(1)))
        assertEquals("THROTTLED", reason(at(10_499, cand(2), dwell = true)))
        assertIs<GateResult.Keep>(at(10_500, cand(2), dwell = true))
        assertIs<GateResult.Keep>(at(11_000, cand(3), dwell = true))
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

    @Test fun stillSampleClosesPendingWindowFirstWithoutDwell() {
        val moving = cand(6, soft = 8)
        at(10_000, moving, MotionState.WALKING)
        val still = cand(6)
        val r = assertIs<GateResult.Keep>(at(10_600, still, MotionState.STILL, dwell = true))
        assertSame(moving, r.candidate)
        assertEquals(false, r.dwell)
        assertEquals(0, gate.retainedCount())
    }

    @Test fun sharpMidTurnFrameDoesNotStealTheDwellFlag() {
        at(3_000, cand(1)) // far enough back that the K1 6 s moving cap is open at 11.8 s
        val midTurn = cand(2) // sharp, but a duplicate-free new scene seen only while moving
        assertEquals("WINDOW", reason(at(11_300, midTurn, MotionState.HEAD_MOVING)))
        val settled = cand(3, soft = 8) // softer, but it is what the user settled on
        // the window closes on its own terms (its winner is not a dwell); throttle keeps the settled frame out here
        val closed = assertIs<GateResult.Keep>(at(11_800, settled, MotionState.STILL, dwell = true))
        assertSame(midTurn, closed.candidate)
        assertEquals(false, closed.dwell)
        // the settled view is judged on its own at the next still sample and carries the dwell flag
        val next = cand(3, soft = 8)
        val r = assertIs<GateResult.Keep>(at(13_000, next, MotionState.STILL, dwell = true))
        assertSame(next, r.candidate)
        assertTrue(r.dwell)
    }

    @Test fun stillSampleIsJudgedOnItsOwnWhenTheWindowKeepsNothing() {
        at(10_000, cand(1))
        assertEquals("WINDOW", reason(at(12_000, cand(1), MotionState.WALKING))) // same scene as the last keyframe
        val still = cand(2)
        val r = assertIs<GateResult.Keep>(at(12_600, still, MotionState.STILL, dwell = true))
        assertSame(still, r.candidate)
        assertTrue(r.dwell)
    }

    @Test fun forcedKeepIsRecordedAndSuppressesTheSameScene() {
        at(10_000, cand(1))
        val forced = cand(2)
        // forced: kept inside the 0.5 s throttle, never a dwell
        val r = assertIs<GateResult.Keep>(at(10_100, forced, MotionState.WALKING, force = true))
        assertSame(forced, r.candidate)
        assertEquals(false, r.dwell)
        assertEquals(r.dhash to r.phash, store.ring.last())
        assertEquals(10_100L, store.kept)
        assertEquals(0, gate.retainedCount())
        assertEquals("THROTTLED", reason(at(10_300, cand(2))))
        assertEquals("DUPLICATE", reason(at(12_000, cand(2))))
    }

    @Test fun forcedKeepStillNeedsTheBlurFloor() {
        assertEquals("BLUR", reason(at(10_000, cand(1, soft = 100), force = true)))
        assertEquals(0, store.ring.size)
    }

    @Test fun recordKeepUpdatesRingAndLastKept() {
        val c = cand(1)
        gate.recordKeep(c, 0x1234L, 0x5678L, 10_050L)
        assertEquals(listOf(0x1234L to 0x5678L), store.ring.toList())
        assertEquals(10_050L, store.kept)
    }

    @Test fun concurrentOffersAndFlushesStayConsistent() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val keeps = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val cands = (0 until 200).map { cand(it % 7 + 20, soft = 1 + it % 3) }
        val jobs = (0 until 4).map { t ->
            pool.submit {
                for (i in t until cands.size step 4) {
                    val r = if (i % 5 == 0) gate.flushWindow() else gate.offer(cands[i], MotionState.WALKING, false)
                    if (r is GateResult.Keep) keeps += r.candidate.seq
                }
            }
        }
        jobs.forEach { it.get() }
        pool.shutdown()
        gate.flushWindow()?.let { if (it is GateResult.Keep) keeps += it.candidate.seq }
        assertEquals(keeps.size, keeps.toSet().size, "a candidate was emitted twice")
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

    // ---- fix wave K1/K2: non-dwell rate cap and sample-time motion ----

    @Test fun nonDwellKeepsAreCappedAtOnePer3s() {
        assertIs<GateResult.Keep>(at(10_000, cand(1)))
        assertEquals("THROTTLED", reason(at(12_999, cand(2))))
        assertIs<GateResult.Keep>(at(13_000, cand(2)))
    }

    @Test fun movingKeepsAreCappedAtOnePer6s() {
        assertIs<GateResult.Keep>(at(10_000, cand(1), MotionState.STILL))
        // a walking window closing 2 s, 4 s and 5.9 s later keeps nothing; the first one past 6 s does
        for (t in listOf(12_000L, 14_000L, 15_999L)) {
            at(t - 1_500, cand(2), MotionState.WALKING)
            assertEquals("THROTTLED", reason(at(t, cand(2), MotionState.WALKING)))
        }
        at(16_000 - 1_500, cand(3), MotionState.HEAD_MOVING)
        assertIs<GateResult.Keep>(at(16_000, cand(3), MotionState.HEAD_MOVING))
    }

    @Test fun dwellAndForcedAreExemptFromTheCap() {
        assertIs<GateResult.Keep>(at(10_000, cand(1), MotionState.WALKING, force = true))
        assertIs<GateResult.Keep>(at(10_600, cand(2), MotionState.STILL, dwell = true))
        assertIs<GateResult.Keep>(at(10_700, cand(3), MotionState.STILL, force = true))
    }

    @Test fun keepCarriesTheMotionOfTheWinningSample() {
        val first = cand(1, soft = 8); val sharp = cand(1)
        at(10_000, first, MotionState.WALKING)
        at(10_500, sharp, MotionState.HEAD_MOVING)
        val r = assertIs<GateResult.Keep>(at(11_600, cand(1, soft = 4), MotionState.STILL))
        assertSame(sharp, r.candidate)
        assertEquals(MotionState.HEAD_MOVING, r.motion)
    }
}
