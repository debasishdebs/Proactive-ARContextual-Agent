package com.rokid.context.orchestration.nudge

import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.NumberCompare
import com.rokid.context.ai.canvas.WindowAggregate
import com.rokid.context.orchestration.FlowPlans
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** v0.9.8 slice C, the parts of the triggers that need no Android: the rate limit, the signal edge, the event debounce and the cron tick. */
class FlowTriggersTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, d: Int = 28) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun plan(trigger: String, cron: String? = null, signal: FlowPlan.Signal? = null) = FlowPlan.BUILT_IN_NUDGE.copy(trigger = trigger, maxPerHour = 2, cron = cron, signal = signal)

    @Test fun theRateLimitAdmitsPerHourAndDoesNotCountARefusal() = runBlocking {
        var now = at(10, 0)
        val meta = MemoryTriggerMeta()
        val limiter = TriggerRateLimiter(meta) { now }
        assertTrue(limiter.admit("trigger.moment", 2)); now += 60_000
        assertTrue(limiter.admit("trigger.moment", 2)); now += 60_000
        assertFalse(limiter.admit("trigger.moment", 2)); now += 60_000
        assertFalse("a refusal is not counted", limiter.admit("trigger.moment", 2))
        assertTrue("another trigger has its own budget", limiter.admit("trigger.scene", 2))
        now = at(11, 0) + 1
        assertTrue("the first stamp left the rolling hour", TriggerRateLimiter(meta) { now }.admit("trigger.moment", 2)) // a new instance on the same meta: the budget survived a restart
        assertTrue(limiter.admit("trigger.moment", 0)) // 0 = unlimited
    }

    @Test fun theSignalFiresOnTheEdgeOnly() = runBlocking {
        var v = listOf<Double?>(0.1, 0.1, 0.1)
        val signal = FlowPlan.Signal(WindowAggregate.Settings("gyroMean", "mean", 3), NumberCompare.Settings(">=", 0.5))
        val meta = MemoryTriggerMeta()
        val gate = SignalGate(meta) { _, _, _ -> v }
        assertFalse(gate.fires(signal, 0))
        v = listOf(0.9, 0.9, 0.9)
        assertTrue(gate.fires(signal, 1))
        assertFalse("still high: not again", SignalGate(meta) { _, _, _ -> v }.fires(signal, 2)) // restart-safe: the held state is in the meta
        v = listOf(0.0, 0.0, 0.0); assertFalse(gate.fires(signal, 3))
        v = listOf(0.9, 0.9, 0.9); assertTrue("high again after a low", gate.fires(signal, 4))
        v = listOf(null, null, null); assertFalse("no numbers is no signal", gate.fires(signal, 5))
    }

    @Test fun anEventEnqueuesOnlyForThePublishedRootAndIsDebounced() = runBlocking {
        var now = 1_000_000L
        val queued = mutableListOf<String>()
        var published: FlowPlan? = plan(NodeTypes.TRIGGER_MOMENT)
        val t = FlowTriggers(FlowPlans { published }, { t, _ -> queued += t; true }, { now }, debounceMs = 60_000)
        assertFalse("a scene event does nothing while the flow is rooted at moments", t.fire(NodeTypes.TRIGGER_SCENE))
        assertTrue(t.fire(NodeTypes.TRIGGER_MOMENT)); now += 1_000
        assertFalse("a burst is one run", t.fire(NodeTypes.TRIGGER_MOMENT)); now += 60_000
        assertTrue(t.fire(NodeTypes.TRIGGER_MOMENT))
        published = null // the built-in flow: its root is the summarizer pass, so no event trigger enqueues
        now += 120_000; assertFalse(t.fire(NodeTypes.TRIGGER_MOMENT))
        assertEquals(listOf(NodeTypes.TRIGGER_MOMENT, NodeTypes.TRIGGER_MOMENT), queued)
    }

    @Test fun aThrowingPlanLookupRunsTheBuiltInFlowInstead() = runBlocking {
        val queued = mutableListOf<String>()
        assertTrue(FlowTriggers(FlowPlans { throw IllegalStateException("db") }, { t, _ -> queued += t; true }).fire(NodeTypes.TRIGGER_MOMENT))
        assertEquals("the built-in flow still runs, as a summarizer pass", listOf(FlowTriggers.BUILTIN_FALLBACK), queued)
    }

    @Test fun theScheduleFiresOnceWhenDueSurvivesARestartAndNeverReplaysMissedRuns() = runBlocking {
        var now = at(8, 0)
        val fired = mutableListOf<String>()
        val meta = MemoryTriggerMeta()
        val plans = FlowPlans { plan(NodeTypes.TRIGGER_SCHEDULE, cron = "0 9 * * *") }
        fun tick() = ScheduleTick(plans, meta, { t, _ -> fired += t; true }, { now }, { zone }) // a new instance each time: only the meta carries state (a restart)
        assertFalse("the first check only records the time", tick().tick())
        now = at(8, 45); assertFalse(tick().tick())
        now = at(9, 5); assertTrue(tick().tick())
        now = at(9, 20); assertFalse("the 09:00 run is done", tick().tick())
        now = at(23, 0); assertFalse(tick().tick())
        // the phone slept for three days: one run, not three
        now = at(10, 0, d = 31); assertTrue(tick().tick())
        now = at(10, 15, d = 31); assertFalse(tick().tick())
        assertEquals(listOf(NodeTypes.TRIGGER_SCHEDULE, NodeTypes.TRIGGER_SCHEDULE), fired)
    }

    @Test fun aNewPublicationNeverFiresRetroactivelyAndHasItsOwnState() = runBlocking {
        var now = at(8, 0)
        var published: FlowPlan? = plan(NodeTypes.TRIGGER_SCHEDULE, cron = "0 9 * * *").copy(scope = "p1")
        val fired = mutableListOf<String>()
        val meta = MemoryTriggerMeta()
        fun tick() = ScheduleTick(FlowPlans { published }, meta, { t, _ -> fired += t; true }, { now }, { zone })
        tick().tick()
        published = null; now = at(8, 30); assertFalse(tick().tick())
        published = plan(NodeTypes.TRIGGER_SCHEDULE, cron = "0 9 * * *").copy(scope = "p2"); now = at(12, 0); assertFalse("09:00 passed before this publication existed", tick().tick())
        assertTrue(fired.isEmpty())
    }

    @Test fun aClockSetBackNeitherReplaysNorReopensTheBudget() = runBlocking {
        var now = at(8, 0)
        val fired = mutableListOf<String>()
        val meta = MemoryTriggerMeta()
        val plans = FlowPlans { plan(NodeTypes.TRIGGER_SCHEDULE, cron = "0 9 * * *") }
        fun tick() = ScheduleTick(plans, meta, { t, _ -> fired += t; true }, { now }, { zone })
        tick().tick(); now = at(9, 5); assertTrue(tick().tick())
        now = at(8, 0); assertFalse("clock back before the occurrence: the 09:00 run is not replayed", tick().tick())
        now = at(9, 30); assertFalse(tick().tick())
        assertEquals(1, fired.size)
        // rate stamps in the future still count
        var t = at(10, 0)
        val limiter = TriggerRateLimiter(MemoryTriggerMeta()) { t }
        assertTrue(limiter.admit("m", 1, "p1")); t = at(9, 0)
        assertFalse(limiter.admit("m", 1, "p1"))
        assertTrue("another publication has its own budget", limiter.admit("m", 1, "p2"))
    }

    @Test fun anOccurrenceIsKeptPendingUntilTheEnqueueIsDurable() = runBlocking {
        var now = at(8, 0)
        var ok = false
        val fired = mutableListOf<String>()
        val meta = MemoryTriggerMeta()
        val plans = FlowPlans { plan(NodeTypes.TRIGGER_SCHEDULE, cron = "0 9 * * *") }
        fun tick() = ScheduleTick(plans, meta, { t, _ -> fired += t; ok }, { now }, { zone })
        tick().tick(); now = at(9, 5)
        assertFalse("enqueue failed", tick().tick()); now = at(9, 20)
        assertFalse(tick().tick()); ok = true
        assertTrue("retried until it is durable", tick().tick())
        now = at(9, 40); assertFalse(tick().tick())
        assertEquals(3, fired.size)
    }

    @Test fun aSignalHasItsOwnStatePerPublication() = runBlocking {
        val signal = FlowPlan.Signal(WindowAggregate.Settings("gyroMean", "mean", 3), NumberCompare.Settings(">=", 0.5))
        val meta = MemoryTriggerMeta()
        val v = listOf<Double?>(0.9, 0.9, 0.9)
        assertTrue(SignalGate(meta, "p1") { _, _, _ -> v }.fires(signal, 0))
        assertTrue("a new publication starts clean", SignalGate(meta, "p2") { _, _, _ -> v }.fires(signal, 0))
    }
}
