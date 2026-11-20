package com.rokid.context.work

import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.OfflineOnlyRefusedException
import com.rokid.context.work.frames.ExtractOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FallbackParkCapTest {
    @Before fun reset() = FallbackParkCap.resetForTests()

    @Test fun aKeyIsParkedCapTimesAndThenTheNextOneCountsAsAnAttempt() {
        repeat(FallbackParkCap.CAP) { assertFalse("park ${it + 1}", FallbackParkCap.over(listOf("job1"))) }
        assertTrue("park ${FallbackParkCap.CAP + 1} uses an attempt", FallbackParkCap.over(listOf("job1")))
        assertFalse("the counter was reset: a fresh allowance", FallbackParkCap.over(listOf("job1")))
    }

    @Test fun keysAreCountedSeparatelyAndAnyOverKeyTrips() {
        repeat(FallbackParkCap.CAP) { FallbackParkCap.over(listOf("a")) }
        assertFalse(FallbackParkCap.over(listOf("b")))
        assertTrue(FallbackParkCap.over(listOf("b", "a")))
    }

    @Test fun anAsleepFallbackIsClassifiedAsAParkWithTheCapFlagAndAPlainRefusalIsNot() {
        val asleep = ExtractOutcome.classify(OfflineOnlyRefusedException(fallbackKind = FailureKind.TRANSIENT))
        assertFalse(asleep.countsAttempt); assertTrue(asleep.fallbackUnavailable); assertEquals(ExtractOutcome.DATA_SAVER, asleep.error)
        val plain = ExtractOutcome.classify(OfflineOnlyRefusedException())
        assertFalse(plain.countsAttempt); assertFalse(plain.fallbackUnavailable)
        assertTrue("an ordinary model failure is untouched", ExtractOutcome.classify(ModelCallException("x", retryable = true)).countsAttempt)
    }
}
