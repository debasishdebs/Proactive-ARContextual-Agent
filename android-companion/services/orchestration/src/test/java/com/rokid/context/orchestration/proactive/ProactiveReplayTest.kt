package com.rokid.context.orchestration.proactive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.orchestration.nudge.NudgeHud
import com.rokid.context.orchestration.proactive.generators.PlaceRecallGenerator
import com.rokid.context.orchestration.proactive.generators.PlaceVisit
import com.rokid.context.provenance.ProvenanceGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProactiveReplayTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val noon = LocalDateTime.of(2026, 11, 18, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val history = FakeHistory(listOf(PlaceVisit("m_now", null, "wd:Q1", "Golconda", null, noon - 60_000, 0.8), PlaceVisit("m_old", null, "wd:Q1", "Golconda", null, noon - 6 * DAY, 0.8)))
    private val real = InMemorySuggestionStore()

    @Before fun setUp() = runBlocking {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        for (id in listOf("m_now", "m_old")) MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory(id, "place", 0.8, noon, "Looked at Golconda").copy(tier = MemoryTier.LONG, reviewState = ReviewState.OK))
    }
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun replayer(gate: com.rokid.context.orchestration.DataOpGate = com.rokid.context.orchestration.DataOpGate.shared): ProactiveReplayer {
        val mem = MemoryGraph.get(ctx)
        val seams = { ProactiveSeams(hud = { NudgeHud(null, Verbosity.MEDIUM, zone) }, globalCap = { 6 }, globalToday = { 0 }, anchorBlocked = { false }, pinsLive = { true }) }
        return ProactiveReplayer(mem.db, ProvenanceGate.forGraph(mem), PlaceRecallGenerator(history) { zone }, seams, never = { real.never() }, zone = { zone }, gate = gate)
    }

    @Test fun aDayIsAWalkOfStepsAndTheBudgetIsSimulatedAcrossThem() = runBlocking {
        val rows = replayer().replay(LocalDate.of(2026, 11, 18), 15, emptyList())
        val shown = rows.filter { r -> r.candidates.any { it.verdict == Gate.WOULD_SHOW } }
        assertEquals("one would-show; the next step is held by the 30 minute gap", 1, shown.size)
        assertEquals(noon, shown.single().atMs)
        val c = shown.single().candidates.single()
        assertEquals("f2:wd:Q1", c.key); assertTrue(c.text!!.startsWith("Golconda: last visit ")); assertEquals(listOf("mem:m_old", "mem:m_now"), c.sourceIds)
        assertEquals(Gate.SHOWN_GAP, rows.first { it.atMs == noon + 15 * 60_000L }.candidates.single().verdict)
        assertEquals(96, replayer().steps(LocalDate.of(2026, 11, 18), 15, emptyList()).size)
    }

    @Test fun aDryRunWritesNothing() = runBlocking {
        val db = MemoryGraph.get(ctx).db
        val before = listOf(db.nudgeGateDao().countSince(0), runBlocking { db.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM memory")).use { it.moveToFirst(); it.getInt(0) } })
        real.addNever("f2:other")
        replayer().replay(LocalDate.of(2026, 11, 18), 15, emptyList())
        val after = listOf(db.nudgeGateDao().countSince(0), db.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM memory")).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(before, after)
        assertTrue("the real store is untouched", real.records().isEmpty() && real.counters().isEmpty() && real.never() == setOf("f2:other"))
    }

    @Test fun theNeverListIsReadInAReplay() = runBlocking {
        real.addNever("f2:wd:Q1")
        val rows = replayer().replay(LocalDate.of(2026, 11, 18), 15, emptyList())
        assertTrue(rows.none { r -> r.candidates.any { it.verdict == Gate.WOULD_SHOW } }); assertTrue(rows.any { r -> r.candidates.any { it.verdict == Gate.FEEDBACK } })
    }

    @Test fun aForgetDuringLaterStepsRevokesTheReplayLeaseAndNothingIsReturned() = runBlocking {
        val leases = com.rokid.context.memory.forget.lifecycle.Leases()
        val r = replayer(com.rokid.context.orchestration.DataOpGate(waitMs = 50, leases = leases))
        r.testAfterStep = { i -> if (i == 60) leases.revoke(listOf(com.rokid.context.memory.forget.lifecycle.Ref(com.rokid.context.memory.forget.lifecycle.Family.MEMORY, "m_old"))) }
        assertTrue("an earlier would-show is not returned after a later Forget", r.replay(LocalDate.of(2026, 11, 18), 15, emptyList()).isEmpty())
        assertEquals("the one lease is closed", 0, leases.openCount)
    }

    @Test fun aSourceGoneAtTheEndIsDroppedFromTheAnswerEvenWhenUnselected() = runBlocking {
        val r = replayer()
        r.testAfterStep = { i -> if (i == 95) MemoryGraph.get(ctx).memories.deleteByIds(listOf("m_old")) }
        val rows = r.replay(LocalDate.of(2026, 11, 18), 15, emptyList())
        assertTrue("no candidate (selected, held or unselected) cites the forgotten memory", rows.all { s -> s.candidates.none { "mem:m_old" in it.sourceIds || it.text != null } })
    }

    @Test fun anEditedPinDuringTheReplayDropsItsCandidates() = runBlocking {
        val pin = "user:00000000-0000-0000-0000-000000000001"
        history.visits = history.visits.map { it.copy(placeKey = pin) }; history.pins = listOf(com.rokid.context.orchestration.proactive.generators.PinInfo(pin, "Golconda", "old note"))
        val pins = com.rokid.context.memory.place.UserPinStore(ctx.getSharedPreferences("replay_pin_test", Context.MODE_PRIVATE))
        fun place(note: String) = com.rokid.context.memory.place.UserPlace(pin, "Golconda", com.rokid.context.memory.place.PlaceKinds.FORT, 17.0, 78.0, note = note)
        assertTrue(pins.write(listOf(place("old note"))))
        val untouched = replayer().replay(LocalDate.of(2026, 11, 18), 15, emptyList())
        assertTrue("with the pin as it was, the would-show is returned with its note", untouched.any { s -> s.candidates.any { it.text?.contains("old note") == true } })
        val r = replayer()
        r.testAfterStep = { i -> if (i == 60) assertTrue(pins.write(listOf(place("new note")))) }
        val rows = r.replay(LocalDate.of(2026, 11, 18), 15, emptyList())
        assertTrue("the text built from the old note is not returned", rows.all { s -> s.candidates.none { "pin:$pin" in it.sourceIds } })
        ctx.getSharedPreferences("replay_pin_test", Context.MODE_PRIVATE).edit().clear().commit()
        Unit
    }

    @Test fun extraEventsAreStepsOfTheirOwn() {
        val steps = replayer().steps(LocalDate.of(2026, 11, 18), 60, listOf(ReplayStep(noon + 7 * 60_000L, TriggerKind.PLACE_ARRIVAL), ReplayStep(noon + 5 * DAY, TriggerKind.TICK)))
        assertEquals(25, steps.size); assertTrue(steps.any { it.trigger == TriggerKind.PLACE_ARRIVAL })
    }
}
