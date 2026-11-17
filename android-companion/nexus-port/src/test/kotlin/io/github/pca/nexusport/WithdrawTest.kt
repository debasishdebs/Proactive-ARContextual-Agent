package io.github.pca.nexusport

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T4 (gate 4): every live handle is hidden, and speech stopped, at once on a trip and within one tick on a gate that changed silently. */
class WithdrawTest {
    private val t = FakeNexusTransport()
    private val g = FakeGates()
    private var now = 0L
    private var cfg = ON_TTS
    private fun sink() = NexusHudSink(t, g, { cfg }, FakeActions(), { now })

    private suspend fun fillAllSlots(s: NexusHudSink) {
        s.show(answer(speak = true))                                                // notice + speech
        s.show(HudEvent.CaptureState(HudEvent.CaptureState.State.CAPTURING))        // pin
        assertEquals(setOf(Slot.NOTICE, Slot.PIN, Slot.SPEECH), s.liveSlots())
    }

    @Test fun everyTripReasonHidesEveryHandleAndStopsTheVoice() = runBlocking<Unit> {
        for (reason in TripReason.entries) {
            val s = sink(); t.live.clear(); t.calls.clear()
            fillAllSlots(s)
            s.trip(reason)
            assertEquals(0, s.liveHandles(), reason.name)
            assertTrue(t.live.isEmpty(), "nothing may stay on the glasses after ${reason.name}: ${t.live}")
            assertTrue("stop" in t.calls && "hide:NOTICE" in t.calls && "hide:PIN" in t.calls, reason.name)
        }
    }

    @Test fun forgetDeleteAllAndModeOffOweAHistoryWipeOthersDoNot() = runBlocking<Unit> {
        for (reason in TripReason.entries) {
            val tt = FakeNexusTransport()
            val s = NexusHudSink(tt, g, { cfg }, FakeActions(), { now })
            s.trip(reason)
            val owes = reason in setOf(TripReason.FORGET, TripReason.DELETE_ALL, TripReason.MODE_OFF)
            assertEquals(if (owes) 1 else 0, tt.forgets, reason.name)
            assertEquals(owes, s.forgetOwed(), reason.name)
        }
    }

    @Test fun anOwedHistoryWipeIsResentUntilTheBridgeSaysItWasAccepted() = runBlocking<Unit> {
        val s = sink()
        s.trip(TripReason.FORGET)
        s.tick(); s.tick()
        assertEquals(3, t.forgets)
        s.events.onForgetAccepted(s.wipeGeneration())
        s.tick()
        assertEquals(3, t.forgets)
        assertTrue(!s.forgetOwed())
    }

    @Test fun modeOffAlsoSwitchesTheBridgeFeaturesOff() = runBlocking<Unit> {
        val s = sink(); fillAllSlots(s)
        assertEquals(NexusFeatures.TTS, t.features)
        cfg = NexusConfig()
        s.trip(TripReason.MODE_OFF)
        assertEquals(NexusFeatures.NONE, t.features)
    }

    @Test fun aGateThatChangedWithoutATripWithdrawsWithinOneTick() = runBlocking<Unit> {
        for (flip in listOf<(FakeGates) -> Unit>({ it.zone = true }, { it.ask = true }, { it.worn = false }, { it.throwOn = "zone" })) {
            val gg = FakeGates(); val tt = FakeNexusTransport()
            val s = NexusHudSink(tt, gg, { cfg }, FakeActions(), { now })
            s.show(answer(speak = true)); s.show(HudEvent.CaptureState(HudEvent.CaptureState.State.PAUSED))
            assertEquals(3, s.liveHandles())
            flip(gg)
            s.tick()
            assertEquals(0, s.liveHandles()); assertTrue(tt.live.isEmpty())
        }
    }

    @Test fun aPauseWithdrawsProactiveHandlesAndAnswersOnATick() = runBlocking<Unit> {
        val s = sink()
        s.show(nudge())
        g.paused = true
        s.tick()
        assertEquals(0, s.liveHandles())
        g.paused = false
        s.show(answer()); g.paused = true
        s.tick()
        assertEquals(emptySet(), s.liveSlots())      // P1-4: an answer (and its speech) is content too
    }

    @Test fun aSourceForgottenWhileShownIsWithdrawnOnTheNextTick() = runBlocking<Unit> {
        val s = sink()
        s.show(nudge(src = setOf("m1"))); s.show(answer(src = setOf("m2")))
        g.liveIds = setOf("m2")
        assertEquals(setOf(Slot.NOTICE), s.liveSlots())     // the answer replaced the nudge in the one notice slot
        s.tick()
        assertEquals(setOf(Slot.NOTICE), s.liveSlots())
        g.liveIds = emptySet()
        s.tick()
        assertEquals(0, s.liveHandles()); assertTrue(t.live.isEmpty())
        g.liveIds = null; g.throwOn = "sources"
        s.show(HudEvent.CaptureState(HudEvent.CaptureState.State.CAPTURING)); s.tick()   // content-free: no provenance read
        assertEquals(1, s.liveHandles())
    }

    @Test fun handlesExpireWithTheirTtlAndSpeechWithItsCeiling() = runBlocking<Unit> {
        val s = sink()
        s.show(HudEvent.AskAnswer("a", "x", emptyList(), setOf("m"), 10_000, speak = true))
        now = 9_999; s.tick(); assertEquals(2, s.liveHandles())
        now = 10_000; s.tick(); assertEquals(setOf(Slot.SPEECH), s.liveSlots())
        now = NexusLimits.SPEECH_MAX_MS; s.tick(); assertEquals(0, s.liveHandles())
    }

    @Test fun aHideThatFailsIsKeptAndRetriedEachTick() = runBlocking<Unit> {
        val s = sink(); fillAllSlots(s)
        t.hideResult = NexusResult.ERROR
        s.trip(TripReason.PAUSE)
        assertEquals(3, s.liveHandles())                    // the bridge was unreachable: still owed
        s.tick(); assertEquals(3, s.liveHandles())
        t.hideResult = NexusResult.OK
        s.tick()
        assertEquals(0, s.liveHandles()); assertTrue(t.live.isEmpty())
    }

    @Test fun aQuietHideRefusalIsFinal() = runBlocking<Unit> {
        val s = sink(); fillAllSlots(s)
        t.hideResult = NexusResult.NOT_AVAILABLE            // link down: nothing can be showing, nothing to retry
        s.trip(TripReason.ZONE_HOLD)
        assertEquals(0, s.liveHandles())
    }

    @Test fun aReconnectedBridgeStartsClean() = runBlocking<Unit> {
        val s = sink(); fillAllSlots(s)
        s.onBridgeConnected()
        assertEquals(0, s.liveHandles()); assertTrue(t.live.isEmpty())
    }

    /** The same fuzz with the assist-button ask and the skills mixed in: a trip leaves no listening, no live ask, and a late answer of any earlier ask delivers nothing. */
    @Test fun fuzzWithAsksAndSkillsLeavesNothingBehindAndDeliversNothingAfterATrip() = runBlocking<Unit> {
        repeat(40) { seed ->
            val rnd = Random(1000 + seed)
            val tt = FakeNexusTransport().also { it.now = { now } }; val gg = FakeGates(); val aa = FakeAsks(); val sk = FakeSkills()
            now = 0
            val s = NexusHudSink(tt, gg, { ON_ASK.copy(ttsEnabled = true, skillsEnabled = true) }, FakeActions(), { now }, {}, aa, sk)
            repeat(300) {
                when (rnd.nextInt(13)) {
                    0 -> s.show(nudge(src = setOf("m${rnd.nextInt(3)}")))
                    1 -> s.show(answer(speak = rnd.nextBoolean()))
                    2 -> s.events.onListening(true)
                    3 -> s.events.onAskTranscript(if (rnd.nextInt(5) == 0) " " else "q${rnd.nextInt(9)}")
                    4 -> s.events.onAskFailed(1 + rnd.nextInt(3))
                    5 -> s.events.onListening(false)
                    6 -> aa.asked.lastOrNull()?.let { s.show(HudEvent.AskAnswer(it.first, "a", emptyList(), setOf("m0"), 5_000, speak = rnd.nextBoolean())) }
                    7 -> { val refusedBefore = aa.refused.size; s.trip(TripReason.entries[rnd.nextInt(TripReason.entries.size)])
                        assertEquals(0, s.liveHandles(), "seed $seed"); assertTrue(tt.live.isEmpty(), "seed $seed")
                        val shownBefore = tt.shown.size; val spokenBefore = tt.spoken.size
                        // asks still open when the trip hit (the handler was told `refused`) can never be answered afterwards; an ask that was already answered is the phone's own business
                        for (id in aa.refused.drop(refusedBefore).map { it.first }.distinct()) assertEquals(Outcome.Dropped(DropReason.TRIPPED), s.show(HudEvent.AskAnswer(id, "late", emptyList(), setOf("m0"), 5_000, speak = true)), "seed $seed")
                        assertEquals(shownBefore, tt.shown.size, "seed $seed"); assertEquals(spokenBefore, tt.spoken.size, "seed $seed") }
                    8 -> gg.zone = rnd.nextBoolean()
                    9 -> gg.worn = rnd.nextInt(4) != 0
                    10 -> { now += rnd.nextLong(0, 40_000); tt.expire(now); s.tick() }
                    11 -> s.events.invokeSkill(SkillOp.entries[rnd.nextInt(4)].id)
                    12 -> { gg.paused = rnd.nextBoolean(); s.refreshFeatures() }
                }
                assertTrue(s.liveSlots().containsAll(tt.live), "seed $seed: orphan ${tt.live - s.liveSlots()}")
            }
            s.trip(TripReason.DELETE_ALL)
            assertEquals(0, s.liveHandles(), "seed $seed"); assertTrue(tt.live.isEmpty(), "seed $seed")
        }
    }

    /** Interleaved shows and trips, seeded: after every trip, and at the end, zero live handles in the sink AND on the fake glasses. */
    @Test fun fuzzInterleavedShowsAndTripsLeaveNoLiveHandle() = runBlocking<Unit> {
        repeat(40) { seed ->
            val rnd = Random(seed)
            val tt = FakeNexusTransport().also { it.now = { now } }; val gg = FakeGates()
            now = 0
            val s = NexusHudSink(tt, gg, { cfg }, FakeActions(), { now })
            repeat(300) {
                when (rnd.nextInt(10)) {
                    0 -> s.show(nudge(src = setOf("m${rnd.nextInt(3)}")))
                    1 -> s.show(answer(speak = rnd.nextBoolean()))
                    2 -> s.show(HudEvent.CaptureState(HudEvent.CaptureState.State.entries[rnd.nextInt(3)]))
                    3 -> s.show(HudEvent.MarkConfirm("m", "pause_zone", emptySet(), 5_000))
                    4 -> { s.trip(TripReason.entries[rnd.nextInt(TripReason.entries.size)]); assertEquals(0, s.liveHandles(), "seed $seed"); assertTrue(tt.live.isEmpty(), "seed $seed: ${tt.live}") }
                    5 -> { gg.zone = rnd.nextBoolean() }
                    6 -> { gg.paused = rnd.nextBoolean() }
                    7 -> { gg.worn = rnd.nextInt(4) != 0 }
                    8 -> { now += rnd.nextLong(0, 20_000); tt.expire(now); s.tick() }
                    9 -> { gg.liveIds = if (rnd.nextBoolean()) null else setOf("m0", "m1"); s.tick() }
                }
                // invariant: anything the glasses show is a handle the sink knows (nothing is orphaned)
                assertTrue(s.liveSlots().containsAll(tt.live), "seed $seed: orphan ${tt.live - s.liveSlots()}")
            }
            s.trip(TripReason.DELETE_ALL)
            assertEquals(0, s.liveHandles(), "seed $seed"); assertTrue(tt.live.isEmpty(), "seed $seed")
        }
    }
}
