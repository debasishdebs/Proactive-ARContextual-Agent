package com.rokid.context.work.frames.intent

import com.rokid.context.ai.contract.IntentOutput
import com.rokid.context.core.model.context.IntentLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntentSamplerTest {
    private val T0 = 10_000_000L
    private var now = T0

    private class Store : IntentFrameStore {
        var frame: IntentFrame? = null
        var rows = 1
        var tomb = false
        val stored = mutableListOf<Triple<String, String, Float>>()
        var lastSince = -1L
        override suspend fun newestUnlabelled(sinceReceivedMs: Long): IntentFrame? { lastSince = sinceReceivedMs; return frame }
        var tombAfterSet = false
        override suspend fun setIntent(frameId: String, label: String, conf: Float, at: Long): Int { stored += Triple(frameId, label, conf); if (tombAfterSet) tomb = true; return rows }
        override suspend fun tombstoned(frameId: String) = tomb
    }

    private val store = Store()
    private var calls = 0
    private var reply: suspend (String) -> IntentOutput? = { IntentOutput(IntentLabel.SITTING_IDLE, 0.8f) }
    private var episode = true
    private var enabled = true
    private var pipeline = true
    private var admit = true
    private var gen = 0L
    private val results = mutableListOf<Triple<String, Long, IntentOutput>>()
    private val stats = mutableListOf<Boolean?>()

    private val flight = IntentFlight()
    private var preflight = true
    private var admitCalls = 0

    private fun sampler(scope: TestScope) = IntentSampler(
        scope = scope, store = store, classify = { id -> calls++; reply(id) }, clock = { now },
        episode = { episode }, onResult = { id, ts, o -> results += Triple(id, ts, o) }, enabled = { enabled },
        stats = { stats += it }, admit = { admitCalls++; admit }, pipelineOn = { pipeline }, generation = { gen },
        flight = flight, preflight = { preflight },
    )

    private fun fresh(id: String = "f", ageMs: Long = 10_000) { store.frame = IntentFrame(id, now - ageMs) }
    private fun TestScope.poke(s: IntentSampler) { s.poke(); runCurrent() }

    @Test fun firstPokeWithAFreshFrameCallsOnce() = runTest {
        val s = sampler(this); fresh(); poke(s)
        assertEquals(1, calls); assertEquals(listOf(true), stats)
        assertEquals(listOf(Triple("f", "SITTING_IDLE", 0.8f)), store.stored)
        assertEquals(1, results.size); assertEquals("f", results[0].first); assertEquals(now - 10_000, results[0].second)
    }

    @Test fun insideAnEpisodeItCallsAMinuteApartAndSkipsInBetween() = runTest {
        val s = sampler(this); fresh(); poke(s)
        now += 30_000; fresh(); poke(s)
        assertEquals(1, calls); assertEquals(listOf<Boolean?>(true, null), stats)
        now += 31_000; fresh(); poke(s)          // 61 s after the call
        assertEquals(2, calls)
    }

    @Test fun noCallsOutsideAnEpisodeNotEvenTheOldFiveMinuteProbe() = runTest {
        episode = false
        val s = sampler(this); fresh(); poke(s)
        now += 301_000; fresh(); poke(s)
        assertEquals(0, calls); assertEquals(listOf<Boolean?>(null, null), stats)
    }

    @Test fun aFrameCapturedThreeMinutesAgoIsSkipped() = runTest {
        val s = sampler(this); fresh(ageMs = 180_000); poke(s)
        assertEquals(0, calls); assertEquals(listOf<Boolean?>(null), stats)
    }

    @Test fun aBacklogFrameReceivedNowButCapturedLongAgoIsNeverClassified() = runTest {
        val s = sampler(this); fresh(ageMs = 30 * 60_000L); poke(s)
        assertEquals(0, calls); assertTrue(store.stored.isEmpty()); assertTrue(results.isEmpty())
    }

    @Test fun aFrameWithUnknownCaptureTimeIsNeverClassified() = runTest {
        val s = sampler(this); store.frame = IntentFrame("f", null); poke(s)
        assertEquals(0, calls); assertEquals(listOf<Boolean?>(null), stats)
    }

    @Test fun aCaptureTimeInTheFutureIsUncertainAndSkipped() = runTest {
        val s = sampler(this); store.frame = IntentFrame("f", now + 120_000); poke(s)
        assertEquals(0, calls)
    }

    @Test fun aClassifyFailureCountsAFailureAndStoresNothing() = runTest {
        reply = { null }
        val s = sampler(this); fresh(); poke(s)
        assertEquals(1, calls); assertEquals(listOf<Boolean?>(false), stats)
        assertTrue(store.stored.isEmpty()); assertTrue(results.isEmpty())
        now += 30_000; fresh(); poke(s)           // a failed call still spends the minute
        assertEquals(1, calls)
    }

    @Test fun aClassifyExceptionIsAFailureToo() = runTest {
        reply = { throw IllegalStateException("boom") }
        val s = sampler(this); fresh(); poke(s)
        assertEquals(listOf<Boolean?>(false), stats); assertTrue(results.isEmpty())
    }

    @Test fun disabledMeansNoCallAndNoSkipCount() = runTest {
        enabled = false
        val s = sampler(this); fresh(); poke(s)
        assertEquals(0, calls); assertTrue(stats.isEmpty())
    }

    @Test fun twoPokesWhileACallIsInFlightMakeOneCall() = runTest {
        val gate = CompletableDeferred<IntentOutput?>()
        reply = { gate.await() }
        val s = sampler(this); fresh(); s.poke(); runCurrent(); s.poke(); runCurrent()
        assertEquals(1, calls)
        gate.complete(IntentOutput(IntentLabel.DRIVING, 0.9f)); advanceUntilIdle()
        assertEquals(1, calls); assertEquals(1, store.stored.size)
        assertEquals(listOf<Boolean?>(null, true), stats)
    }

    @Test fun aResultForAFrameElevenMinutesOldIsStoredButNotFed() = runTest {
        val gate = CompletableDeferred<IntentOutput?>()
        reply = { gate.await() }
        val s = sampler(this); fresh(ageMs = 100_000); s.poke(); runCurrent()
        now += 11 * 60_000L                       // the call took very long
        gate.complete(IntentOutput(IntentLabel.DRIVING, 0.9f)); advanceUntilIdle()
        assertEquals(1, store.stored.size); assertTrue(results.isEmpty()); assertEquals(listOf<Boolean?>(true), stats)
    }

    // ---- Codex-derived fences
    @Test fun noRowChangedMeansNoEvidenceAndNoSuccess() = runTest {
        store.rows = 0
        val s = sampler(this); fresh(); poke(s)
        assertEquals(1, calls); assertTrue(results.isEmpty()); assertFalse(true in stats)
    }

    @Test fun aWipeDuringTheCallDropsTheResult() = runTest {
        val gate = CompletableDeferred<IntentOutput?>()
        reply = { gate.await() }
        val s = sampler(this); fresh(); s.poke(); runCurrent()
        gen++                                      // Delete all
        gate.complete(IntentOutput(IntentLabel.DRIVING, 0.9f)); advanceUntilIdle()
        assertTrue(store.stored.isEmpty()); assertTrue(results.isEmpty())
    }

    @Test fun aSourceSwitchedOffDuringTheCallDropsTheResult() = runTest {
        val gate = CompletableDeferred<IntentOutput?>()
        reply = { gate.await() }
        val s = sampler(this); fresh(); s.poke(); runCurrent()
        enabled = false
        gate.complete(IntentOutput(IntentLabel.DRIVING, 0.9f)); advanceUntilIdle()
        assertTrue(store.stored.isEmpty()); assertTrue(results.isEmpty())
    }

    @Test fun thePipelinePausedDuringTheCallDropsTheResult() = runTest {
        val gate = CompletableDeferred<IntentOutput?>()
        reply = { gate.await() }
        val s = sampler(this); fresh(); s.poke(); runCurrent()
        pipeline = false
        gate.complete(IntentOutput(IntentLabel.DRIVING, 0.9f)); advanceUntilIdle()
        assertTrue(store.stored.isEmpty()); assertTrue(results.isEmpty())
    }

    @Test fun aPausedPipelineAtAdmissionMakesNoCall() = runTest {
        pipeline = false
        val s = sampler(this); fresh(); poke(s)
        assertEquals(0, calls)
    }

    @Test fun aFrameForgottenBeforeOrDuringTheCallIsNeverStoredOrFed() = runTest {
        store.tomb = true
        val s = sampler(this); fresh(); poke(s)
        assertEquals(0, calls)
        store.tomb = false; now += 61_000; fresh()
        val gate = CompletableDeferred<IntentOutput?>()
        reply = { gate.await() }
        s.poke(); runCurrent()
        store.tomb = true                          // Forget while the model works
        gate.complete(IntentOutput(IntentLabel.DRIVING, 0.9f)); advanceUntilIdle()
        assertTrue(store.stored.isEmpty()); assertTrue(results.isEmpty())
    }

    @Test fun theSharedPowerGateRefusesTheCall() = runTest {
        admit = false
        val s = sampler(this); fresh(); poke(s)
        assertEquals(0, calls); assertEquals(listOf<Boolean?>(null), stats)
        admit = true; poke(s)                      // a refused call did not spend the minute
        assertEquals(1, calls)
    }

    @Test fun closeCancelsAnInFlightCallAndStoresNothing() = runTest {
        val gate = CompletableDeferred<IntentOutput?>()
        reply = { gate.await() }
        val s = sampler(this); fresh(); s.poke(); runCurrent()
        s.close()
        gate.complete(IntentOutput(IntentLabel.DRIVING, 0.9f)); advanceUntilIdle()
        assertTrue(store.stored.isEmpty()); assertTrue(results.isEmpty())
        s.poke(); runCurrent(); assertEquals(1, calls)   // a closed sampler never calls again
    }

    @Test fun theCandidateQueryLooksAtMostTwoMinutesBack() = runTest {
        val s = sampler(this); fresh(); poke(s)
        assertEquals(now - 120_000, store.lastSince)
    }

    // ---- fix round 1
    @Test fun offThenOnDuringACallCannotStartASecondUploadOrResetTheMinute() = runTest {
        val gate = CompletableDeferred<IntentOutput?>()
        reply = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() } }   // an HTTP call that ignores cancellation
        val first = sampler(this); fresh(); first.poke(); runCurrent()
        first.close()                              // the source went off ...
        val second = sampler(this)                 // ... and on again: a new instance
        second.poke(); runCurrent()
        assertEquals("no second upload while the first still runs", 1, calls)
        gate.complete(null); advanceUntilIdle()
        now += 30_000; fresh(); second.poke(); runCurrent()
        assertEquals("the minute is measured from the first call, not reset by the new instance", 1, calls)
        now += 31_000; fresh(); second.poke(); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun everyModelCallIsCountedEvenWhenTheResultIsDropped() = runTest {
        // fenced drop
        val gate = CompletableDeferred<IntentOutput?>(); reply = { gate.await() }
        val s = sampler(this); fresh(); s.poke(); runCurrent(); gen++
        gate.complete(IntentOutput(IntentLabel.DRIVING, 0.9f)); advanceUntilIdle()
        assertEquals(listOf<Boolean?>(false), stats)
    }

    @Test fun aZeroRowWriteIsCountedAsAFailedCall() = runTest {
        store.rows = 0
        val s = sampler(this); fresh(); poke(s)
        assertEquals(listOf<Boolean?>(false), stats)
    }

    @Test fun aCancelledCallIsCounted() = runTest {
        val gate = CompletableDeferred<IntentOutput?>(); reply = { gate.await() }
        val s = sampler(this); fresh(); s.poke(); runCurrent()
        s.close(); advanceUntilIdle()
        assertEquals(listOf<Boolean?>(false), stats)
    }

    @Test fun aForgetBetweenTheWriteAndTheVoteSendsNoVote() = runTest {
        store.tombAfterSet = true
        val s = sampler(this); fresh(); poke(s)
        assertEquals(1, store.stored.size); assertTrue(results.isEmpty())
    }

    @Test fun anUnreadablePhotoNeverTakesTheSharedSlot() = runTest {
        preflight = false
        val s = sampler(this); fresh(); poke(s)
        assertEquals(0, admitCalls); assertEquals(0, calls)
    }
}
