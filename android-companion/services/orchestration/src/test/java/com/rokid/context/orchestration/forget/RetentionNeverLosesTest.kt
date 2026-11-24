package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetListener
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.orchestration.proactive.InMemorySuggestionStore
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.ProactiveForgetListener
import com.rokid.context.orchestration.proactive.SourceLiveness
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.provenance.ProvenanceGate
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.MomentForgetFixture
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.memory.moment.MomentId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P0 retention fix, the "26 Nov 00:50 cannot lose anything" proof: a golden world whose SHORT facts (older than the window) back a nudge (with a `dependsOn`
 * chain), an answer, a stored Moment and a Scene. The real [RetentionWorker] runs; the facts go, and nothing that depended on them loses text or rows.
 * Both `forget_v2` modes. An explicit user Forget of the same fact still redacts (control).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetentionNeverLosesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val hour = 3_600_000L
    private val graph get() = MemoryGraph.get(ctx)
    private val sec = MomentForgetFixture.SEC
    private val mom = MomentId.format(sec + 100)
    private val now = System.currentTimeMillis()

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); DeveloperFlags.setPauseRetention(ctx, false) }
    @After fun tearDown() { ForgetV2.testOverride = null; ForgetListeners.unregister(counter); graph.db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun seed(): MomentForgetFixture {
        val fx = MomentForgetFixture(graph.db, now)
        val old = now - 30 * hour // SHORT window is 24 h by default
        fx.frame("f1", now - 30 * hour, wire = "w1", capturedAt = now - 30 * hour)
        fx.memory("fact1", "f1", createdAt = old); fx.memory("fact2", "f1", createdAt = old); fx.memory("fact3", null, createdAt = old)
        graph.db.memoryDao().insert(com.rokid.context.memory.db.MemoryEntity("cap1", "f1", "caption", "caption", "", MemoryTier.LONG, 0.8, com.rokid.context.memory.db.ReviewState.OK, false, old, false, "caption"))
        graph.db.askDao().upsert(AskEntity("a1", 1, "q", "ANSWER TEXT", """["fact1","fact3"]""", null, "test", false))
        graph.db.nudgeDao().insertIgnore(NudgeEntity("n1", 1, "NUDGE TEXT", "WHY TEXT", """["fact2",{"dependsOn":["fact3"]}]""", 1, "h1", "PHONE", false))
        fx.project(mom, "FRAME" to "f1", "MEMORY" to "fact1", "MEMORY" to "fact2")
        graph.db.sceneDao().replace(
            SceneEntity("sc1", 1, 0L, MomentId.startMs(mom)!!, MomentId.startMs(mom)!! + 999, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L),
            listOf(ScenePartEntity("sc1", ScenePartKind.MOMENT, mom, "r", MomentId.startMs(mom)!!)),
        )
        graph.proposals.create(ProposalEntity("pp", ProposalOrigin.NUDGE.name, "n1", "REMINDER", """{"title":"t"}""", ProposalState.PENDING.name, 99_999, null, null, 1), 1)
        return fx
    }

    private fun assertNothingLost() = runBlocking {
        assertTrue(graph.db.memoryViewDao().byId("fact1") == null && graph.db.memoryViewDao().byId("fact2") == null && graph.db.memoryViewDao().byId("fact3") == null)
        assertNotNull("the LONG caption is not a tier-expiry candidate yet", graph.db.memoryViewDao().byId("cap1"))
        val a = graph.db.askDao().byId("a1")!!
        assertFalse(a.redacted); assertEquals("ANSWER TEXT", a.answer); assertEquals("""["fact1","fact3"]""", a.sourcesJson)
        val n = graph.db.nudgeDao().byIds(listOf("n1")).single()
        assertFalse(n.redacted); assertEquals("NUDGE TEXT", n.text); assertEquals("WHY TEXT", n.why); assertEquals("""["fact2",{"dependsOn":["fact3"]}]""", n.sourceIdsJson)
        assertEquals("the stored Moment stays", listOf(mom), graph.db.momentDao().momentIdsBetween(mom, mom))
        assertTrue("the Scene stays", graph.db.sceneDao().sceneIdsHoldingMoments(listOf(mom)).contains("sc1"))
        assertEquals(ProposalState.PENDING.name, graph.proposals.byId("pp")!!.state)
        assertEquals("""{"title":"t"}""", graph.proposals.byId("pp")!!.paramsJson)
    }

    private fun run() = runBlocking { assertTrue(TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() is ListenableWorker.Result.Success) }

    @Test fun retentionLosesNothingDependentLegacyPath() = runBlocking { ForgetV2.testOverride = false; seed(); run(); assertNothingLost() }
    @Test fun retentionLosesNothingDependentV2Path() = runBlocking { ForgetV2.testOverride = true; seed(); run(); assertNothingLost() }

    /** P4: the plan is counts per tier and kind, read-only, and its line carries no id and no text. */
    @Test fun planCountsPerTierAndKindAndLogsNoContent() = runBlocking {
        seed()
        val retention = Retention(graph, com.rokid.context.memory.forget.ForgetService(graph.db, graph.proposals))
        val plan = retention.plan(com.rokid.context.memory.MemorySettings(), now)
        assertEquals(3, plan.shortDue); assertEquals(0, plan.mediumDue); assertEquals(0, plan.longDue)
        assertEquals(1, plan.nudgesKept); assertEquals(1, plan.answersKept)
        val line = plan.line()
        assertTrue(line, line.startsWith("plan shortDue=3 mediumDue=0 longDue=0 "))
        listOf("fact1", "fact2", "fact3", "NUDGE TEXT", "ANSWER TEXT", "f1").forEach { assertFalse(line, it in line) }
        assertEquals("planning wrote nothing", 3, graph.db.forgetDao().countExpired("SHORT", now - 24 * hour))
    }

    /** Control: an explicit user Forget of the same fact still redacts. */
    @Test fun userForgetStillRedacts() = runBlocking {
        ForgetV2.testOverride = false; seed()
        com.rokid.context.memory.forget.ForgetService(graph.db, graph.proposals).forgetFact("fact1")
        assertTrue(graph.db.askDao().byId("a1")!!.redacted)
    }

    // ---- retention r1 (Codex retention-r1): the extended golden world ----------------------------------------------------------------------------------------------------------------------
    private val day = 24 * hour
    private val counter = object : ForgetListener {
        var frames = 0; var ranges = 0
        override suspend fun onFramesForgotten(frameIds: List<String>) { frames++ }
        override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { ranges++ }
    }
    private fun fresh(v2: Boolean) { graph.db.close(); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); ForgetV2.testOverride = v2; ForgetListeners.unregister(counter); ForgetListeners.register(counter); counter.frames = 0; counter.ranges = 0 }
    private fun bothModes(block: suspend (Boolean) -> Unit) = runBlocking { for (v2 in listOf(false, true)) { fresh(v2); block(v2) } }

    private val plTs = now - 10 * day
    private val skTs = now - 5 * day
    private val lfTs = now - 40 * hour
    private val orTs = now - 40 * day

    private fun momentAt(ts: Long, plus: Long = 0) = MomentId.format(ts / 1000 + plus)
    private suspend fun scene(id: String, mom: String) {
        val start = MomentId.startMs(mom)!!
        graph.db.sceneDao().replace(SceneEntity(id, 1, 0L, start, start + 999, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L), listOf(ScenePartEntity(id, ScenePartKind.MOMENT, mom, "r", start)))
    }
    private suspend fun state(frame: String, state: String, updatedAt: Long) = graph.db.frameStateDao().insertIgnore(listOf(FrameStateEntity(frame, state, 0, null, null, null, updatedAt)))
    private suspend fun nudge(id: String, sources: String) = graph.db.nudgeDao().insertIgnore(NudgeEntity(id, 1, "TEXT $id", "WHY $id", sources, 1, "h$id", "PHONE", false))

    /** The golden world of [seed] plus: a linked photo and a skipped photo that are due, a legacy frame with an expiring memory, a legacy orphan frame with CARRIED provenance, a CARRIED expired memory, a summary built on an expired fact. */
    private suspend fun seedExtended(): MomentForgetFixture {
        val fx = seed()
        val old = now - 30 * hour
        // linked photo "pl": described by a LONG caption, 10 days old; a Moment, a Scene, a neighbouring Moment of another photo and a mark inside the privacy radius
        fx.frame("pl", plTs, wire = "wpl", capturedAt = plTs); state("pl", "EXTRACTED", plTs)
        graph.db.memoryDao().insert(MemoryEntity("capPl", "pl", "caption pl", "caption", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, plTs, false, "caption pl"))
        graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("capPl", "pl", null, "FACT")))
        fx.project(momentAt(plTs), "FRAME" to "pl", "MEMORY" to "capPl"); scene("scPl", momentAt(plTs))
        fx.frame("nearPl", plTs + 3000, wire = "wnp", capturedAt = plTs + 3000); fx.project(momentAt(plTs, 3), "FRAME" to "nearPl")
        fx.mark("mkPl", plTs + 2000)
        nudge("nPl", """["frame:pl"]""")
        // skipped photo "sk": NEAR_DUP, scored low, 5 days old, with a Moment, a Scene and a mark in its radius
        fx.frame("sk", skTs, wire = "wsk", capturedAt = skTs)
        graph.db.frameMetaDao().upsert(FrameMetaEntity("sk", "wsk", null, "STILL", false, null, null, null, 640, 0.0f, null, null, null, "sp", skTs, capturedAtMs = skTs)); state("sk", "NEAR_DUP", skTs)
        fx.project(momentAt(skTs), "FRAME" to "sk"); scene("scSk", momentAt(skTs)); fx.mark("mkSk", skTs + 1000)
        // legacy frame (no frame_meta) whose only memory expires: a Moment, a Scene and a mark around it
        fx.frame("lf", lfTs); fx.memory("lfFact", "lf", createdAt = lfTs)
        fx.project(momentAt(lfTs), "FRAME" to "lf", "MEMORY" to "lfFact"); scene("scLf", momentAt(lfTs)); fx.mark("mkLf", lfTs + 2000)
        // legacy orphan frame older than longDays with a CARRIED row to a live memory a nudge cites; a mark and a neighbouring Moment in its radius
        fx.frame("orph", orTs)
        graph.db.memoryDao().insert(MemoryEntity("carL", null, "carried live", "note", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, now - hour, false, "carried live"))
        graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("carL", "orph", null, "CARRIED")))
        nudge("nCar", """["carL"]""")
        fx.frame("nearOr", orTs + 2000, wire = "wno", capturedAt = orTs + 2000); fx.project(momentAt(orTs, 2), "FRAME" to "nearOr"); fx.mark("mkOr", orTs + 1000)
        // a CARRIED SHORT memory of f1 that expires, and a nudge that cites it; a LONG summary built on the expiring fact1
        fx.memory("carr", null, createdAt = old); graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("carr", "f1", null, "CARRIED"))); nudge("nc", """["carr"]""")
        graph.db.memoryDao().insert(MemoryEntity("sum1", null, "summary", "cluster_summary", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, old, true, "summary")); fx.derivedFrom("sum1", "fact1")
        return fx
    }

    private fun settings() = MemorySettings(perKindSince = now - 30 * day)
    private fun retention() = runBlocking {
        Retention(graph, ForgetService(graph.db, graph.proposals) { now }).run(settings(), now) { r -> FrameSweep.delete(r.framesToDelete) }
    }
    private suspend fun momentsAlive(vararg ids: String) = ids.filter { graph.db.momentDao().moment(it) != null }
    private suspend fun scenesAlive(vararg ids: String) = ids.filter { graph.db.sceneDao().scene(it) != null }
    private suspend fun marksAlive() = graph.db.canvasSlice3Dao().labels().map { it.labelId }.toSet()

    /** Finding 1: expiring a linked photo and a skipped photo is storage only: Moments, Scenes, marks stay, no radius cascade, no listener is told. */
    @Test fun photoAndSkippedPhotoExpiryDropNoMomentSceneMarkAndNotifyNobody() = bothModes { v2 ->
        seedExtended()
        val summary = retention()
        assertEquals("v2=$v2", 1, summary.skippedDeleted)
        assertNull("the linked photo expired", graph.db.forgetDao().frame("pl")); assertNull(graph.db.forgetDao().frame("sk"))
        assertEquals(listOf("pl", "sk"), graph.db.forgetDao().tombstoned(listOf("pl", "sk")).sorted())
        assertNotNull("its metadata stays", graph.db.frameMetaDao().get("pl"))
        assertEquals(listOf(momentAt(plTs), momentAt(plTs, 3), momentAt(skTs)).sorted(), momentsAlive(momentAt(plTs), momentAt(plTs, 3), momentAt(skTs)).sorted())
        assertEquals(listOf("scPl", "scSk"), scenesAlive("scPl", "scSk"))
        assertTrue("the marks inside the radius stay", marksAlive().containsAll(listOf("mkPl", "mkSk")))
        assertEquals("no framesForgotten for an expiry", 0, counter.frames); assertEquals("no range fence", 0, counter.ranges)
        assertFalse(graph.db.nudgeDao().byIds(listOf("nPl")).single().redacted)
        assertTrue("no durable range fence was written", graph.db.canvasSlice3Dao().meta("forgetRanges").isNullOrEmpty())
    }

    /** Finding 2: memories expiring on a LEGACY frame (no metadata) lock the frame and delete its file but drop no Moment, Scene or mark and run no radius cascade. */
    @Test fun memoryExpiryOnALegacyFrameDropsNoMomentOrMark() = bothModes { v2 ->
        seedExtended(); retention()
        assertNull("v2=$v2", graph.db.memoryViewDao().byId("lfFact"))
        assertEquals(listOf("lf"), graph.db.forgetDao().tombstoned(listOf("lf")))
        assertEquals(listOf(momentAt(lfTs)), momentsAlive(momentAt(lfTs))); assertEquals(listOf("scLf"), scenesAlive("scLf")); assertTrue(marksAlive().contains("mkLf"))
        assertEquals(0, counter.frames); assertEquals(0, counter.ranges)
    }

    /** Finding 3: the legacy orphan pass has its own path: the frame goes, a CARRIED memory, the nudge citing it, the marks and the Moments in its radius do not. */
    @Test fun legacyOrphanExpiryDoesNotCascadeCarriedProvenance() = bothModes { v2 ->
        seedExtended(); retention()
        assertNull("v2=$v2", graph.db.forgetDao().frame("orph")); assertEquals(listOf("orph"), graph.db.forgetDao().tombstoned(listOf("orph")))
        assertEquals(ReviewState.OK, graph.db.forgetDao().memories(listOf("carL")).single().reviewState)
        val n = graph.db.nudgeDao().byIds(listOf("nCar")).single(); assertFalse(n.redacted); assertEquals("TEXT nCar", n.text)
        assertTrue(marksAlive().contains("mkOr")); assertEquals(listOf(momentAt(orTs, 2)), momentsAlive(momentAt(orTs, 2)))
        assertEquals("the CARRIED lineage row stays", listOf("carL"), graph.db.forgetDao().carriedMemoryIdsOfFrame("orph"))
        assertEquals(0, counter.frames); assertEquals(0, counter.ranges)
    }

    /** Finding 4: after expiry took the facts, their provenance edges and their frame links, a LATER user Forget of the photo still redacts every nudge and answer that cited them, with their proposal, and invalidates what was built on them. */
    @Test fun aLaterUserForgetOfThePhotoStillFindsWhatCitedExpiredSources() = bothModes { v2 ->
        seedExtended(); retention()
        // everything survived the expiry ...
        assertFalse(graph.db.askDao().byId("a1")!!.redacted); assertFalse(graph.db.nudgeDao().byIds(listOf("n1")).single().redacted); assertFalse(graph.db.nudgeDao().byIds(listOf("nc")).single().redacted)
        assertNotNull(graph.db.canvasSlice3Dao().meta("lin:f:f1"))
        // ... and the later user Forget of f1 reaches them through the lineage
        ForgetService(graph.db, graph.proposals).forgetMoment("f1")
        assertTrue("v2=$v2 the answer citing expired fact1", graph.db.askDao().byId("a1")!!.redacted)
        assertTrue("the nudge citing expired fact2 and depending on fact3", graph.db.nudgeDao().byIds(listOf("n1")).none { !it.redacted })
        assertTrue("the nudge citing an expired CARRIED memory", graph.db.nudgeDao().byIds(listOf("nc")).none { !it.redacted })
        assertNotEquals("the proposal of the redacted nudge is no longer pending", ProposalState.PENDING.name, graph.proposals.byId("pp")!!.state)
        assertEquals("the summary built on the expired fact is invalidated", ReviewState.INVALIDATED, graph.db.forgetDao().memories(listOf("sum1")).single().reviewState)
        assertNull("the lineage of the photo is spent", graph.db.canvasSlice3Dao().meta("lin:f:f1"))
        assertFalse("an unrelated nudge stays", graph.db.nudgeDao().byIds(listOf("nCar")).single().redacted)
    }

    /** Finding 4 (photo cites): a nudge that cited the photo itself still redacts when the user later forgets the already-expired photo. */
    @Test fun aLaterUserForgetOfAnExpiredPhotoRedactsWhatCitedThePhoto() = bothModes { v2 ->
        seedExtended(); retention()
        assertFalse(graph.db.nudgeDao().byIds(listOf("nPl")).single().redacted)
        ForgetService(graph.db, graph.proposals).forgetMoment("pl")
        assertTrue("v2=$v2", graph.db.nudgeDao().byIds(listOf("nPl")).none { !it.redacted })
    }

    /** Delete all removes the lineage (ids of expired memories) with everything else. */
    @Test fun deleteAllWipesTheLineage() = bothModes { _ ->
        seedExtended(); retention()
        assertNotNull(graph.db.canvasSlice3Dao().meta("lin:f:f1"))
        graph.db.wipeDao().wipeMemory()
        assertNull(graph.db.canvasSlice3Dao().meta("lin:f:f1"))
    }

    /** Finding 5: an expired photo does not withdraw the suggestion that cites it (no event, and the sweep treats an expired photo as live); a user Forget of it does. */
    @Test fun anExpiredPhotoKeepsItsSuggestionAndAUserForgetWithdrawsIt() = bothModes { v2 ->
        seedExtended(); retention()
        val store = InMemorySuggestionStore()
        store.add(SuggestionRecord("sug", now, "f2:sug", ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.7, "wd:Q1", listOf("frame:pl"), listOf("arrived")))
        val listener = ProactiveForgetListener({ store }, { SourceLiveness(ProvenanceGate.forGraph(MemoryGraph.get(ctx)), { emptySet() }) })
        ForgetListeners.register(listener)
        try {
            assertEquals("v2=$v2 an expiry notifies nobody", 0, counter.frames)
            assertEquals("a sweep after the expiry keeps it", 0, listener.sweep()); assertEquals(listOf("sug"), store.records().map { it.id })
            ForgetService(graph.db, graph.proposals).forgetMoment("pl")
            listener.onUserForget()
            assertTrue("the user's Forget withdraws it", store.records().isEmpty())
        } finally { ForgetListeners.unregister(listener) }
    }
}
