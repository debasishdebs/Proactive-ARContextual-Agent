package com.rokid.context.orchestration.nudge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NudgeEngineTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 10, 12, 18, 0).atZone(zone).toInstant().toEpochMilli()
    private var hud: NudgeHud? = NudgeHud(null, Verbosity.MEDIUM, zone)
    private var cap = 6
    private var calls = 0
    private val prompts = mutableListOf<String>()
    private var reply = """{"shouldNudge":true,"text":"Milk is nearly out","why":"You saw an empty carton","sourceIds":["rokid_mem_1"],"priority":"normal"}"""
    private val proposed = mutableListOf<Pair<ProposalOrigin, String>>()
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = TestAi.graph(ctx) { p -> calls++; prompts += p; if (reply == "THROW") throw java.io.IOException("down") else reply }
        TestAi.bindAll(ai)
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun engine(): NudgeEngine {
        val mem = MemoryGraph.get(ctx)
        val sources = ContextSourceProvider { q, _ -> if (q == null) listOf("event \u00b7 Dentist \u00b7 Mon 19:00\u201319:30") else emptyList() }
        return NudgeEngine(
            NudgeInputs(mem, sources) { zone }, ai.runner, NudgeGates(mem.db.nudgeGateDao()) { cap }, mem,
            ProposalIntake { o, id, _, _ -> proposed += o to id; "p1" }, clock = { now }, hud = { hud },
        )
    }

    private suspend fun memory(at: Long = now - 3_600_000L) =
        MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, at, "Milk carton nearly empty"))

    private suspend fun rows() = MemoryGraph.get(ctx).nudges.recent(10).first()

    @Test fun passStoresTheNudgeWithInputsAsData() = runBlocking {
        memory()
        val run = engine().runOnce()
        val row = rows().single()
        assertEquals(NudgeRun.Shown(row.id), run)
        assertTrue(row.id.startsWith("nudge_"))
        assertEquals("Milk is nearly out", row.text)
        assertEquals("glance", row.shownOn)
        assertEquals(NudgeGates.hash("Milk is nearly out"), row.hash)
        val p = prompts.single()
        assertTrue(p, p.contains("BEGIN_DATA memories") && p.contains("id=rokid_mem_1 \u00b7 17:00 \u00b7 Milk carton nearly empty"))
        assertTrue(p, p.contains("BEGIN_DATA sources") && p.contains("Dentist"))
        assertTrue(p, p.contains("BEGIN_DATA recent_nudges"))
    }

    @Test fun quietHoursDropsNudge() = runBlocking {
        memory()
        hud = NudgeHud(QuietHours(17 * 60, 19 * 60), Verbosity.MAX, zone)
        assertEquals(NudgeRun.Dropped(Gate.QUIET_HOURS), engine().runOnce())
        assertTrue(rows().isEmpty())
    }

    @Test fun capZeroIsOff() = runBlocking {
        memory()
        cap = 0
        assertEquals(NudgeRun.Dropped(Gate.OFF), engine().runOnce())
        assertEquals(0, calls)
    }

    @Test fun shownOnFollowsVerbosity() = runBlocking {
        memory()
        hud = NudgeHud(null, Verbosity.MAX, zone)
        engine().runOnce()
        assertEquals("toast", rows().single().shownOn)
        hud = NudgeHud(null, Verbosity.SILENT, zone)
        reply = reply.replace("Milk is nearly out", "Buy milk on the way home")
        engine().runOnce()
        assertEquals("phone", rows().first { it.text.startsWith("Buy") }.shownOn)
    }

    @Test fun recentNudgesAreSentAndDuplicatesDropped() = runBlocking {
        memory()
        engine().runOnce()
        assertEquals(NudgeRun.Dropped(Gate.DUPLICATE), engine().runOnce())
        assertTrue(prompts.last(), prompts.last().contains("BEGIN_DATA recent_nudges\n- Milk is nearly out"))
        assertEquals(1, rows().size)
    }

    @Test fun proposedActionGoesToIntakeWithNudgeOrigin() = runBlocking {
        memory()
        reply = reply.dropLast(1) + ""","proposedAction":{"type":"SHOPPING_ADD","items":[{"name":"milk"}]}}"""
        val run = engine().runOnce() as NudgeRun.Shown
        assertEquals(listOf(ProposalOrigin.NUDGE to run.nudgeId), proposed)
    }

    @Test fun noRecentMemoriesSkipsModel() = runBlocking {
        memory(at = now - 7 * 3_600_000L) // older than 6 h
        assertEquals(NudgeRun.NoMemories, engine().runOnce())
        assertEquals(0, calls)
    }

    @Test fun shouldNudgeFalseStoresNothing() = runBlocking {
        memory()
        reply = """{"shouldNudge":false,"text":"","why":"","sourceIds":[],"priority":"low"}"""
        assertEquals(NudgeRun.NotNeeded, engine().runOnce())
        assertTrue(rows().isEmpty())
    }

    @Test fun unknownHudSkipsRun() = runBlocking {
        memory()
        hud = null
        assertEquals(NudgeRun.NoHud, engine().runOnce())
        assertEquals(0, calls)
    }

    @Test fun modelFailureIsTracedNotThrown() = runBlocking {
        memory()
        reply = "THROW"
        assertEquals(NudgeRun.Failed, engine().runOnce())
        assertEquals("NUDGE_FAILED", MemoryGraph.get(ctx).traces.recent(5).single().state)
        assertTrue(rows().isEmpty())
    }
}
