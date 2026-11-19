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
    private val s = NexusHudSink(fake, g, { ON }, FakeActions(), { now })

    @Test fun aRefusedWipeIsAskedAgainWithADoublingWaitNotEveryTick() = runBlocking<Unit> {
        fake.forgetResult = NexusResult.NOT_GRANTED
        s.trip(TripReason.FORGET)
        assertEquals(1, fake.forgets)
        while (now < 20_000) { now += 1_000; s.tick() }
        assertTrue(fake.forgets in 2..7, "20 one-second ticks asked ${fake.forgets} times (was one per tick)")
        assertTrue(s.forgetOwed(), "the debt stays owed")
    }

    @Test fun aNewTripAsksAtOnceAndAnOkResetsTheWait() = runBlocking<Unit> {
        fake.forgetResult = NexusResult.NOT_REGISTERED
        s.trip(TripReason.FORGET)
        now += 1_000; s.tick()
        assertEquals(2, fake.forgets)
        fake.forgetResult = NexusResult.OK
        s.trip(TripReason.DELETE_ALL)                           // a user action is not held back by the wait
        assertEquals(3, fake.forgets)
        val before = fake.forgets
        now += 1_000; s.tick()
        assertEquals(before + 1, fake.forgets, "after an OK the wait is reset: the next tick asks at once")
    }
}
