package com.rokid.context.orchestration.nudge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.NudgeEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [34])
class NudgeEvaluatorTest {
    private val evaluated = mutableListOf<Pair<Long, List<String>>>()

    private fun TestScope.evaluator(settle: Long = 5_000, gap: Long = 30_000) = NudgeEvaluator(
        clock = { testScheduler.currentTime }, settleMs = { settle }, gapMs = { gap },
        evaluate = { ids -> evaluated += testScheduler.currentTime to ids; NudgeDecision(true, "t") },
        scope = backgroundScope,
    )

    @Test fun settleWindowCoalesces() = kotlinx.coroutines.test.runTest {
        val e = evaluator()
        e.offer("a", true)
        advanceTimeBy(3_000); e.offer("b", true)
        e.offer("ignored", false)
        advanceTimeBy(1_999); runCurrent()
        assertTrue(evaluated.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(5_000L to listOf("a", "b")), evaluated)
    }

    @Test fun gapEnforced() = kotlinx.coroutines.test.runTest {
        val e = evaluator()
        e.offer("a", true)
        advanceTimeBy(5_001)
        assertEquals(1, evaluated.size)
        advanceTimeBy(1_000); e.offer("b", true) // t=6 s, inside the gap
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, evaluated.size)
    }

    @Test fun arrivalInGapStartsNextWindowAtGapEnd() = kotlinx.coroutines.test.runTest {
        val e = evaluator()
        e.offer("a", true)
        advanceTimeBy(5_001) // first check at 5 s; the gap ends at 35 s
        advanceTimeBy(5_000); e.offer("b", true) // t=10 s
        advanceTimeBy(24_999); runCurrent() // t=35 s - 1 ms
        assertEquals(1, evaluated.size)
        advanceTimeBy(4_999); runCurrent() // window 35..40 s
        assertEquals(1, evaluated.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(5_000L to listOf("a"), 40_000L to listOf("b")), evaluated)
    }

    @Test fun latencyUsesOffsetCorrectedCaptureTime() = kotlinx.coroutines.test.runTest {
        val decisions = mutableListOf<Pair<NudgeDecision, Long>>()
        val e = evaluator()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { e.decisions.toList(decisions) }
        runCurrent()
        // Glasses clock runs 120 s behind the phone: raw capture 1 s, offset +120 s -> phone time 121 s.
        advanceTimeBy(121_000)
        e.offer("a", true, captureMs = 1_000, clockOffsetMs = 120_000) // phone arrival at 121 s
        advanceTimeBy(5_001); runCurrent()
        assertEquals(1, decisions.size)
        assertEquals(5_000L, decisions.single().second) // not 125 s from the raw glasses time
    }

    /** A "nudge" decision while the shown-nudge gap holds is dropped with a reason and leaves no nudge row. */
    @Test fun throttledDecisionRecordedDropped() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        try {
            val mem = MemoryGraph.get(ctx)
            val now = 10_000_000L
            mem.nudges.addIgnore(NudgeEntity("n0", now - 5 * 60_000L, "old", "", "[]", 0, "h0", "phone", false))
            val gates = NudgeGates(mem.db.nudgeGateDao(), { 30 * 60_000L }) { 6 }
            val hud = NudgeHud(null, Verbosity.MEDIUM, ZoneId.of("UTC"))
            val gate = gates.check("Milk", hud, now)
            assertEquals(Gate.SHOWN_GAP, gate)
            NudgeEngine.recordDropped(mem, gate, now)
            assertEquals("SHOWN_GAP", mem.traces.recent(5).single { it.state == "NUDGE_DROPPED" }.note)
            assertEquals(1, mem.db.nudgeGateDao().countSince(0))
        } finally {
            MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
        }
    }
}
