package com.rokid.context.canvas

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.plan.PlanShape
import com.rokid.context.canvas.plan.PlanEvidenceGates
import com.rokid.context.canvas.plan.PlanHarness
import com.rokid.context.canvas.plan.PlanOutcome
import com.rokid.context.canvas.plan.PlanTestFixtures
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.db.CanvasPipelineVersionEntity
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WP7 (S6, part B P15): `py.fn` and every `tool.`-prefixed node can never be published, on every path that can publish, with the fixed text [PlanShape.S6_REFUSAL]: the ONLY reason on the `CanvasPublisher` paths, one of the reasons (beside gates 3, 5 ...) on the capture-plan paths.
 * Versions are inserted straight into the table (the stored-row threat: a version the editor could not freeze must still be refused), so the tests do not depend on the validator.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PublishRefusesPyFnTest {
    private lateinit var h: PlanHarness
    private var ids = 0
    @Before fun setUp() { h = PlanHarness(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() = h.close()

    private fun withNode(p: Pipeline, type: String) = p.copy(nodes = p.nodes + PipelineNode("t3", type, "t3", JsonObject(emptyMap())))

    /** Stores [p] as a frozen version (and a successful run of its ASYNC projection so no other gate can be the reason). Returns id and version. */
    private fun stored(p: Pipeline, ran: Boolean = true): Pair<String, Int> = runBlocking {
        val id = h.repo.duplicate("live-extract", null, p.name)
        val json = PipelineJson.encode(p.copy(id = id))
        h.canvas.insertVersion(CanvasPipelineVersionEntity(id, 1, json, PipelineHash.of(p.copy(id = id)), "n", h.now++))
        if (ran) {
            val hash = PipelineHash.of(PlanShape.asyncProjection(p.copy(id = id)))
            h.canvas.insertRun(CanvasRunEntity("r${++ids}", "x", 1, hash, "{}", "DONE", 1, 1, 1, 0, 1, 2, "[]"))
            h.canvas.upsertItem(CanvasRunItemEntity("r$ids", "f1", "extract", "OK", null, null, null, 1, null, null, null, null, null))
        }
        id to 1
    }

    private val fixed = listOf(PlanShape.S6_REFUSAL)
    private fun noRows() = assertTrue("nothing was published", runBlocking { h.canvas.activePublishes() }.isEmpty())

    @Test fun refusalReasonIsTheFixedText() {
        assertEquals(PlanShape.S6_REFUSAL, PlanEvidenceGates.G2)
        assertEquals("Gate 2: the graph has a tier-3 node, and generated or laptop-run code never publishes.", PlanShape.S6_REFUSAL)
    }

    @Test fun publishInterpreterRefusesPyFnToolDraftAndAnyToolPrefix() {
        for (type in listOf("py.fn", "tool.draft", "tool.generated", "tool.anything")) for (mode in LiveMode.entries) {
            val (id, v) = stored(withNode(DefaultPipelines.liveExtract(), type))
            assertEquals("$type $mode", CanvasPublisher.Outcome.Blocked(fixed), runBlocking { h.publisher.publishInterpreter(id, v, mode) })
        }
        noRows()
    }

    /** The legacy prompt publish: the S6 text, not the shape text, even though the graph is not Live-shaped. */
    @Test fun publishLegacyRefusesTier3() {
        for (type in listOf("py.fn", "tool.draft")) {
            val (id, v) = stored(withNode(DefaultPipelines.liveExtract(), type))
            assertEquals(type, CanvasPublisher.Outcome.Blocked(fixed), runBlocking { h.publisher.publish(id, v) })
        }
        noRows()
    }

    /** A py.fn node that the ASYNC projection keeps (it is not RT) is still refused: the guard runs on the FULL graph before the projection. */
    @Test fun tier3GuardRunsBeforeProjection() {
        val full = withNode(PlanTestFixtures.full("Full"), "py.fn")
        assertTrue("the projection keeps the node", PlanShape.asyncProjection(full).nodes.any { it.id == "t3" })
        val (id, v) = stored(full)
        assertEquals(CanvasPublisher.Outcome.Blocked(fixed), runBlocking { h.publisher.publishInterpreter(id, v, LiveMode.SHADOW) })
        noRows()
    }

    @Test fun capturePlanPublishRefusesTier3() {
        for (type in listOf("py.fn", "tool.draft")) {
            val p = withNode(PlanTestFixtures.captureHalf("Cap"), type)
            val (id, v) = stored(p, ran = false)
            val o = runBlocking { h.plans.publish(id, v, PlanMode.SHADOW) } as PlanOutcome.Blocked
            assertTrue(type, PlanShape.S6_REFUSAL in o.reasons)
        }
        assertTrue(h.sent.isEmpty()); assertTrue(runBlocking { h.canvas.capturePlanPublishes() }.isEmpty())
    }

    @Test fun checkReportsS6Reason() {
        val (id, v) = stored(withNode(PlanTestFixtures.captureHalf("Cap"), "py.fn"), ran = false)
        val r = runBlocking { h.plans.check(id, v, PlanMode.SHADOW) }!!
        assertTrue(r.reasons.toString(), PlanShape.S6_REFUSAL in r.reasons)
    }

    /** A full graph (capture half + ASYNC half) with py.fn: the capture-plan path refuses, so no group exists for the activation path to publish the ASYNC half of. */
    @Test fun fullGraphWithPyFnPublishesNothingOnEitherHalf() {
        val (id, v) = stored(withNode(PlanTestFixtures.full("Full"), "py.fn"))
        val o = runBlocking { h.plans.publish(id, v, PlanMode.SHADOW) } as PlanOutcome.Blocked
        assertTrue(PlanShape.S6_REFUSAL in o.reasons)
        assertTrue(h.sent.isEmpty()); noRows()
    }

    /** Fix 1: an ASYNC-only graph (no capture half) with py.fn or tool.* gets the fixed S6 text on the capture-plan path, not "no capture half". */
    @Test fun asyncOnlyTier3GraphGetsTheFixedS6TextOnTheCapturePlanPath() {
        for (type in listOf("py.fn", "tool.draft")) {
            val p = withNode(DefaultPipelines.liveExtract().copy(name = "Async $type"), type)
            assertTrue("no capture half", !PlanShape.hasCaptureHalf(p))
            val (id, v) = stored(p)
            val o = runBlocking { h.plans.publish(id, v, PlanMode.SHADOW) } as PlanOutcome.Blocked
            assertEquals(type, fixed, o.reasons)
        }
        assertTrue(h.sent.isEmpty()); noRows()
    }

    /** An ASYNC-only graph without tier 3 still says it has no capture half. */
    @Test fun asyncOnlyPlainGraphStillSaysNoCaptureHalf() {
        val (id, v) = stored(DefaultPipelines.liveExtract().copy(name = "Plain"))
        val o = runBlocking { h.plans.publish(id, v, PlanMode.SHADOW) } as PlanOutcome.Blocked
        assertEquals(listOf(com.rokid.context.canvas.plan.CapturePlanPublisher.NO_CAPTURE_HALF), o.reasons)
    }

    /** A capture_plan group (publish row, group state and plan source) over the stored version, as a glasses-accepted publish leaves it, through the real groups. */
    private fun capturePlanGroup(id: String, v: Int, gid: String) = runBlocking {
        val json = "{\"p\":1}"
        h.canvas.insertPublish(com.rokid.context.memory.db.CanvasPublishEntity(
            pipelineId = id, version = v, hash = "x", publishedAt = h.now++, rolledBackAt = null, snapshotJson = "{}", kind = CanvasPublisher.KIND_CAPTURE_PLAN,
            mode = PlanMode.SHADOW.name, groupId = gid, component = com.rokid.context.canvas.plan.PlanPublishGroups.COMPONENT, planJson = json, configHash = "cfg", gen = null, state = "DESIRED", trialEndsAt = null,
        ))
        h.groups.publish(gid, 1, PlanMode.SHADOW, json, "cfg", null)
        json
    }
    private fun interpreterRows(gid: String) = runBlocking { h.canvas.publishesOfGroup(gid) }.filter { it.kind == CanvasPublisher.KIND_INTERPRETER }

    /** Activation (PlanActivation.activate, called by the groups on EFFECTIVE) of a tier-3 version publishes no interpreter row; the same fixture without tier 3 does (control). */
    @Test fun activationThroughPlanActivationPublishesNoInterpreterForTier3() {
        val (id, v) = stored(withNode(PlanTestFixtures.full("Full"), "py.fn"))
        val json = capturePlanGroup(id, v, "g1")
        runBlocking { h.activation.activate("g1", json) }
        assertTrue("no interpreter row", interpreterRows("g1").isEmpty())
        noInterpreterAnywhere()
    }

    @Test fun activationControlPublishesTheInterpreterRowWithoutTier3() {
        val (id, v) = stored(PlanTestFixtures.full("Full"))
        val json = capturePlanGroup(id, v, "g1")
        runBlocking { h.activation.activate("g1", json) }
        assertEquals("the fixture can publish, so the tier-3 test above is meaningful", 1, interpreterRows("g1").size)
    }

    private fun noInterpreterAnywhere() = assertTrue(runBlocking { h.canvas.activePublishes() }.none { it.kind == CanvasPublisher.KIND_INTERPRETER })

    /** EFFECTIVE ack, then a restart's recover() (EFFECTIVE activates again): neither makes an interpreter row for a tier-3 version. */
    @Test fun effectiveAckAndRecoveryPublishNoInterpreterForTier3() {
        val (id, v) = stored(withNode(PlanTestFixtures.full("Full"), "py.fn"))
        capturePlanGroup(id, v, "g1")
        h.effective()
        assertEquals("EFFECTIVE", runBlocking { h.store.get("g1") }!!.state)
        assertTrue(interpreterRows("g1").isEmpty())
        runBlocking { h.groups.recover() }
        assertTrue("recovery of an EFFECTIVE group", interpreterRows("g1").isEmpty())
        noInterpreterAnywhere()
    }

    @Test fun effectiveAckAndRecoveryControlPublishOneInterpreterRowWithoutTier3() {
        val (id, v) = stored(PlanTestFixtures.full("Full"))
        capturePlanGroup(id, v, "g1")
        h.effective()
        assertEquals(1, interpreterRows("g1").size)
        runBlocking { h.groups.recover() }
        assertEquals("recovery is idempotent", 1, interpreterRows("g1").size)
    }

    /** An untouched Live extraction still publishes (the guard has no false positive). */
    @Test fun aGraphWithoutTier3NodesIsNotRefusedByTheGuard() {
        val (id, v) = stored(DefaultPipelines.liveExtract().copy(name = "Plain"))
        val out = runBlocking { h.publisher.publishInterpreter(id, v, LiveMode.SHADOW) }
        assertTrue(out.toString(), out is CanvasPublisher.Outcome.Published)
    }
}
