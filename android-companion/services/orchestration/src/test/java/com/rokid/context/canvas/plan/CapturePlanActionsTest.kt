package com.rokid.context.canvas.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.canvas.plan.asyncProjection
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.LiveMode
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.db.CanvasPublishEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Slice 3a Task 28: the S26 actions, the group states, and the rows the Settings and Diagnostics screens show (S12), over the real tables, the real publishers and a recording transport. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CapturePlanActionsTest {
    private lateinit var h: PlanHarness
    private val full = PlanTestFixtures.full("Quiet walk")
    private lateinit var fullId: String
    private var fullVersion = 0
    private val hashA get() = h.compileHash(full)

    @Before fun setUp() {
        h = PlanHarness(ApplicationProvider.getApplicationContext())
        val (i, v) = h.frozen(full); fullId = i; fullVersion = v
        h.addReplay(hashA)
    }
    @After fun tearDown() = h.close()

    private fun publish(mode: PlanMode = PlanMode.SHADOW, id: String = fullId, version: Int = fullVersion) = runBlocking { h.plans.publish(id, version, mode) }
    private fun done(o: PlanOutcome) = o as PlanOutcome.Done
    private fun blocked(o: PlanOutcome) = (o as PlanOutcome.Blocked).reasons
    private fun active() = runBlocking { h.canvas.activePublishes() }
    private fun all() = runBlocking { h.canvas.publishHistory(100) }
    private fun recordM1() = runBlocking { h.m1.record(M1Evidence(h.now, "worn run")) }
    private fun status() = runBlocking { h.plans.status() }
    private fun undo(id: Long) = runBlocking { h.plans.undoLatest(id) }
    private fun row(id: Long): CanvasPublishEntity = runBlocking { h.canvas.publishRow(id)!! }

    /** A second distinct plan of the same graph shape (a different settle time, so another `configHash`), with its own replay evidence. */
    private fun secondPlan(): Pair<String, Int> {
        val p = PlanTestFixtures.captureHalf("Other", settleMs = 2500)
        h.addReplay(h.compileHash(p))
        return h.frozen(p, ran = false)
    }

    // ---- S25: the ASYNC half activates only after the glasses say EFFECTIVE ----

    @Test fun theAsyncHalfAndThePhoneRtActivateOnlyAfterEffective() {
        val o = done(publish())
        assertTrue("nothing activated yet", h.phoneRt.isEmpty() && active().none { it.kind == CanvasPublisher.KIND_INTERPRETER })
        assertEquals("SENT", status().active!!.state)
        h.effective()
        assertEquals(listOf(h.sent.single().planJson), h.phoneRt)
        val async = active().single { it.kind == CanvasPublisher.KIND_INTERPRETER }
        assertEquals(o.groupId, async.groupId); assertEquals("SHADOW", async.mode)
        assertEquals("the ASYNC half is published as its projection: slice 2's hash", PipelineHash.of(com.rokid.context.ai.canvas.PipelineJson.decode(runBlocking { h.canvas.version(fullId, fullVersion)!!.json }).getOrThrow().asyncProjection()), async.hash)
        assertEquals(setOf(CanvasPublisher.KIND_CAPTURE_PLAN, CanvasPublisher.KIND_INTERPRETER), active().map { it.kind }.toSet())
    }

    @Test fun aRejectedPlanNeverTouchesThePhoneOrTheAsyncHalf() {
        done(publish())
        runBlocking { h.groups.onAck(h.ack(PlanAckState.REJECTED, reason = "bounds")) }
        assertEquals("FAILED", runBlocking { h.store.all() }.single().state)
        assertTrue(h.phoneRt.isEmpty()); assertTrue(active().none { it.kind == CanvasPublisher.KIND_INTERPRETER })
    }

    @Test fun anUntestedAsyncHalfLeavesItOffAndSaysSo() {
        val (id, v) = h.frozen(PlanTestFixtures.full("Untested", settleMs = 2000), ran = false)
        h.addReplay(h.compileHash(PlanTestFixtures.full("Untested", settleMs = 2000)))
        done(publish(id = id, version = v)); h.effective()
        assertTrue(active().none { it.kind == CanvasPublisher.KIND_INTERPRETER })
        assertEquals("ASYNC: none", status().rows.last())
        assertEquals("EFFECTIVE", status().active!!.state)                                  // the glasses half is live; the ASYNC publish is simply not
    }

    // ---- S26: Use settings ----

    @Test fun useSettingsDisablesTheCaptureOverridesAndLeavesAsyncAlone() {
        done(publish()); h.effective()
        val planRow = active().single { it.kind == CanvasPublisher.KIND_CAPTURE_PLAN }
        val asyncRow = active().single { it.kind == CanvasPublisher.KIND_INTERPRETER }
        val o = done(runBlocking { h.plans.useSettings() })
        assertEquals(PlanMode.SETTINGS, o.mode)
        val s = h.sent.last(); assertEquals(PlanMode.SETTINGS, s.mode); assertNull(s.planJson); assertNull(s.hash)
        assertEquals("use_settings", row(planRow.id).rollbackReason); assertNotNull(row(planRow.id).rolledBackAt)
        assertNull("ASYNC is untouched", row(asyncRow.id).rolledBackAt)
        assertNull(status().active)
        h.effective()                                                                       // the glasses ack SETTINGS: the phone RT is switched off too
        assertNull(h.phoneRt.last())
    }

    // ---- S26: Undo latest publish ----

    @Test fun undoLatestUndoesTheWholeGroupAsyncFirstThenTheGlasses() {
        done(publish()); h.effective()
        val async = active().single { it.kind == CanvasPublisher.KIND_INTERPRETER }
        assertEquals("the newest active publish is the group's ASYNC row", async.id, active().first().id)
        val out = undo(async.id)
        assertTrue(out.toString(), out is CanvasPublisher.Outcome.Published)
        val plan = all().single { it.kind == CanvasPublisher.KIND_CAPTURE_PLAN }
        assertNotNull(row(async.id).rolledBackAt); assertEquals("undo", row(plan.id).rollbackReason)
        assertTrue("ASYNC went before the plan row", row(async.id).rolledBackAt!! < row(plan.id).rolledBackAt!!)
        assertEquals(PlanMode.SETTINGS, h.sent.last().mode)                                  // nothing earlier: back to settings
        assertTrue(active().isEmpty())
    }

    @Test fun aRefusedUndoChangesNothing() {
        done(publish()); h.effective()
        val (id2, v2) = secondPlan()
        done(publish(id = id2, version = v2)); h.effective()
        val before = active().map { it.id }; val sentBefore = h.sent.size; val stateBefore = status().active!!.state
        h.glasses = GlassesState.NOT_LINKED
        val out = undo(active().first().id)
        assertTrue(out.toString(), out is CanvasPublisher.Outcome.Blocked)
        assertEquals("every publish row is still active", before, active().map { it.id })
        assertEquals("nothing was sent", sentBefore, h.sent.size); assertEquals(stateBefore, status().active!!.state)
        assertTrue(all().none { it.rollbackReason == "undo" || it.rollbackReason == "reinstated" })
        h.glasses = GlassesState.LINKED_CAPABLE
        assertTrue("the same undo works once the glasses are back", undo(active().first().id) is CanvasPublisher.Outcome.Published)
    }

    @Test fun undoRevealsThePreviousActivePlanInItsOwnMode() {
        done(publish()); h.effective()
        val (id2, v2) = secondPlan()
        done(publish(id = id2, version = v2)); h.effective()
        assertEquals(2, h.sent.size)
        val newest = active().first()
        undo(newest.id)
        val back = h.sent.last()
        assertEquals(PlanMode.SHADOW, back.mode); assertEquals(hashA, back.hash)             // the first plan is desired again
        val rows = active().filter { it.kind == CanvasPublisher.KIND_CAPTURE_PLAN }
        assertEquals(1, rows.size); assertEquals(hashA, rows.single().configHash)
        assertEquals("the second plan's row was undone", "undo", all().first { it.configHash == h.compileHash(PlanTestFixtures.captureHalf("Other", settleMs = 2500)) }.rollbackReason)
        assertEquals("reinstated", all().first { it.rollbackReason == "reinstated" }.rollbackReason)
    }

    @Test fun undoNamesTheNewestPublishItSawOrDoesNothing() {
        done(publish()); h.effective()
        val plan = active().single { it.kind == CanvasPublisher.KIND_CAPTURE_PLAN }
        val before = h.sent.size
        val stale = undo(plan.id)                                                           // the plan row is not the newest (its ASYNC row is)
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("A newer publish exists. Reload and try again.")), stale)
        assertEquals(CanvasPublisher.Outcome.Blocked(listOf("That publish was already rolled back.")), undo(9_999))
        assertEquals(before, h.sent.size); assertEquals(2, active().size)
    }

    @Test fun undoOfAPlainInterpreterPublishIsSliceTwosRollback() {
        val (id, v) = h.frozen(com.rokid.context.ai.canvas.DefaultPipelines.liveExtract().copy(name = "Plain"))
        val pub = runBlocking { h.publisher.publishInterpreter(id, v, LiveMode.SHADOW) } as CanvasPublisher.Outcome.Published
        assertTrue(undo(pub.publishId) is CanvasPublisher.Outcome.Published)
        assertTrue(active().isEmpty()); assertTrue(h.sent.isEmpty())
    }

    @Test fun undoRevealingATrialWithNoTrialLeftBecomesShadow() {
        recordM1()
        done(publish(PlanMode.TRIAL)); h.effective()
        val (id2, v2) = secondPlan()
        done(publish(id = id2, version = v2)); h.effective()
        runBlocking { repeat(2) { h.canvas.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 1, hash = "v", publishedAt = 1, rolledBackAt = 5, snapshotJson = "{}", kind = "capture_plan", mode = "TRIAL", groupId = "x$it", component = "capture_plan", configHash = hashA)) } }
        undo(active().first().id)
        assertEquals(PlanMode.SHADOW, h.sent.last().mode); assertNull(h.sent.last().trialMaxMs)
    }

    // ---- S26: Restore previous capture plan ----

    @Test fun restorePreviousSendsTheEarlierPlanInItsModeWithTheTrialReBounded() {
        recordM1()
        done(publish(PlanMode.TRIAL)); h.effective()
        val (id2, v2) = secondPlan()
        done(publish(id = id2, version = v2)); h.effective()
        val o = done(runBlocking { h.plans.restorePrevious() })
        assertEquals(PlanMode.TRIAL, o.mode)
        val s = h.sent.last(); assertEquals(PlanMode.TRIAL, s.mode); assertEquals(3_600_000L, s.trialMaxMs); assertEquals(hashA, s.hash)
        assertEquals("a restored TRIAL is a counted trial", 2, runBlocking { h.canvas.trialCount(hashA) })
        assertEquals("the plan it replaced is closed", "restore_previous", all().first { it.configHash != hashA && it.component == "capture_plan" }.rollbackReason)
    }

    @Test fun restorePreviousIsRefusedForATrialWhenNoneIsLeft() {
        recordM1()
        done(publish(PlanMode.TRIAL)); h.effective()
        runBlocking { repeat(2) { h.canvas.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 1, hash = "v", publishedAt = 1, rolledBackAt = 5, snapshotJson = "{}", kind = "capture_plan", mode = "TRIAL", groupId = "x$it", component = "capture_plan", configHash = hashA)) } }
        val (id2, v2) = secondPlan()
        done(publish(id = id2, version = v2)); h.effective()
        val before = h.sent.size
        assertEquals(listOf(PlanEvidenceGates.G4_TRIALS), blocked(runBlocking { h.plans.restorePrevious() }))
        assertEquals(before, h.sent.size)
    }

    @Test fun restorePreviousAfterUseSettingsBringsBackTheLastPlanAndSkipsRevertedOnes() {
        done(publish()); h.effective()
        runBlocking { h.plans.useSettings() }; h.effective()
        val o = done(runBlocking { h.plans.restorePrevious() })
        assertEquals(PlanMode.SHADOW, o.mode); assertEquals(hashA, h.sent.last().hash)
        // a plan the glasses reverted is never restored
        h.effective(); runBlocking { h.plans.useSettings() }; h.effective()
        val (id2, v2) = secondPlan()
        done(publish(id = id2, version = v2)); h.reverted(reason = "auto:ceiling")
        assertEquals("only the reverted plan is newer; the older one is restorable", hashA, done(runBlocking { h.plans.restorePrevious() }).configHash)
    }

    @Test fun restorePreviousWithNothingEarlierIsRefused() {
        assertEquals(listOf("There is no earlier capture plan to restore."), blocked(runBlocking { h.plans.restorePrevious() }))
        done(publish())
        assertEquals(listOf("There is no earlier capture plan to restore."), blocked(runBlocking { h.plans.restorePrevious() }))
    }

    @Test fun restoreNeedsTheGlassesLinked() {
        done(publish()); h.effective(); runBlocking { h.plans.useSettings() }
        h.glasses = GlassesState.NOT_LINKED
        assertEquals(listOf(PlanEvidenceGates.G5), blocked(runBlocking { h.plans.restorePrevious() }))
    }

    // ---- a glasses revert, a lost ack ----

    @Test fun aGlassesRevertDeactivatesTheAsyncHalfAndThePhoneRt() {
        recordM1()
        done(publish(PlanMode.TRIAL)); h.effective()
        assertEquals(2, active().size)
        h.reverted()
        assertTrue(active().isEmpty())
        assertEquals("plan_reverted", all().first { it.kind == CanvasPublisher.KIND_INTERPRETER }.rollbackReason)
        assertEquals("plan_reverted", all().first { it.kind == CanvasPublisher.KIND_CAPTURE_PLAN }.rollbackReason)
        assertNull("the phone RT follows what is still active: nothing, so settings", h.phoneRt.last())
        assertEquals("REVERTED", runBlocking { h.store.all() }.single().state)
        assertNull(status().active)
    }

    @Test fun aNewPublishCompensatesAnUnknownRemoteGroupFirst() {
        done(publish())
        h.now += PlanPublishGroups.UNKNOWN_AFTER_MS + 1; runBlocking { h.groups.tick() }
        assertEquals("UNKNOWN_REMOTE", runBlocking { h.store.all() }.single().state)
        val (id2, v2) = secondPlan()
        done(publish(id = id2, version = v2))
        assertEquals(listOf(PlanMode.SHADOW, PlanMode.SETTINGS, PlanMode.SHADOW), h.sent.map { it.mode })   // the compensation (a higher gen carrying the previous state) goes before the new plan
        assertTrue(h.sent[1].gen > h.sent[0].gen && h.sent[2].gen > h.sent[1].gen)
    }

    // ---- the rows (S12) ----

    @Test fun theSettingsRowAndTheDiagnosticsCardTellTheGroupStates() {
        assertNull(status().active); assertTrue(status().rows.isEmpty())
        done(publish())
        val s = status()
        assertEquals("Capture plan: Quiet walk v$fullVersion (shadow) · Use settings", s.active!!.settingsRow)
        assertEquals("Frozen in canvas plan v$fullVersion", s.active!!.frozenLabel)
        assertEquals(listOf("Glasses: SENT · gen 1 · shadow", "Phone RT: waiting for the glasses", "ASYNC: none"), s.rows)
        h.effective()
        assertEquals(listOf("Glasses: EFFECTIVE · gen 1 · shadow", "Phone RT: applied", "ASYNC: shadow"), status().rows)
        assertEquals(hashA, status().active!!.configHash)
    }

    @Test fun aReplayCandidateIsResolvedFromTheStoredPlanOfItsHashOnly() = runBlocking {
        val c = ReplayCandidates(h.canvas)
        val cand = c.forHash(hashA)!!
        assertEquals(hashA, cand.configHash); assertNotNull(cand.table); assertNotNull(cand.gate)
        assertNull("no stored plan has this hash", c.forHash("deadbeef"))
    }

    @Test fun aWindowPlanKeepsItsReplayCandidateAfterTheGlobalToleranceChanges() = runBlocking {
        val calm = """{"steps":[{"name":"calm","inputs":[],"nodes":[{"id":"w","op":"win","name":"stepHz","agg":"mean","sec":10},{"id":"k","op":"const","value":1.2},{"id":"c","op":"cmp","f":"<"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}],"out":"calm"}"""
        val base = PlanTestFixtures.captureHalf("Win")
        val guard = com.rokid.context.ai.canvas.PipelineNode("gw", com.rokid.context.ai.canvas.NodeTypes.EXPR_GUARD, "gw", kotlinx.serialization.json.buildJsonObject { put("graph", kotlinx.serialization.json.Json.parseToJsonElement(calm)) })
        val p = base.copy(
            nodes = base.nodes + guard,
            edges = base.edges.filterNot { it.from == "head" && it.to == "thresh" } + com.rokid.context.ai.canvas.PipelineEdge("head", "gw") + com.rokid.context.ai.canvas.PipelineEdge("gw", "thresh"),
        )
        h.frozen(p, ran = false)
        val published = com.rokid.context.ai.canvas.plan.PlanCompiler.compile(p, windowMinValidPct = 90).plan!!.configHash
        val other = com.rokid.context.ai.canvas.plan.PlanCompiler.compile(p, windowMinValidPct = 60).plan!!.configHash
        assertTrue("the tolerance is part of the plan hash", published != other)
        try {
            com.rokid.context.ai.canvas.plan.WindowTolerance.provider = { 60 }
            assertEquals(published, ReplayCandidates(h.canvas).forHash(published)!!.configHash)
            assertEquals(other, ReplayCandidates(h.canvas).forHash(other)!!.configHash)
        } finally { com.rokid.context.ai.canvas.plan.WindowTolerance.provider = { 90 } }
    }

    @Test fun aReplayCandidateIsFoundEvenWhenAnotherToleranceMakesTheCompileFail() = runBlocking {
        fun win(sec: Int, mv: String?) = """{"steps":[{"name":"s","inputs":[],"nodes":[{"id":"w","op":"win","name":"stepHz","agg":"mean","sec":$sec${if (mv != null) ""","mv":$mv""" else ""}},{"id":"k","op":"const","value":1.2},{"id":"c","op":"cmp","f":"<"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}],"out":"s"}"""
        // four explicit 90 % terms, plus a fifth window with no mv: at the global 90 % it is a duplicate of the first (four terms); at 60 % it is a fifth term and the compile fails
        val graphs = listOf(win(2, "90"), win(3, "90"), win(4, "90"), win(5, "90"), win(2, null))
        val base = PlanTestFixtures.captureHalf("Dup")
        val guards = graphs.mapIndexed { i, g -> com.rokid.context.ai.canvas.PipelineNode("g$i", com.rokid.context.ai.canvas.NodeTypes.EXPR_GUARD, "g$i", kotlinx.serialization.json.buildJsonObject { put("graph", kotlinx.serialization.json.Json.parseToJsonElement(g)) }) }
        val ids = guards.map { it.id }
        val p = base.copy(
            nodes = base.nodes + guards,
            edges = base.edges.filterNot { it.from == "head" && it.to == "thresh" } + com.rokid.context.ai.canvas.PipelineEdge("head", ids.first()) +
                ids.zipWithNext { a, b -> com.rokid.context.ai.canvas.PipelineEdge(a, b) } + com.rokid.context.ai.canvas.PipelineEdge(ids.last(), "thresh"),
        )
        h.frozen(p, ran = false)
        val published = com.rokid.context.ai.canvas.plan.PlanCompiler.compile(p, windowMinValidPct = 90).plan!!.configHash
        assertNull("at 60 % the duplicate is a fifth term", com.rokid.context.ai.canvas.plan.PlanCompiler.compile(p, windowMinValidPct = 60).plan)
        try {
            com.rokid.context.ai.canvas.plan.WindowTolerance.provider = { 60 }
            assertEquals(published, ReplayCandidates(h.canvas).forHash(published)!!.configHash)
        } finally { com.rokid.context.ai.canvas.plan.WindowTolerance.provider = { 90 } }
    }
}
