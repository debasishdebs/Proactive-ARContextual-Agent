package com.rokid.context.canvas.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.db.CanvasCalibrationEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Slice 3a Task 27, the publish side: gates in front of the group machine, SHADOW possible without M1, a 60-minute TRIAL counted per `configHash`, one identity from compile to LIVE (S60). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CapturePlanPublisherTest {
    private lateinit var h: PlanHarness
    private val pipeline = PlanTestFixtures.captureHalf("Quiet")
    private lateinit var id: String
    private var version = 0
    private lateinit var hash: String

    @Before fun setUp() {
        h = PlanHarness(ApplicationProvider.getApplicationContext())
        val (i, v) = h.frozen(pipeline, ran = false); id = i; version = v
        hash = h.compileHash(pipeline)
    }
    @After fun tearDown() = h.close()

    private fun publish(mode: PlanMode) = runBlocking { h.plans.publish(id, version, mode) }
    private fun blocked(o: PlanOutcome) = (o as PlanOutcome.Blocked).reasons
    private fun recordM1() = runBlocking { h.m1.record(M1Evidence(h.now, "worn run")) }

    @Test fun nothingIsSentOrRecordedWhileAGateFails() {
        val o = publish(PlanMode.SHADOW)
        assertTrue(blocked(o).any { it.startsWith("Gate 3") })
        assertTrue(h.sent.isEmpty()); assertTrue(runBlocking { h.canvas.capturePlanPublishes() }.isEmpty()); assertTrue(runBlocking { h.store.all() }.isEmpty())
    }

    @Test fun shadowPublishesWithoutM1AndRecordsTheGroup() {
        h.addReplay(hash)
        assertNull(runBlocking { h.m1.get() })
        val o = publish(PlanMode.SHADOW) as PlanOutcome.Done
        assertEquals(PlanMode.SHADOW, h.sent.single().mode); assertNull(h.sent.single().trialMaxMs)
        assertEquals(hash, h.sent.single().hash)
        val row = runBlocking { h.canvas.capturePlanPublishes() }.single()
        assertEquals(o.publishId, row.id); assertEquals("capture_plan", row.kind); assertEquals("SHADOW", row.mode); assertEquals(hash, row.configHash); assertEquals(1L, row.gen); assertEquals("SENT", row.state)
        assertEquals(h.sent.single().planJson, row.planJson)
        assertEquals("SENT", runBlocking { h.store.get(row.groupId!!) }!!.state)
    }

    @Test fun theSentPlanCarriesTheReplaysP95AndTheSameHash() {
        h.addReplay(hash)
        publish(PlanMode.SHADOW)
        val plan = PlanJson.decodeStrict(h.sent.single().planJson!!).getOrThrow()
        assertEquals(mapOf("REST" to 3.0, "WALKING" to 6.0), plan.meta.expectOpensP95PerMin)
        assertEquals(hash, plan.configHash); assertEquals(hash, PlanJson.configHash(plan.config))
    }

    @Test fun trialIsRefusedWithoutM1AndNothingIsSent() {
        h.addReplay(hash)
        assertEquals(PlanEvidenceGates.G4_M1, blocked(publish(PlanMode.TRIAL)).single())
        assertTrue(h.sent.isEmpty())
        recordM1()
        publish(PlanMode.TRIAL)
        assertEquals(1, h.sent.size)
    }

    @Test fun trialSendsOneHourAndIsCountedPerConfigHash() {
        h.addReplay(hash); recordM1()
        repeat(3) { assertTrue(publish(PlanMode.TRIAL) is PlanOutcome.Done) }
        assertTrue(h.sent.all { it.mode == PlanMode.TRIAL && it.trialMaxMs == 3_600_000L })
        assertEquals(3, runBlocking { h.canvas.trialCount(hash) })
        assertEquals(PlanEvidenceGates.G4_TRIALS, blocked(publish(PlanMode.TRIAL)).single())
        assertEquals(3, h.sent.size)
        assertTrue("SHADOW is still possible", publish(PlanMode.SHADOW) is PlanOutcome.Done)
        val trialRow = runBlocking { h.canvas.capturePlanPublishes() }.last()
        assertNotNull(trialRow.trialEndsAt)
    }

    @Test fun liveIsNeverPublishedInCore() {
        h.addReplay(hash); recordM1()
        assertEquals(PlanEvidenceGates.G4_LIVE, blocked(publish(PlanMode.LIVE)).single())
        assertTrue(h.sent.isEmpty())
    }

    @Test fun aGraphWithNoCaptureHalfIsNotAPlan() {
        val (aid, av) = h.frozen(com.rokid.context.ai.canvas.DefaultPipelines.liveExtract().copy(name = "Async"), ran = false)
        assertEquals(listOf(CapturePlanPublisher.NO_CAPTURE_HALF), blocked(runBlocking { h.plans.publish(aid, av, PlanMode.SHADOW) }))
    }

    /** S60: compile -> replay -> TRIAL -> calibration import -> LIVE, one `configHash` on every piece of evidence; the meta fill never changes it. */
    @Test fun configHashStableFromReplayToLive() {
        val compiled = h.compileHash(pipeline)                                                 // 1. compile
        val replayId = h.addReplay(compiled)                                                  // 2. replay
        assertEquals(compiled, runBlocking { h.dao.replay(replayId)!!.configHash })
        recordM1()
        val trial = publish(PlanMode.TRIAL) as PlanOutcome.Done                               // 3. TRIAL (the plan carries the meta filled from the replay)
        assertEquals(compiled, trial.configHash)
        val sentPlan = PlanJson.decodeStrict(h.sent.single().planJson!!).getOrThrow()
        assertNotNull(sentPlan.meta.expectOpensP95PerMin)
        assertEquals(compiled, sentPlan.configHash); assertEquals(compiled, PlanJson.configHash(sentPlan.config)); assertEquals(compiled, h.sent.single().hash)
        h.effective()
        val group = runBlocking { h.store.get(trial.groupId!!) }!!
        assertEquals("EFFECTIVE", group.state); assertEquals(compiled, group.configHash)
        assertEquals(compiled, runBlocking { h.canvas.capturePlanPublishes() }.single().configHash)
        runBlocking {                                                                         // 4. calibration import (Task 25 is later: the row is written as the import would)
            h.dao.insertCalibration(CanvasCalibrationEntity(configHash = compiled, settingsRev = 0, mode = "TRIAL", phasesJson = "[]", measuredJson = """{"pctPerMin":0.41}""", verified = true, createdAt = h.now))
            assertEquals(1, h.dao.verifiedCount(compiled)); assertEquals(compiled, h.dao.calibrations(compiled).single().configHash)
        }
        val live = runBlocking { h.gates.evaluate(pipeline, com.rokid.context.ai.canvas.plan.PlanCompiler.compile(pipeline), PlanMode.LIVE) }  // 5. LIVE: same hash, evidence found, refused only by the LA9 seam
        assertEquals(compiled, live.configHash); assertEquals(compiled, live.evidence!!.configHash)
        assertEquals(listOf(PlanEvidenceGates.G4_LIVE), live.reasons)
        assertEquals("a second publish of the same graph is the same identity", compiled, h.compileHash(PipelineJson.decode(PipelineJson.encode(pipeline)).getOrThrow()))
    }

    @Test fun anAckForAnotherConfigHashDoesNotActivate() {
        h.addReplay(hash)
        publish(PlanMode.SHADOW)
        runBlocking { h.groups.onAck(h.ack(PlanAckState.EFFECTIVE, hash = "ffffffffffff")) }
        assertEquals("SENT", runBlocking { h.store.all() }.single().state)
        assertTrue(h.phoneRt.isEmpty())
        h.effective(); assertFalse(h.phoneRt.isEmpty())
    }
}
