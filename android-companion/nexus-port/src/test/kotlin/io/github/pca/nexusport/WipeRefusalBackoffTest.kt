package io.github.pca.nexusport

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Found by the real-hub run (2026-11-19): a hub that refuses the history wipe for good (capability not granted) was asked again by EVERY tick, once a second, for as long as the refusal lasted. */
class WipeRefusalBackoffTest {
    private val fake = FakeNexusTransport()
    private val g = FakeGates()
    private var now = 0L
    private var mono = 0L
    private val s = NexusHudSink(fake, g, { ON }, FakeActions(), { now }, monotonicMs = { mono })

    @Test fun aRefusedWipeIsAskedAgainWithADoublingWaitNotEveryTick() = runBlocking<Unit> {
        fake.forgetResult = NexusResult.NOT_GRANTED
        s.trip(TripReason.FORGET)
        assertEquals(1, fake.forgets)
        while (now < 20_000) { now += 1_000; mono += 1_000; s.tick() }
        assertTrue(fake.forgets in 2..7, "20 one-second ticks asked ${fake.forgets} times (was one per tick)")
        assertTrue(s.forgetOwed(), "the debt stays owed")
    }

    @Test fun aNewTripAsksAtOnceAndAnOkResetsTheWait() = runBlocking<Unit> {
        fake.forgetResult = NexusResult.NOT_REGISTERED
        s.trip(TripReason.FORGET)
        now += 1_000; mono += 1_000; s.tick()
        assertEquals(2, fake.forgets)
        fake.forgetResult = NexusResult.OK
        s.trip(TripReason.DELETE_ALL)                           // a user action is not held back by the wait
        assertEquals(3, fake.forgets)
        val before = fake.forgets
        now += 1_000; mono += 1_000; s.tick()
        assertEquals(before + 1, fake.forgets, "after an OK the wait is reset: the next tick asks at once")
    }

    @Test fun aWallClockJumpNeitherFreezesNorFloodsTheRetries() = runBlocking<Unit> {
        fake.forgetResult = NexusResult.NOT_GRANTED
        s.trip(TripReason.FORGET)
        for (i in 1..5) { now += 1_000; mono += 1_000; s.tick() }
        val before = fake.forgets
        now -= 6 * 3_600_000L                                   // the wall clock jumps back six hours
        for (i in 1..40) { mono += 1_000; s.tick() }            // 40 s of real time
        assertTrue(fake.forgets > before, "still asked on the monotonic schedule after a backwards jump")
        val mid = fake.forgets
        now += 24 * 3_600_000L                                  // and forward a day: no flood
        for (i in 1..20) { mono += 1_000; s.tick() }
        assertTrue(fake.forgets - mid in 0..2, "a forward jump asked ${fake.forgets - mid} times in 20 s")
    }

    @Test fun aNewConnectionAndANewTripRestartTheWaitAtOneSecond() = runBlocking<Unit> {
        fake.forgetResult = NexusResult.NOT_GRANTED
        s.trip(TripReason.FORGET)
        for (i in 1..30) { mono += 1_000; s.tick() }            // the wait has grown to its cap
        val a = fake.forgets
        s.onBridgeConnected()                                   // asks at once, refused again
        assertEquals(a + 1, fake.forgets)
        mono += 1_000; s.tick()
        assertEquals(a + 2, fake.forgets, "after a reconnect the next wait is 1 s again")
        for (i in 1..30) { mono += 1_000; s.tick() }
        val b = fake.forgets
        s.trip(TripReason.DELETE_ALL)
        assertEquals(b + 1, fake.forgets)
        mono += 1_000; s.tick()
        assertEquals(b + 2, fake.forgets, "after a new trip the next wait is 1 s again")
    }

    @Test fun aBusyHubIsRetriedShortAndNeverEscalates() = runBlocking<Unit> {
        fake.forgetResult = NexusResult.BUSY
        s.trip(TripReason.FORGET)
        for (i in 1..10) { mono += 1_000; s.tick() }
        assertEquals(11, fake.forgets, "BUSY is asked again every second")
    }

    @Test fun anOverlappingLateRefusalDoesNotRearmTheWaitAfterAnOk() = runBlocking<Unit> {
        fake.forgetResult = NexusResult.NOT_GRANTED
        // while the first ask is in flight, a second run (a new trip) is answered OK; then the first one's late refusal lands
        fake.onForget = { fake.forgetResult = NexusResult.OK; s.trip(TripReason.DELETE_ALL); fake.forgetResult = NexusResult.NOT_GRANTED }
        s.trip(TripReason.FORGET)
        assertEquals(2, fake.forgets)
        assertEquals(Long.MIN_VALUE, s.forgetNotBeforeForTest(), "a refusal that started before the OK must not re-arm the wait")
    }
}
