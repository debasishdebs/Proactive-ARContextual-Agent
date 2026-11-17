package io.github.pca.nexusbridge

import io.github.pca.nexusport.NexusAction
import io.github.pca.nexusport.NexusFeatures
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.Slot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Codex v2 r2 P1-2 (STT can never open after a completed trip; the authorization call is bounded) and the bridge half of P1-1 (a late show after a trip is dropped) and P1-3 (the wipe acknowledgement carries its generation). */
class TripRaceBridgeTest {
    private class RaceBus : NexusBusClient {
        @Volatile var open = false
        @Volatile var onStart: () -> Unit = {}
        @Volatile var notice: String? = null
        @Volatile var onShow: () -> Unit = {}
        @Volatile var accept: (() -> Unit)? = null
        override fun showNotice(title: String, body: String, ttlMs: Long, wake: Boolean, actions: List<NexusAction>): Int { onShow(); notice = body; return NexusResult.OK }
        override fun hideNotice(): Int { notice = null; return NexusResult.OK }
        override fun showPin(title: String, lines: List<String>, ttlMs: Long) = NexusResult.OK
        override fun hidePin() = NexusResult.OK
        override fun speak(text: String) = NexusResult.OK
        override fun stopSpeech() = NexusResult.OK
        override fun forgetNotices(onAccepted: () -> Unit): Int { accept = onAccepted; return NexusResult.OK }
        override fun startListening(cb: ListenCallbacks): Int { onStart(); open = true; return NexusResult.OK }
        override fun stopListening(): Int { open = false; return NexusResult.OK }
    }

    private val ASK = NexusFeatures.STT or NexusFeatures.ASSIST
    private val ev = object : BridgeEvents {
        override fun onAction(slot: Int, actionId: String) = Unit
        override fun onSpeechDone() = Unit
        override fun onForgetAccepted() = Unit
    }

    @Test fun aTripWhileTheSessionIsBeingOpenedWaitsForTheOpenAndThenClosesIt() {
        val bus = RaceBus(); val r = BridgeRenderer({ bus }, {}, { true }).also { it.events = ev; it.setFeatures(ASK) }
        val tripDone = java.util.concurrent.atomic.AtomicBoolean(false)
        var blockedDuringOpen = false
        var trip: Thread? = null
        bus.onStart = {
            trip = thread { r.stopListening(); tripDone.set(true) }
            Thread.sleep(150)
            blockedDuringOpen = !tripDone.get()                     // the trip cannot complete between the compare and the open
        }
        r.assistOpened()
        trip!!.join(5_000)
        assertTrue(blockedDuringOpen, "compare-and-open is atomic with the trip")
        assertTrue(tripDone.get())
        assertFalse(bus.open, "the session that was being opened is closed by the trip")
    }

    @Test fun aTripBeforeTheCompareMeansNothingOpens() {
        val bus = RaceBus(); lateinit var r: BridgeRenderer
        r = BridgeRenderer({ bus }, {}, { r.stopListening(); true }).also { it.events = ev; it.setFeatures(ASK) }
        r.assistOpened()
        assertFalse(bus.open)
    }

    /** Interleaves authorization, the trip and the open at every step: the trip always starts after the press began, so at the end nothing may be open. */
    @Test fun noInterleavingOfAuthorizationTripAndOpenLeavesASessionOpenAfterTheTrip() {
        repeat(300) { seed ->
            val rnd = Random(seed)
            val bus = RaceBus()
            val started = CountDownLatch(1)
            val authDelay = rnd.nextLong(0, 4); val openDelay = rnd.nextLong(0, 4); val tripDelay = rnd.nextLong(0, 6)
            bus.onStart = { if (openDelay > 0) Thread.sleep(openDelay) }
            val r = BridgeRenderer({ bus }, {}, { started.countDown(); if (authDelay > 0) Thread.sleep(authDelay); true }).also { it.events = ev; it.setFeatures(ASK) }
            val a = thread { r.assistOpened() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val b = thread { if (tripDelay > 0) Thread.sleep(tripDelay); r.stopListening() }
            a.join(5_000); b.join(5_000)
            assertFalse(bus.open, "seed $seed: a session stayed open after a completed trip")
        }
    }

    @Test fun anAuthorizationThatNeverAnswersIsANoWithinTheTimeout() {
        val bus = RaceBus(); val never = CountDownLatch(1)
        val r = BridgeRenderer({ bus }, {}, { never.await(30, TimeUnit.SECONDS) }, authorizeTimeoutMs = 150).also { it.events = ev; it.setFeatures(ASK) }
        val t0 = System.nanoTime()
        r.assistOpened()
        val ms = (System.nanoTime() - t0) / 1_000_000
        never.countDown()
        assertTrue(ms < 3_000, "returned after ${ms}ms")
        assertFalse(bus.open)
    }

    // ---- P1-1: trip epochs ----

    private val ids = listOf("dismiss"); private val labels = listOf("Dismiss")

    @Test fun aShowStampedBeforeTheLatestTripIsDroppedBridgeSide() {
        val bus = RaceBus(); val r = BridgeRenderer({ bus }, {})
        r.tripAt(5)
        assertEquals(NexusResult.STALE, r.showAt(4, Slot.NOTICE.code, "T", "b", 5_000, false, ids, labels))
        assertEquals(null, bus.notice)
        assertEquals(NexusResult.OK, r.showAt(5, Slot.NOTICE.code, "T", "b", 5_000, false, ids, labels))
        assertEquals("b", bus.notice)
        r.tripAt(3)                                           // epochs never go back
        assertEquals(NexusResult.STALE, r.showAt(4, Slot.NOTICE.code, "T", "b2", 5_000, false, ids, labels))
    }

    @Test fun aShowThatLandsWhileTheTripIsToldIsHiddenAgain() {
        val bus = RaceBus(); val r = BridgeRenderer({ bus }, {})
        bus.onShow = { r.tripAt(9) }                          // the trip notice arrives between the check and the draw
        assertEquals(NexusResult.STALE, r.showAt(8, Slot.NOTICE.code, "T", "b", 5_000, false, ids, labels))
        assertEquals(null, bus.notice, "drawn and hidden again")
    }

    @Test fun aNewConnectionStartsANewEpochCounter() {
        val bus = RaceBus(); val r = BridgeRenderer({ bus }, {})
        r.tripAt(50); r.resetEpoch()
        assertEquals(NexusResult.OK, r.showAt(1, Slot.NOTICE.code, "T", "b", 5_000, false, ids, labels))
    }

    // ---- P1-3 ----

    @Test fun theWipeAcknowledgementCarriesTheGenerationOfItsRequest() {
        val bus = RaceBus(); val r = BridgeRenderer({ bus }, {})
        val got = mutableListOf<Long>()
        r.events = object : BridgeEvents {
            override fun onAction(slot: Int, actionId: String) = Unit
            override fun onSpeechDone() = Unit
            override fun onForgetAccepted() = Unit
            override fun onForgetAcceptedAt(generation: Long) { got += generation }
        }
        assertEquals(NexusResult.OK, r.forgetHistoryAt(7)); bus.accept!!.invoke()
        assertEquals(listOf(7L), got)
    }
}
