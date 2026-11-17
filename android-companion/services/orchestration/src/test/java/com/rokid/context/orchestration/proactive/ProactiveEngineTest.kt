package com.rokid.context.orchestration.proactive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.orchestration.AskZoneGuard
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.nudge.NudgeHud
import com.rokid.context.orchestration.proactive.generators.PinInfo
import com.rokid.context.orchestration.proactive.generators.PlaceRecallGenerator
import com.rokid.context.orchestration.proactive.generators.PlaceVisit
import com.rokid.context.provenance.ProvenanceGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** The 9-step gate pipeline over a real in-memory database: every gate that trips records NOTHING as would-show, and nothing is ever delivered or written to the nudge table. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProactiveEngineTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 18, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val store = InMemorySuggestionStore()
    private val history = FakeHistory()
    private val leases = Leases()
    private val gate = DataOpGate(waitMs = 50, leases = leases)
    private var enabled = true; private var paused = false; private var worn = true; private var driving = false
    private var guard: AskZoneGuard = AskZoneGuard { false }
    private var hud: NudgeHud? = NudgeHud(null, Verbosity.MEDIUM, zone)
    private var cap = 6; private var nudgesToday = 0; private var anchorBlocked = false; private var pinsLive = true
    private var throwPause = false

    private fun visit(id: String, ts: Long, key: String = "wd:Q1") = PlaceVisit(id, null, key, "Golconda", null, ts, 0.8)

    @Before fun setUp() = runBlocking {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        for (id in listOf("m_now", "m_old")) MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory(id, "place", 0.8, now, "Looked at Golconda").copy(tier = MemoryTier.LONG, reviewState = ReviewState.OK))
        history.visits = listOf(visit("m_now", now - 60_000), visit("m_old", now - 6 * DAY))
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun engine(): ProactiveEngine {
        val mem = MemoryGraph.get(ctx)
        val seams = ProactiveSeams(
            enabled = { enabled }, paused = { if (throwPause) throw IllegalStateException("x") else paused }, zoneGuard = guard, worn = { worn }, driving = { driving }, hud = { hud },
            globalCap = { cap }, globalToday = { nudgesToday }, anchorBlocked = { anchorBlocked }, pinsLive = { pinsLive },
        )
        return ProactiveEngine(mem.db, ProvenanceGate.forGraph(mem), store, listOf(PlaceRecallGenerator(history) { zone }), seams, gate, clock = { now })
    }

    private fun assertNothingRecorded(g: Gate, run: ProactiveRun) {
        assertEquals(g, run.gate); assertNull(run.record)
        assertTrue("a tripped gate records nothing", store.records().isEmpty())
        assertNull(store.counters()["would_show"]); assertEquals("shadow mode writes no nudge row", 0, runBlocking { MemoryGraph.get(ctx).db.nudgeGateDao().countSince(0) })
    }

    @Test fun passRecordsAWouldShowWithIdsAndTokensOnly() = runBlocking {
        val run = engine().run(TriggerKind.PLACE_ARRIVAL)
        assertEquals(Gate.WOULD_SHOW, run.gate)
        val r = store.records().single()
        assertEquals(run.record, r); assertEquals("f2:wd:Q1", r.key); assertEquals(listOf("mem:m_old"), r.sourceIds); assertEquals(TriggerKind.PLACE_ARRIVAL, r.trigger)
        assertTrue(r.factors.containsAll(listOf("arrived", "visits=1")))
        assertEquals(1, store.counters()["would_show"]); assertEquals(0, MemoryGraph.get(ctx).db.nudgeGateDao().countSince(0))
        assertEquals("the lease is closed", 0, leases.openCount)
    }

    @Test fun switchOff() = runBlocking { enabled = false; assertNothingRecorded(Gate.SWITCH, engine().run(TriggerKind.TICK)) }
    @Test fun paused() = runBlocking { paused = true; assertNothingRecorded(Gate.PAUSE, engine().run(TriggerKind.TICK)) }
    @Test fun aThrowingPauseCheckIsPaused() = runBlocking { throwPause = true; assertNothingRecorded(Gate.PAUSE, engine().run(TriggerKind.TICK)) }
    @Test fun zoneFenceBlocked() = runBlocking { guard = AskZoneGuard { true }; assertNothingRecorded(Gate.ZONE, engine().run(TriggerKind.TICK)) }
    @Test fun anAnchorPlaceInsideAZone() = runBlocking { anchorBlocked = true; assertNothingRecorded(Gate.ZONE_ANCHOR, engine().run(TriggerKind.TICK)) }
    @Test fun noHudSettingsFailsClosed() = runBlocking { hud = null; assertNothingRecorded(Gate.OFF, engine().run(TriggerKind.TICK)) }
    @Test fun quietHoursAlwaysWin() = runBlocking { hud = NudgeHud(QuietHours(11 * 60, 13 * 60), Verbosity.MEDIUM, zone); assertNothingRecorded(Gate.QUIET_HOURS, engine().run(TriggerKind.TICK)) }
    @Test fun globalCapZeroDisablesEverything() = runBlocking { cap = 0; assertNothingRecorded(Gate.OFF, engine().run(TriggerKind.TICK)) }
    @Test fun theNudgeTableCountsInsideTheGlobalCap() = runBlocking { cap = 3; nudgesToday = 3; assertNothingRecorded(Gate.GLOBAL_CAP, engine().run(TriggerKind.TICK)) }
    @Test fun neverList() = runBlocking { store.addNever("f2:wd:Q1"); assertNothingRecorded(Gate.FEEDBACK, engine().run(TriggerKind.TICK).also { assertEquals(1, store.never().size) }) }
    @Test fun notWorn() = runBlocking { worn = false; assertNothingRecorded(Gate.WEAR, engine().run(TriggerKind.TICK)) }
    @Test fun drivingDropsEverything() = runBlocking { driving = true; assertNothingRecorded(Gate.DRIVING, engine().run(TriggerKind.TICK)) }
    @Test fun noCandidates() = runBlocking { history.visits = emptyList(); assertNothingRecorded(Gate.NO_CANDIDATES, engine().run(TriggerKind.TICK)) }

    @Test fun aForgetThatHoldsTheGateBlocksTheLease() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val deleting = async { gate.delete { release.await() } }
        yield()
        assertNothingRecorded(Gate.LEASE, engine().run(TriggerKind.TICK))
        release.complete(Unit); deleting.await()
    }

    @Test fun aSourceForgottenBeforeTheCommitIsNotRecorded() = runBlocking {
        MemoryGraph.get(ctx).memories.deleteByIds(listOf("m_old"))
        val run = engine().run(TriggerKind.TICK) // the lease's own live check and the provenance gate both refuse a memory that is gone
        assertTrue(run.gate == Gate.LEASE || run.gate == Gate.PROVENANCE); assertTrue(store.records().isEmpty())
    }

    @Test fun aForgetDuringTheRunRevokesTheLeaseAndNothingCommits() = runBlocking {
        val e = engine()
        e.testBeforeCommit = { leases.revoke(listOf(Ref(Family.MEMORY, "m_old"))) }
        assertNothingRecorded(Gate.LEASE, e.run(TriggerKind.TICK))
    }

    @Test fun aDeleteAllDuringTheRunCommitsNothing() = runBlocking {
        val e = engine()
        e.testBeforeCommit = { MemoryWipe.generation.incrementAndGet() }
        val run = e.run(TriggerKind.TICK)
        assertTrue(run.gate == Gate.LEASE || run.gate == Gate.PROVENANCE); assertTrue(store.records().isEmpty())
    }

    @Test fun aPinThatIsNoLongerThereIsNotRecorded() = runBlocking {
        val pin = "user:00000000-0000-0000-0000-000000000001"
        history.visits = listOf(visit("m_now", now - 60_000, pin), visit("m_old", now - 6 * DAY, pin)); history.pins = listOf(PinInfo(pin, "Golconda", "my note"))
        pinsLive = false
        assertNothingRecorded(Gate.PROVENANCE, engine().run(TriggerKind.TICK))
        pinsLive = true
        assertEquals(Gate.WOULD_SHOW, engine().run(TriggerKind.TICK).gate)
        assertTrue("pin:$pin" in store.records().single().sourceIds)
    }

    @Test fun theZoneFenceClosingDuringTheRunCommitsNothing() = runBlocking {
        val fence = ZoneFence()
        guard = object : AskZoneGuard { override fun blocked() = fence.blocked(); override fun ticket() = fence.ticket() }
        val e = engine()
        e.testBeforeCommit = { fence.hold() }
        assertNothingRecorded(Gate.ZONE, e.run(TriggerKind.TICK))
    }

    @Test fun theBudgetIsSharedAndSerialised() = runBlocking {
        val e = engine()
        val runs = (1..4).map { async(kotlinx.coroutines.Dispatchers.Default) { e.run(TriggerKind.TICK) } }.awaitAll()
        assertEquals(1, runs.count { it.gate == Gate.WOULD_SHOW }); assertEquals(1, store.records().size)
        assertEquals("a second run the same moment is held by the gap or the cooldown", Gate.SHOWN_GAP, runs.first { it.gate != Gate.WOULD_SHOW }.gate)
    }

    @Test fun dryRunVerifiesButTheCallersStoreIsTheOnlyWrite() = runBlocking {
        enabled = false; paused = true; worn = false; driving = true; guard = AskZoneGuard { true } // live-state gates are not part of a replay
        val run = engine().run(TriggerKind.TICK, dry = true)
        assertEquals(Gate.WOULD_SHOW, run.gate); assertTrue(run.reports.single().verdict == Gate.WOULD_SHOW)
        assertTrue("dry run bumps no counter", store.counters().isEmpty())
    }
}
