package com.rokid.context.canvas.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.plan.CompileResult
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.capture.replay.ReplayFigures
import com.rokid.context.capture.replay.ReplayReport
import com.rokid.context.capture.replay.StateFigures
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.db.CanvasCalibrationEntity
import com.rokid.context.memory.db.CanvasPublishEntity
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Slice 3a Task 27: the five `capture_plan` gates (S11, S60, S75). The evidence is produced by the REAL [ReplayService] over fake traces (so the rows are the ones a device run writes),
 * and the gates read it back from the real tables.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlanEvidenceGatesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.canvasSlice3Dao()
    private val canvas = db.canvasDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val epoch = DeletionEpoch("install", 1, 0)
    private var now = 1_000_000L
    private var glasses = GlassesState.LINKED_CAPABLE
    private val m1 = DaoM1EvidenceStore(dao)
    private val gates = PlanEvidenceGates(dao, m1, { glasses }, canvas)
    private val pipeline = PlanTestFixtures.captureHalf()
    private val compile: CompileResult = PlanCompiler.compile(pipeline)
    private val hash = compile.plan!!.configHash
    private val replayTraces = HashMap<String, ReplayTrace>()
    private fun fence() = DaoFence(1, epoch, { 1 }, { epoch })

    @After fun close() { scope.cancel(); db.close() }

    /** A fake trace whose replay says [minutes] minutes, the given per-state minutes and p95, and the given battery estimates (candidate vs recorded). */
    private fun fakeTrace(id: String, minutes: Double = 40.0, states: Map<String, StateFigures> = mapOf("REST" to StateFigures(25.0, 3.0), "WALKING" to StateFigures(15.0, 6.0)), cand: Double = 0.45, base: Double = 0.49, rev: Int = 0, status: String = "COMPLETE") {
        runBlocking { dao.acceptTraceFenced(CanvasTraceEntity(id, "IMU", status, "{}", 1, false, rev, null, epoch.installId, epoch.counter, true, 10_000, 20_000, 1), fence()) }
        fun fig(h: String, pct: Double) = ReplayFigures(h, 30, 1.0, 20.0, 0.5, 30.0, 1.0, 0, pct, 3.0)
        replayTraces[id] = object : ReplayTrace("0", rev, 10_000, 20_000) {
            override fun replay(candidate: ReplayCandidate?, candidateFusion: Map<String, Double>?, tick: () -> Unit) =
                ReplayReport(if (candidate == null) "EXACT" else "COUNTERFACTUAL", minutes, fig("0", base), candidate?.let { fig(it.configHash, cand) }, 0.0, 1, 0, 0, emptyMap(), false, emptyList(), states)
        }
    }

    private fun replayService() = ReplayService(dao, { id -> replayTraces[id] }, ::fence, scope, MutableStateFlow(true), null, { now }, { "rp_${now++}" })

    /** Runs a candidate replay of the plan over [traceIds] and waits until it is DONE. */
    private fun replay(vararg traceIds: String, candidateHash: String = hash): String = runBlocking {
        val id = replayService().start(ReplayRequest(traceIds.toList(), ReplayCandidate(candidateHash)))
        withTimeout(10_000) { while (dao.replay(id)!!.jobState == "RUNNING") delay(5) }
        assertEquals("DONE", dao.replay(id)!!.jobState)
        id
    }

    private fun evaluate(mode: PlanMode, p: Pipeline = pipeline, c: CompileResult = compile) = runBlocking { gates.evaluate(p, c, mode) }
    private fun gate(r: GateReport, n: Int) = r.gates.single { it.n == n }
    private fun recordM1() = runBlocking { m1.record(M1Evidence(now, "device run")) }

    // ---- gates 1, 2, 5 ----

    @Test fun gate1FailsWhenThePlanDoesNotCompile() {
        val bad = pipeline.copy(nodes = pipeline.nodes.map { if (it.id == "head") PipelineNode("head", NodeTypes.IMU_HEAD_MOTION, "head", kotlinx.serialization.json.buildJsonObject { put("onsetRadS", 1.1) }) else it })
        val c = PlanCompiler.compile(bad)
        assertNull(c.plan)
        val r = evaluate(PlanMode.SHADOW, bad, c)
        assertFalse(gate(r, 1).ok); assertEquals(PlanEvidenceGates.G1, gate(r, 1).text)
        assertTrue("the compiler's problems are listed", r.reasons.containsAll(c.problems) && c.problems.isNotEmpty())
        assertFalse("without a plan there is no evidence to look up", gate(r, 3).ok)
    }

    @Test fun gate2RefusesATier3Node() {
        val t3 = pipeline.copy(nodes = pipeline.nodes + PipelineNode("fn", "py.fn", "fn", JsonObject(emptyMap())))
        val r = evaluate(PlanMode.SHADOW, t3, compile)
        assertFalse(gate(r, 2).ok); assertEquals(PlanEvidenceGates.G2, gate(r, 2).text)
        assertTrue(gate(evaluate(PlanMode.SHADOW), 2).ok)
    }

    @Test fun gate5NeedsTheGlassesLinkedAndCapable() {
        fakeTrace("t1"); replay("t1")
        for (s in GlassesState.entries) {
            glasses = s
            assertEquals("$s", s == GlassesState.LINKED_CAPABLE, gate(evaluate(PlanMode.SHADOW), 5).ok)
        }
        glasses = GlassesState.TOO_OLD
        assertEquals(PlanEvidenceGates.G5, gate(evaluate(PlanMode.SHADOW), 5).text)
    }

    // ---- gate 3 ----

    @Test fun gate3NeedsAFinishedReplayOfThisPlan() {
        assertFalse(gate(evaluate(PlanMode.SHADOW), 3).ok)
        fakeTrace("t1"); replay("t1", candidateHash = "ffffffffffff")                      // a replay of ANOTHER config is no evidence (S60)
        assertFalse(gate(evaluate(PlanMode.SHADOW), 3).ok)
        replay("t1")
        val r = evaluate(PlanMode.SHADOW)
        assertTrue(r.reasons.toString(), r.ok); assertEquals(40.0, r.evidence!!.minutes, 1e-9); assertEquals(hash, r.evidence!!.configHash)
    }

    @Test fun gate3NeedsThirtyMinutesAndTwoStates() {
        fakeTrace("short", minutes = 29.0); replay("short")
        assertTrue(gate(evaluate(PlanMode.SHADOW), 3).text.contains("needs 30 minutes of traces, has 29"))
        fakeTrace("a", minutes = 15.0); fakeTrace("b", minutes = 15.0); replay("a", "b")        // two traces together reach 30
        assertTrue(gate(evaluate(PlanMode.SHADOW), 3).ok)
    }

    @Test fun gate3NeedsTwoStatesCovered() {
        fakeTrace("one", states = mapOf("REST" to StateFigures(40.0, 2.0), "WALKING" to StateFigures(0.5, 9.0))); replay("one")      // 30 s of walking is not a covered state
        assertTrue(gate(evaluate(PlanMode.SHADOW), 3).text.contains("needs 2 states, has 1"))
    }

    /** S75: evidence whose sources or labels changed is stale. */
    @Test fun staleSourcesFailGate3() {
        fakeTrace("t1"); replay("t1")
        assertTrue(gate(evaluate(PlanMode.SHADOW), 3).ok)
        runBlocking { dao.bumpLabelRev() }                                                    // a label changed after the replay
        assertTrue(gate(evaluate(PlanMode.SHADOW), 3).text.contains("labels changed"))
        replay("t1"); assertTrue("a new replay under the new labelRev is current", gate(evaluate(PlanMode.SHADOW), 3).ok)
        runBlocking { dao.tombstoneTrace("t1", now) }                                         // the source was forgotten
        assertFalse(gate(evaluate(PlanMode.SHADOW), 3).ok)
        fakeTrace("t2"); replay("t2")
        runBlocking { dao.acceptTraceFenced(CanvasTraceEntity("t2", "IMU", "COMPLETE", "{}", 1, false, 1, null, epoch.installId, epoch.counter, true, 10_000, 20_000, 1), fence()) }  // rewritten: revision 1
        assertTrue(gate(evaluate(PlanMode.SHADOW), 3).text.contains("trace changed"))
        fakeTrace("t3", status = "PARTIAL"); replay("t3")
        assertTrue(gate(evaluate(PlanMode.SHADOW), 3).text.contains("not complete"))
    }

    @Test fun anInvalidatedReplayIsNotEvidence() {
        fakeTrace("t1"); replay("t1")
        runBlocking { dao.invalidateBySources(listOf("t1"), null, null, false) }
        assertFalse(gate(evaluate(PlanMode.SHADOW), 3).ok)
    }

    // ---- gate 4: S11 ----

    @Test fun shadowNeedsNoM1() {
        fakeTrace("t1"); replay("t1")
        assertNull(runBlocking { m1.get() })
        assertTrue(evaluate(PlanMode.SHADOW).ok)
    }

    @Test fun trialAndLiveAreRefusedUntilM1IsRecorded() {
        fakeTrace("t1"); replay("t1")
        assertEquals(PlanEvidenceGates.G4_M1, gate(evaluate(PlanMode.TRIAL), 4).text)
        assertEquals(PlanEvidenceGates.G4_M1, gate(evaluate(PlanMode.LIVE), 4).text)
        recordM1()
        assertTrue(evaluate(PlanMode.TRIAL).ok)
    }

    @Test fun anUnreadableM1RecordIsNotAPass() {
        runBlocking { dao.putMeta(DaoM1EvidenceStore.KEY, "not json") }
        assertNull(runBlocking { m1.get() })
        runBlocking { dao.putMeta(DaoM1EvidenceStore.KEY, """{"note":"x"}""") }
        assertNull(runBlocking { m1.get() })
        runBlocking { m1.record(M1Evidence(5, "ok")) }
        assertEquals(M1Evidence(5, "ok"), runBlocking { m1.get() })
    }

    @Test fun trialNeedsTheEstimateWithinTenPercentOfBaseline() {
        recordM1()
        fakeTrace("hot", cand = 0.54, base = 0.49); replay("hot")                              // 0.539 is the limit: 0.54 is over
        assertTrue(gate(evaluate(PlanMode.TRIAL), 4).text.startsWith("Gate 4: the replay estimates 0.54 %/min, more than 10 % above the baseline of 0.49"))
        fakeTrace("ok", cand = 0.539, base = 0.49); replay("ok")
        assertTrue(evaluate(PlanMode.TRIAL).ok)
    }

    @Test fun atMostThreeTrialsPerConfigHash() {
        recordM1(); fakeTrace("t1"); replay("t1")
        suspend fun trialRow(h: String) = canvas.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 1, hash = "v", publishedAt = 1, rolledBackAt = 5, snapshotJson = "{}", kind = "capture_plan", mode = "TRIAL", groupId = "g$h${now++}", component = "capture_plan", configHash = h))
        runBlocking { trialRow(hash); trialRow(hash); trialRow("other") }                      // a trial of ANOTHER config does not count; a rolled-back trial does
        assertTrue(evaluate(PlanMode.TRIAL).ok)
        runBlocking { trialRow(hash) }
        assertEquals(PlanEvidenceGates.G4_TRIALS, gate(evaluate(PlanMode.TRIAL), 4).text)
        assertTrue("SHADOW is not a trial", evaluate(PlanMode.SHADOW).ok)
    }

    /** LA9 (the verified LIVE gate) is later: in core, LIVE is refused whatever the calibration rows say. The seam is [LiveEvidence]. */
    @Test fun liveIsRefusedInCoreEvenWithAVerifiedCalibrationAtTheTarget() {
        recordM1(); fakeTrace("t1"); replay("t1")
        for (measured in listOf(0.43, 0.42)) runBlocking {
            dao.insertCalibration(CanvasCalibrationEntity(configHash = hash, settingsRev = 0, mode = "TRIAL", phasesJson = "[]", measuredJson = """{"pctPerMin":$measured}""", verified = true, createdAt = now))
            assertEquals(PlanEvidenceGates.G4_LIVE, gate(gates.evaluate(pipeline, compile, PlanMode.LIVE), 4).text)
        }
        val granted = PlanEvidenceGates(dao, m1, { glasses }, canvas, live = { it == hash })
        assertTrue("the seam is where LA9 plugs in", runBlocking { granted.evaluate(pipeline, compile, PlanMode.LIVE) }.ok)
    }

    // ---- the plan's meta (S60) ----

    @Test fun gate3EvidenceFillsTheExpectedOpensP95WithoutChangingTheHash() {
        fakeTrace("t1", states = mapOf("REST" to StateFigures(25.0, 3.0), "WALKING" to StateFigures(15.0, 6.004)))
        fakeTrace("t2", states = mapOf("REST" to StateFigures(25.0, 4.2), "WALKING" to StateFigures(15.0, 2.0)))
        replay("t1", "t2")
        val ev = evaluate(PlanMode.SHADOW).evidence!!
        assertEquals(mapOf("REST" to 4.2, "WALKING" to 6.0), ev.opensP95PerMin)              // the highest of the traces, rounded to 2 decimals
        val filled = PlanEvidenceGates.fillMeta(compile.plan!!, ev.opensP95PerMin)
        assertEquals(ev.opensP95PerMin, filled.meta.expectOpensP95PerMin)
        assertEquals(hash, filled.configHash)
    }

    @Test fun metaFillDoesNotChangeHash() {
        val plan = compile.plan!!
        val filled = PlanEvidenceGates.fillMeta(plan, mapOf("REST" to 3.0, "WALKING" to 9.5))
        assertEquals(plan.configHash, filled.configHash)
        assertEquals(plan.configHash, PlanJson.configHash(filled.config))
        val decoded = PlanJson.decodeStrict(PlanJson.encode(filled)).getOrThrow()
        assertEquals(plan.configHash, decoded.configHash); assertEquals(plan.configHash, PlanJson.configHash(decoded.config))
        assertEquals(mapOf("REST" to 3.0, "WALKING" to 9.5), decoded.meta.expectOpensP95PerMin)
        assertNotNull(decoded.meta.expectOpensP95PerMin)
        // a different meta, the same identity; a different config, a different identity
        assertEquals(PlanEvidenceGates.fillMeta(plan, mapOf("REST" to 1.0)).configHash, filled.configHash)
        val other = PlanCompiler.compile(pipeline.copy(nodes = pipeline.nodes.map { if (it.id == "settle") PipelineNode("settle", NodeTypes.IMU_SETTLE, "settle", kotlinx.serialization.json.buildJsonObject { put("settleMs", 2500) }) else it })).plan!!
        assertTrue(other.configHash != plan.configHash)
    }

}
