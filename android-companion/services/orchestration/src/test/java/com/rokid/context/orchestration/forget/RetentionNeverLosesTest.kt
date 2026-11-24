package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MemoryVectorEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.ExpiryMarks
import com.rokid.context.memory.forget.ForgetListener
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForgetFixture
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.ForgetPlanner
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.memory.forget.lifecycle.Policy
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.orchestration.proactive.InMemorySuggestionStore
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.ProactiveForgetListener
import com.rokid.context.orchestration.proactive.SourceLiveness
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.provenance.ProvenanceGate
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
 * Retention v3, the golden world (design 2026-11-24, Codex retention r1 + r2): RETENTION NEVER DELETES TEXT. The world has SHORT facts older than the recall window that back a nudge (with a `dependsOn` chain), an answer, a stored
 * Moment, a Scene, a pending proposal and a summary built on them, plus linked / skipped / legacy photos with Moments, Scenes and marks in their radius. The real [Retention] and [RetentionWorker] run (both `forget_v2` modes) and
 * every row of text, every edge and every citation is byte-equal afterwards; only text VECTORS of past-the-window facts, and photo files and rows, go. Every Codex finding is closed or structurally impossible below (the
 * table is in docs/superpowers/plans/handoffs/retention-v3-2026-11-24.md). The explicit-Forget controls are unchanged.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetentionNeverLosesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val graph get() = MemoryGraph.get(ctx)
    private val sec = MomentForgetFixture.SEC
    private val mom = MomentId.format(sec + 100)
    private val now = System.currentTimeMillis()

    private val counter = object : ForgetListener {
        var frames = 0; var ranges = 0
        override suspend fun onFramesForgotten(frameIds: List<String>) { frames++ }
        override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { ranges++ }
    }

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); DeveloperFlags.setPauseRetention(ctx, false) }
    @After fun tearDown() { runBlocking { com.rokid.context.memory.MemorySettingsStore.get(ctx).update { MemorySettings() } }; ForgetV2.testOverride = null; ForgetListeners.unregister(counter); graph.db.close(); MemoryGraph.resetForTests(null) }

    private fun fresh(v2: Boolean) { graph.db.close(); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); ForgetV2.testOverride = v2; ForgetListeners.unregister(counter); ForgetListeners.register(counter); counter.frames = 0; counter.ranges = 0 }
    private fun bothModes(block: suspend (Boolean) -> Unit) = runBlocking { for (v2 in listOf(false, true)) { fresh(v2); block(v2) } }

    private val plTs = now - 10 * day
    private val skTs = now - 5 * day
    private val lfTs = now - 40 * hour
    private val orTs = now - 40 * day
    private val fOld = now - 30 * hour

    private fun momentAt(ts: Long, plus: Long = 0) = MomentId.format(ts / 1000 + plus)
    private suspend fun scene(id: String, mom: String) {
        val start = MomentId.startMs(mom)!!
        graph.db.sceneDao().replace(SceneEntity(id, 1, 0L, start, start + 999, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L), listOf(ScenePartEntity(id, ScenePartKind.MOMENT, mom, "r", start)))
    }
    private suspend fun state(frame: String, state: String, updatedAt: Long) = graph.db.frameStateDao().insertIgnore(listOf(FrameStateEntity(frame, state, 0, null, null, null, updatedAt)))
    private suspend fun nudge(id: String, sources: String) = graph.db.nudgeDao().insertIgnore(NudgeEntity(id, 1, "TEXT $id", "WHY $id", sources, 1, "h$id", "PHONE", false))
    private suspend fun vector(id: String) = graph.db.embeddingDao().putVector(MemoryVectorEntity(id, "sp1", ByteArray(8)))
    private suspend fun hasVector(id: String) = graph.db.embeddingDao().embeddedAmong("sp1", listOf(id)).isNotEmpty()

    /** The golden world: see the class comment. Text vectors are seeded for every memory so demotion has something to remove. */
    private suspend fun seed(): MomentForgetFixture {
        val fx = MomentForgetFixture(graph.db, now)
        // f1: a photo with three facts' worth of provenance; its facts are older than the recall window (30 h, window 12 h)
        fx.frame("f1", now - 30 * hour, wire = "w1", capturedAt = now - 30 * hour)
        fx.memory("fact1", "f1", createdAt = fOld); fx.memory("fact2", "f1", createdAt = fOld); fx.memory("fact3", null, createdAt = fOld)
        graph.db.memoryDao().insert(MemoryEntity("cap1", "f1", "caption", "caption", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, fOld, false, "caption"))
        graph.db.askDao().upsert(AskEntity("a1", 1, "q", "ANSWER TEXT", """["fact1","fact3"]""", null, "test", false))
        nudge("n1", """["fact2",{"dependsOn":["fact3"]}]""")
        fx.project(mom, "FRAME" to "f1", "MEMORY" to "fact1", "MEMORY" to "fact2")
        scene("sc1", mom)
        graph.proposals.create(ProposalEntity("pp", ProposalOrigin.NUDGE.name, "n1", "REMINDER", """{"title":"t"}""", ProposalState.PENDING.name, 99_999, null, null, 1), 1)
        graph.db.memoryDao().insert(MemoryEntity("sum1", null, "summary", "cluster_summary", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, fOld, true, "summary")); fx.derivedFrom("sum1", "fact1")
        // pl: linked photo, 10 days old, described by a LONG caption; a Moment, a Scene, a neighbouring Moment of another photo and a mark inside the privacy radius
        fx.frame("pl", plTs, wire = "wpl", capturedAt = plTs); state("pl", "EXTRACTED", plTs)
        graph.db.memoryDao().insert(MemoryEntity("capPl", "pl", "caption pl", "caption", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, plTs, false, "caption pl"))
        graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("capPl", "pl", null, "FACT")))
        fx.project(momentAt(plTs), "FRAME" to "pl", "MEMORY" to "capPl"); scene("scPl", momentAt(plTs))
        fx.frame("nearPl", plTs + 3000, wire = "wnp", capturedAt = plTs + 3000); fx.project(momentAt(plTs, 3), "FRAME" to "nearPl")
        fx.mark("mkPl", plTs + 2000)
        nudge("nPl", """["frame:pl"]""")
        // sk: skipped photo (NEAR_DUP, scored low), 5 days old, with a Moment, a Scene and a mark in its radius
        fx.frame("sk", skTs, wire = "wsk", capturedAt = skTs)
        graph.db.frameMetaDao().upsert(FrameMetaEntity("sk", "wsk", null, "STILL", false, null, null, null, 640, 0.0f, null, null, null, "sp", skTs, capturedAtMs = skTs)); state("sk", "NEAR_DUP", skTs)
        fx.project(momentAt(skTs), "FRAME" to "sk"); scene("scSk", momentAt(skTs)); fx.mark("mkSk", skTs + 1000)
        // lf: legacy frame (no frame_meta) whose only memory is a past-the-window fact: a Moment, a Scene and a mark around it
        fx.frame("lf", lfTs); fx.memory("lfFact", "lf", createdAt = lfTs)
        fx.project(momentAt(lfTs), "FRAME" to "lf", "MEMORY" to "lfFact"); scene("scLf", momentAt(lfTs)); fx.mark("mkLf", lfTs + 2000)
        // orph: legacy orphan frame older than the Photos setting with a CARRIED row to a live memory a nudge cites; a mark and a neighbouring Moment in its radius
        fx.frame("orph", orTs)
        graph.db.memoryDao().insert(MemoryEntity("carL", null, "carried live", "note", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, now - hour, false, "carried live"))
        graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("carL", "orph", null, "CARRIED")))
        nudge("nCar", """["carL"]""")
        fx.frame("nearOr", orTs + 2000, wire = "wno", capturedAt = orTs + 2000); fx.project(momentAt(orTs, 2), "FRAME" to "nearOr"); fx.mark("mkOr", orTs + 1000)
        // a CARRIED SHORT memory of f1 and a nudge that cites it
        fx.memory("carr", null, createdAt = fOld); graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("carr", "f1", null, "CARRIED"))); nudge("nc", """["carr"]""")
        listOf("fact1", "fact2", "fact3", "cap1", "sum1", "capPl", "lfFact", "carL", "carr").forEach { vector(it) }
        return fx
    }

    private fun settings() = MemorySettings(recallHours = 12, perKindSince = now - 30 * day)
    private fun retention() = runBlocking {
        Retention(graph, ForgetService(graph.db, graph.proposals) { now }).run(settings(), now) { r -> FrameSweep.delete(r.framesToDelete) }
    }
    private suspend fun momentsAlive(vararg ids: String) = ids.filter { graph.db.momentDao().moment(it) != null }
    private suspend fun scenesAlive(vararg ids: String) = ids.filter { graph.db.sceneDao().scene(it) != null }
    private suspend fun marksAlive() = graph.db.canvasSlice3Dao().labels().map { it.labelId }.toSet()
    private suspend fun metaKeys(prefix: String): List<String> = graph.db.openHelper.readableDatabase.query("SELECT key FROM canvas_meta WHERE key LIKE '$prefix%' ORDER BY key").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    /** Every row of [table] as one string (all columns), in a stable order: byte-equality of what retention must not touch. */
    private fun dump(table: String, order: String): String = graph.db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY $order").use { c ->
        buildString { while (c.moveToNext()) { for (i in 0 until c.columnCount) append(if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) "<blob>" else c.getString(i)).append('|'); append('\n') } }
    }
    private val textTables = listOf("memory" to "id", "memory_source" to "memoryId, sourceMemoryId", "frame_source" to "memoryId, frameId, role", "ask" to "id", "nudge" to "id", "proposal" to "id",
        "moment" to "momentId", "moment_part" to "momentId, kind, refId", "scene" to "sceneId", "scene_part" to "sceneId, refId")
    private fun snapshot() = textTables.associate { (t, o) -> t to dump(t, o) }

    // ---- WP1: retention never deletes text ------------------------------------------------------------------------------------------------------------------------------------------------

    /** K1, r1-2, r1-4, r1-5 (memory half), r2-1, r2-2, r2-8: with the recall window passed, every text row, edge, citation, Moment, Scene and proposal is byte-equal; no `lin:` key is ever written; only vectors and photos go. */
    @Test fun retentionKeepsEveryTextRowAndCitationByteEqual() = bothModes { v2 ->
        seed()
        val before = snapshot()
        val summary = retention()
        val after = snapshot()
        for ((t, _) in textTables) assertEquals("v2=$v2 table $t is byte-equal after retention", before[t], after[t])
        assertEquals("no lineage key exists, none was written", emptyList<String>(), metaKeys("lin:"))
        assertEquals("the facts were demoted (vectors only)", 5, summary.demoted) // fact1-3, lfFact, carr (SHORT, past the 12 h window)
        listOf("fact1", "fact2", "fact3", "lfFact", "carr").forEach { assertFalse("$it lost its text vector", hasVector(it)) }
        listOf("cap1", "sum1", "capPl", "carL").forEach { assertTrue("a LONG memory ($it) is never demoted", hasVector(it)) }
        assertEquals(ProposalState.PENDING.name, graph.proposals.byId("pp")!!.state)
        assertEquals("nothing was forgotten, so no listener was told", 0, counter.frames + counter.ranges)
    }

    /** r1-2: a fact on a LEGACY frame (no metadata) is no longer deleted: it stays with its frame, Moment, Scene and mark; nothing locks or deletes the legacy frame while a memory cites it. */
    @Test fun aFactOnALegacyFrameIsKeptWithItsFrameMomentSceneAndMark() = bothModes { v2 ->
        seed(); retention()
        assertNotNull("v2=$v2", graph.db.memoryViewDao().byId("lfFact"))
        assertNotNull(graph.db.forgetDao().frame("lf")); assertEquals(emptyList<String>(), graph.db.forgetDao().tombstoned(listOf("lf")))
        assertEquals(listOf(momentAt(lfTs)), momentsAlive(momentAt(lfTs))); assertEquals(listOf("scLf"), scenesAlive("scLf")); assertTrue(marksAlive().contains("mkLf"))
        assertEquals(0, counter.frames); assertEquals(0, counter.ranges)
    }

    /** Structural: there is no memory-expiry entry point left, in the service, the executor or the planner's policies. */
    @Test fun noMemoryExpiryEntryPointExists() {
        assertTrue(ForgetService::class.java.methods.none { it.name == "expire" })
        assertTrue(com.rokid.context.memory.forget.lifecycle.ForgetExecutor::class.java.declaredMethods.none { it.name == "expire" })
        assertTrue(com.rokid.context.memory.forget.lifecycle.Policy::class.java.classes.none { it.simpleName == "Retention" })
        assertTrue("the legacy lineage machinery is gone (Codex v3 r2 P1-2..P1-4)", runCatching { Class.forName("com.rokid.context.memory.forget.ExpiredLineage") }.isFailure)
    }

    /** The real worker, both modes: nothing lost, and the counts-only plan equals the run (`demoteDue` = `demoted`). */
    @Test fun theWorkerLosesNothingAndThePlanEqualsTheRun() = bothModes { v2 ->
        seed()
        com.rokid.context.memory.MemorySettingsStore.get(ctx).update { it.copy(recallHours = 12) }
        val before = snapshot()
        val plan = Retention(graph, ForgetService(graph.db, graph.proposals)).plan(com.rokid.context.memory.MemorySettingsStore.get(ctx).settings.value, now)
        assertTrue(TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() is ListenableWorker.Result.Success)
        for ((t, _) in textTables) assertEquals("v2=$v2 table $t", before[t], dump(t, textTables.first { it.first == t }.second))
        assertEquals("demoteDue was the number of vectors the run removed", plan.demoteDue, 5)
        assertEquals(0, graph.db.forgetDao().countDemotable(now - 12 * hour))
        val line = plan.line()
        assertTrue(line, line.startsWith("plan demoteDue=5 "))
        listOf("fact1", "fact2", "fact3", "NUDGE TEXT", "ANSWER TEXT", "f1").forEach { assertFalse(line, it in line) }
    }

    /** Control: an explicit user Forget of the same fact still redacts, withdraws and invalidates (unweakened), also AFTER retention ran. */
    @Test fun userForgetStillRedactsAfterRetentionRan() = bothModes { v2 ->
        seed(); retention(); retention()
        ForgetService(graph.db, graph.proposals).forgetFact("fact1")
        assertTrue("v2=$v2", graph.db.askDao().byId("a1")!!.redacted)
        assertEquals("the summary built on the forgotten fact is invalidated", ReviewState.INVALIDATED, graph.db.forgetDao().memories(listOf("sum1")).single().reviewState)
    }

    /** r1-4, r2-1: a LATER user Forget of the photo after any number of retention runs reaches every dependent through the LIVE graph (no lineage needed). */
    @Test fun aLaterUserForgetOfThePhotoStillFindsEveryDependent() = bothModes { v2 ->
        seed(); retention(); retention(); retention()
        ForgetService(graph.db, graph.proposals).forgetMoment("f1")
        assertTrue("v2=$v2 the answer citing fact1", graph.db.askDao().byId("a1")!!.redacted)
        assertTrue("the nudge citing fact2 and depending on fact3", graph.db.nudgeDao().byIds(listOf("n1")).none { !it.redacted })
        assertTrue("the nudge citing the CARRIED fact", graph.db.nudgeDao().byIds(listOf("nc")).none { !it.redacted })
        assertNotEquals("the proposal of the redacted nudge is no longer pending", ProposalState.PENDING.name, graph.proposals.byId("pp")!!.state)
        assertEquals(ReviewState.INVALIDATED, graph.db.forgetDao().memories(listOf("sum1")).single().reviewState)
        assertFalse("an unrelated nudge stays", graph.db.nudgeDao().byIds(listOf("nCar")).single().redacted)
    }

    /** r2-2: Forget photo A whose batch fact also cites surviving photo B never redacts what cites an unrelated fact of B (no lineage seeds exist to over-reach). */
    @Test fun forgettingPhotoAWithABatchFactNeverRedactsWhatCitesPhotoBsOtherFacts() = bothModes { v2 ->
        val fx = MomentForgetFixture(graph.db, now)
        fx.frame("A", now - 40 * hour, wire = "wA", capturedAt = now - 40 * hour); fx.frame("B", now - 40 * hour + 60_000, wire = "wB", capturedAt = now - 40 * hour + 60_000)
        fx.memory("mAB", "A", createdAt = fOld); graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("mAB", "B", null, "FACT")))
        fx.memory("xB", "B", createdAt = fOld)
        graph.db.askDao().upsert(AskEntity("askB", 1, "q", "ABOUT B ONLY", """["xB"]""", null, "test", false))
        listOf("mAB", "xB").forEach { vector(it) }
        retention(); retention()
        ForgetService(graph.db, graph.proposals).forgetMoment("A")
        assertFalse("v2=$v2 an answer citing only photo B's own fact stays", graph.db.askDao().byId("askB")!!.redacted)
        assertNotNull(graph.db.memoryViewDao().byId("xB"))
    }

    // ---- WP2: the expiry marker ---------------------------------------------------------------------------------------------------------------------------------------------------------

    /** r1-1 (carried): expiring a linked photo and a skipped photo is storage only: Moments, Scenes, marks stay, no radius cascade, no listener is told; r2-3/r2-6: the marker is written with the tombstone. */
    @Test fun photoAndSkippedPhotoExpiryDropNoMomentSceneMarkAndNotifyNobody() = bothModes { v2 ->
        seed()
        val summary = retention()
        assertEquals("v2=$v2", 1, summary.skippedDeleted)
        assertNull("the linked photo expired", graph.db.forgetDao().frame("pl")); assertNull(graph.db.forgetDao().frame("sk"))
        assertEquals(listOf("pl", "sk"), graph.db.forgetDao().tombstoned(listOf("pl", "sk")).sorted())
        assertEquals("both carry the expiry marker", setOf("pl", "sk"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl", "sk", "f1")))
        assertNotNull("its metadata stays", graph.db.frameMetaDao().get("pl"))
        assertEquals(listOf(momentAt(plTs), momentAt(plTs, 3), momentAt(skTs)).sorted(), momentsAlive(momentAt(plTs), momentAt(plTs, 3), momentAt(skTs)).sorted())
        assertEquals(listOf("scPl", "scSk"), scenesAlive("scPl", "scSk"))
        assertTrue("the marks inside the radius stay", marksAlive().containsAll(listOf("mkPl", "mkSk")))
        assertEquals("no framesForgotten for an expiry", 0, counter.frames); assertEquals("no range fence", 0, counter.ranges)
        assertFalse(graph.db.nudgeDao().byIds(listOf("nPl")).single().redacted)
        assertTrue("no durable range fence was written", graph.db.canvasSlice3Dao().meta("forgetRanges").isNullOrEmpty())
    }

    /** r1-3 (carried), r2-6: the legacy orphan pass has its own path: the frame goes WITH a marker; a CARRIED memory, the nudge citing it, the marks and the Moments in its radius do not. */
    @Test fun legacyOrphanExpiryDoesNotCascadeCarriedProvenanceAndIsMarked() = bothModes { v2 ->
        seed(); retention()
        assertNull("v2=$v2", graph.db.forgetDao().frame("orph")); assertEquals(listOf("orph"), graph.db.forgetDao().tombstoned(listOf("orph")))
        assertEquals("the legacy frame has no metadata and still carries the marker", setOf("orph"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("orph")))
        assertEquals(ReviewState.OK, graph.db.forgetDao().memories(listOf("carL")).single().reviewState)
        val n = graph.db.nudgeDao().byIds(listOf("nCar")).single(); assertFalse(n.redacted); assertEquals("TEXT nCar", n.text)
        assertTrue(marksAlive().contains("mkOr")); assertEquals(listOf(momentAt(orTs, 2)), momentsAlive(momentAt(orTs, 2)))
        assertEquals("the CARRIED lineage row stays", listOf("carL"), graph.db.forgetDao().carriedMemoryIdsOfFrame("orph"))
        assertEquals(0, counter.frames); assertEquals(0, counter.ranges)
    }

    /** r2-3: the marker is written in the SAME transaction as the tombstone and the row delete: a failure after the expiry leaves no marker, no tombstone and the frame row. */
    @Test fun theMarkerIsAtomicWithTheTombstoneAndTheRowDelete() = bothModes { v2 ->
        seed()
        val service = ForgetService(graph.db, graph.proposals) { now }
        runCatching { graph.db.withTransaction { service.expirePhotos(listOf("pl"), requireLinked = true); error("injected failure after the expiry") } }
        assertNotNull("v2=$v2 the frame row is back", graph.db.forgetDao().frame("pl"))
        assertEquals(emptyList<String>(), graph.db.forgetDao().tombstoned(listOf("pl")))
        assertEquals(emptySet<String>(), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl")))
        assertEquals(emptyList<String>(), metaKeys("rx:"))
    }

    /** r2-3, r2-6: "expired" versus "forgotten" is data. Expired P (metadata kept) -> not forgotten. A fact Forget tombstones P and keeps its metadata -> forgotten. Expired legacy L -> not forgotten. Expired then user-forgotten P -> forgotten. */
    @Test fun forgottenVersusExpiredIsDataNotAnInferenceFromMetadata() = bothModes { v2 ->
        seed()
        val gate = ProvenanceGate.forGraph(MemoryGraph.get(ctx))
        retention() // expires pl, sk (with metadata) and orph (legacy, no metadata)
        assertEquals("v2=$v2 expired keyframes are not forgotten", emptySet<String>(), gate.forgottenFrames(setOf("pl", "sk")))
        assertEquals("an expired LEGACY frame (no frame_meta) is not forgotten either (r2-6)", emptySet<String>(), gate.forgottenFrames(setOf("orph")))
        val service = ForgetService(graph.db, graph.proposals)
        service.forgetFact("fact1") // tombstones f1 but keeps its frame_meta
        assertNotNull("the metadata is still there", graph.db.frameMetaDao().get("f1"))
        assertEquals("a user Forget of a fact of the photo is a Forget, whatever the metadata says (r2-3)", setOf("f1"), gate.forgottenFrames(setOf("f1")))
        service.forgetMoment("pl")
        assertEquals("expired then user-forgotten -> forgotten", setOf("pl"), gate.forgottenFrames(setOf("pl")))
        assertEquals("and its marker is gone", emptySet<String>(), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl")))
        service.forgetMoment("orph")
        assertEquals("expired legacy then user-forgotten -> forgotten", setOf("orph"), gate.forgottenFrames(setOf("orph")))
    }

    /** r1-5, r2-3, r2-6: an expiry never withdraws a suggestion that cites the photo (also a legacy one); a user Forget (of a moment, or of a fact of it) withdraws it with the surfaces. */
    @Test fun anExpiredPhotoKeepsItsSuggestionAndAUserForgetWithdrawsIt() = bothModes { v2 ->
        seed(); retention()
        val store = InMemorySuggestionStore()
        store.add(SuggestionRecord("sug", now, "f2:sug", ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.7, "wd:Q1", listOf("frame:pl"), listOf("arrived")))
        store.add(SuggestionRecord("sugL", now, "f2:sugL", ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.7, "wd:Q2", listOf("frame:orph"), listOf("arrived")))
        store.add(SuggestionRecord("sugF", now, "f2:sugF", ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.7, "wd:Q3", listOf("frame:f1"), listOf("arrived")))
        val listener = ProactiveForgetListener({ store }, { SourceLiveness(ProvenanceGate.forGraph(MemoryGraph.get(ctx)), { emptySet() }) })
        ForgetListeners.register(listener)
        try {
            assertEquals("v2=$v2 an expiry notifies nobody", 0, counter.frames)
            assertEquals("a sweep after the expiry keeps the records citing expired keyframe and expired legacy frame", 0, listener.sweep() - 0)
            assertEquals(listOf("sug", "sugF", "sugL"), store.records().map { it.id }.sorted())
            ForgetService(graph.db, graph.proposals).forgetMoment("pl"); listener.onUserForget()
            assertEquals("the user's Forget of the photo withdraws its record only", listOf("sugF", "sugL"), store.records().map { it.id }.sorted())
            ForgetService(graph.db, graph.proposals).forgetFact("fact1"); listener.onUserForget() // keeps f1's metadata, still a Forget
            assertEquals("a Forget of a fact of the photo withdraws the record citing the photo (metadata kept)", listOf("sugL"), store.records().map { it.id })
            ForgetService(graph.db, graph.proposals).forgetMoment("orph"); listener.onUserForget()
            assertTrue("a Forget of the expired legacy frame withdraws its record", store.records().isEmpty())
        } finally { ForgetListeners.unregister(listener) }
    }

    /** r2-4: Forget after expiry still runs the radius cleanup: the mark within the radius of the expired photo goes, the range is told, and the planner preview names the same mark. Also for a legacy frame (capture time from the marker). */
    @Test fun forgetAfterExpiryStillRunsTheRadiusCleanup() = bothModes { v2 ->
        seed(); retention()
        assertTrue(marksAlive().containsAll(listOf("mkPl", "mkOr")))
        val plan = ForgetPlanner(graph.db).plan(setOf(Ref(Family.PHOTO, "pl")), Policy.User)
        assertTrue("v2=$v2 the preview names the mark of the expired photo", plan.nodes.keys.any { it.family == Family.MARK && it.id == "mkPl" })
        val legacyPlan = ForgetPlanner(graph.db).plan(setOf(Ref(Family.PHOTO, "orph")), Policy.User)
        assertTrue("the preview of an expired legacy frame names its mark (capture time from the marker)", legacyPlan.nodes.keys.any { it.family == Family.MARK && it.id == "mkOr" })
        ForgetService(graph.db, graph.proposals).forgetMoment("pl")
        assertFalse("the mark inside the radius of the expired photo is deleted", marksAlive().contains("mkPl"))
        assertTrue("a range was told so the glasses follow", counter.ranges > 0)
        ForgetService(graph.db, graph.proposals).forgetMoment("orph")
        assertFalse("the mark inside the radius of the expired LEGACY frame is deleted too", marksAlive().contains("mkOr"))
    }

    /** r2-9: a Moment whose photo only EXPIRED still counts as alive for a pin (the sweep keeps its anchor); a user Forget of the photo clears the marker, so the link ends. */
    @Test fun aPinKeepsItsMomentAfterExpiryAndLosesItAfterAForget() = bothModes { v2 ->
        seed(); retention()
        val md = graph.db.momentDao()
        assertTrue("v2=$v2 the photo row is gone", md.photoMomentsOf(listOf(momentAt(plTs))).isEmpty())
        assertEquals("the pin sweep's liveness keeps it", setOf(momentAt(plTs)), md.momentsAliveOrExpired(listOf(momentAt(plTs))))
        ForgetService(graph.db, graph.proposals).forgetMoment("pl")
        assertEquals("a user Forget ends the link", emptySet<String>(), md.momentsAliveOrExpired(listOf(momentAt(plTs))))
        assertEquals("a Moment with no photo at all is gone", emptySet<String>(), md.momentsAliveOrExpired(listOf(MomentId.format(sec + 5_000))))
    }

    // ---- WP2 (place history) ------------------------------------------------------------------------------------------------------------------------------------------------------------

    /** r2-5 (v18, context.db): expiry only marks the card-history row gone and KEEPS its frame id as private lineage; a later Forget of the photo still deletes the row, in the same transaction. */
    @Test fun placeHistoryKeepsItsLineageThroughExpiryAndAForgetStillDeletesTheRow() = bothModes { v2 ->
        seed()
        val dao = graph.db.placeCardLogDao()
        val history = com.rokid.context.work.frames.place.PlaceCardHistory(graph.db)
        dao.put(com.rokid.context.memory.db.PlaceCardLogEntity("h1", 1, "wd:Q1", "Site", "PHOTO", null, "pl", null, "c1", com.rokid.context.memory.place.CardDelivery.SENT))
        retention() // expires pl
        val row = dao.cardLog("h1")!!
        assertEquals("v2=$v2 the lineage stays", "pl", row.frameId); assertEquals(1, row.photoGone)
        assertEquals(listOf("pl"), history.photoIds())
        ForgetService(graph.db, graph.proposals).forgetMoment("pl")
        assertNull("v2=$v2 a later Forget of the expired photo deletes the photo-derived row", dao.cardLog("h1"))
    }

    // ---- WP4: pause / quota split -------------------------------------------------------------------------------------------------------------------------------------------------------

    /** r2-7: Pause ON skips photo expiry and demotion, but the file-retry sweep still finishes a user's Forget (a JPEG left on disk). */
    @Test fun pauseSkipsTheDeletingStepsButNeverTheFileRetry() = runBlocking {
        ForgetV2.testOverride = false
        seed()
        DeveloperFlags.setPauseRetention(ctx, true)
        com.rokid.context.memory.MemorySettingsStore.get(ctx).update { it.copy(recallHours = 12, photoDays = 7) }
        val dir = java.io.File(ctx.filesDir, com.rokid.context.data.frames.RokidFrameInbox.FRAME_DIRS.first()).also { it.mkdirs() }
        val stray = java.io.File(dir, "gone1.jpg").also { it.writeText("x") }
        graph.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("gone1", 1)) // a user Forget committed this, its JPEG delete failed
        assertTrue(TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() is ListenableWorker.Result.Success)
        assertFalse("the file-retry sweep ran under Pause", stray.exists())
        assertNotNull("no photo expired under Pause", graph.db.forgetDao().frame("pl")); assertEquals(emptyList<String>(), metaKeys("rx:f:"))
        assertTrue("no fact was demoted under Pause", hasVector("fact1"))
        DeveloperFlags.setPauseRetention(ctx, false)
    }

    private suspend fun quotaWorld(): Pair<List<String>, MomentForgetFixture> {
        val fx = MomentForgetFixture(graph.db, now)
        val ids = (1..6).map { "q$it" }
        ids.forEachIndexed { i, id ->
            val ts = now - (40 - i) * day // q1 oldest
            fx.frame(id, ts, wire = "w$id", capturedAt = ts); state(id, "EXTRACTED", ts)
            graph.db.memoryDao().insert(MemoryEntity("c$id", id, "caption $id", "caption", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, ts, false, "caption $id"))
            graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("c$id", id, null, "FACT")))
        }
        return ids to fx
    }
    private fun quotaRetention(usedPerPhoto: Long, protectedIds: Set<String>? = emptySet(), baseline: Long = 0L) = Retention(
        graph, ForgetService(graph.db, graph.proposals) { now },
        usedBytes = { baseline + usedPerPhoto * graph.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM frame").use { c -> c.moveToFirst(); c.getLong(0) } },
        protectedFrames = { protectedIds }, quotaChunk = 1,
    )

    /** K6: over the cap, the OLDEST eligible photos go until usage is at 90% of the cap; text is untouched and every evicted photo is marked expired (not forgotten). */
    @Test fun quotaEvictsTheOldestEligibleUntilNinetyPercentAndNeverTouchesText() = bothModes { v2 ->
        val (ids, _) = quotaWorld()
        val mb = 1024L * 1024
        val textBefore = snapshot()
        // cap 10 MB (the smallest the setting allows), 6 photos x 2 MB = 12 MB: over. 90% = 9 MB -> 2 photos must go (12 -> 8)
        val evicted = runBlocking { quotaRetention(2 * mb).runQuota(MemorySettings(quotaMb = 10), now) }
        assertEquals("v2=$v2", 2, evicted)
        assertNull(graph.db.forgetDao().frame("q1")); assertNull(graph.db.forgetDao().frame("q2")); assertNotNull(graph.db.forgetDao().frame("q3"))
        assertEquals(setOf("q1", "q2"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), ids))
        assertEquals("text is untouched", textBefore, snapshot())
        assertEquals("not forgotten: no suggestion is withdrawn", emptySet<String>(), ProvenanceGate.forGraph(MemoryGraph.get(ctx)).forgottenFrames(ids.toSet()))
        assertEquals(0, counter.frames)
    }

    /** K6: never explicit importance (a place memory, a mark within the radius, an accepted proposal's source, a place card or a pin), never an undescribed photo, never a photo under 14 days. When stuck, nothing is evicted. */
    @Test fun quotaNeverEvictsPhotosOfExplicitImportanceUndescribedOrYoung() = bothModes { v2 ->
        val (ids, fx) = quotaWorld()
        val mb = 1024L * 1024
        // q1: a place memory; q2: a mark in its radius; q3: cited by an EXECUTED proposal's nudge; q4: named by a place card / pin (the protected set); q5: undescribed; q6: young (5 days)
        graph.db.memoryDao().insert(MemoryEntity("place1", "q1", "a place", "place", "", MemoryTier.LONG, 0.9, ReviewState.OK, false, now - 39 * day, false, "a place"))
        fx.mark("mkQ2", now - 39 * day + 1000)
        nudge("nQ3", """["frame:q3"]""")
        graph.proposals.create(ProposalEntity("pQ3", ProposalOrigin.NUDGE.name, "nQ3", "REMINDER", """{"title":"t"}""", ProposalState.EXECUTED.name, 99_999, null, null, 1), 1)
        graph.db.openHelper.writableDatabase.execSQL("DELETE FROM memory WHERE id = 'cq5'") // q5 loses its caption: not described
        graph.db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("q6", now - 5 * day, "/p/q6.jpg", "rokid_glasses")) // keep a row; q6 below is made young
        graph.db.openHelper.writableDatabase.execSQL("UPDATE frame SET ts = ${now - 5 * day} WHERE id = 'q6'")
        val before = snapshot()
        val evicted = runBlocking { quotaRetention(2 * mb, protectedIds = setOf("q4")).runQuota(MemorySettings(quotaMb = 10), now) }
        assertEquals("v2=$v2 nothing is eligible", 0, evicted)
        ids.forEach { assertNotNull("$it stays", graph.db.forgetDao().frame(it)) }
        assertEquals("no marker was written", emptyList<String>(), metaKeys("rx:f:"))
        assertEquals(before, snapshot())
        // an unreadable protection set (place card or pin lookup failed) is fail closed: nothing is evicted even though q3.. would otherwise be fine
        assertEquals(0, runBlocking { quotaRetention(2 * mb, protectedIds = null).runQuota(MemorySettings(quotaMb = 10), now) })
    }

    /** K6: under the cap nothing happens. */
    @Test fun quotaDoesNothingUnderTheCap() = runBlocking {
        ForgetV2.testOverride = false
        val (ids, _) = quotaWorld()
        assertEquals(0, quotaRetention(1024L).runQuota(MemorySettings(quotaMb = 10), now))
        ids.forEach { assertNotNull(graph.db.forgetDao().frame(it)) }
    }

    /** Codex v3 r1 interim safety: with Pause ON the worker deletes NOTHING, quota eviction included (a 10 MB cap over 18 MB of photos); with Pause OFF the same world evicts the three oldest. */
    @Test fun theWorkerEvictsNothingUnderPauseAndEvictsWhenPauseIsOff() = runBlocking {
        ForgetV2.testOverride = false
        val (ids, _) = quotaWorld()
        com.rokid.context.memory.MemorySettingsStore.get(ctx).update { it.copy(quotaMb = 10, photoDays = MemorySettings.FOREVER) }
        val dir = java.io.File(ctx.filesDir, com.rokid.context.data.frames.RokidFrameInbox.FRAME_DIRS.first()).also { it.mkdirs() }
        ids.forEach { id ->
            val f = java.io.File(dir, "$id.jpg"); java.io.RandomAccessFile(f, "rw").use { it.setLength(3L * 1024 * 1024) }
            graph.db.openHelper.writableDatabase.execSQL("UPDATE frame SET path = '${f.absolutePath}' WHERE id = '$id'")
        }
        DeveloperFlags.setPauseRetention(ctx, true)
        val before = snapshot()
        assertTrue(TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() is ListenableWorker.Result.Success)
        ids.forEach { assertNotNull("$it stays under Pause", graph.db.forgetDao().frame(it)); assertTrue(java.io.File(dir, "$it.jpg").exists()) }
        assertEquals("nothing marked, nothing deleted under Pause", emptyList<String>(), metaKeys("rx:f:")); assertEquals(before, snapshot())
        DeveloperFlags.setPauseRetention(ctx, false)
        assertTrue(TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() is ListenableWorker.Result.Success) // 18 MB over a 10 MB cap: down to 9 MB = 3 photos
        val gone = ids.filter { graph.db.forgetDao().frame(it) == null }
        assertEquals("the three oldest are gone", listOf("q1", "q2", "q3"), gone)
        gone.forEach { assertFalse("$it's JPEG was deleted", java.io.File(dir, "$it.jpg").exists()) }
        assertEquals("text untouched", 6, graph.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM memory").use { c -> c.moveToFirst(); c.getInt(0) })
    }

    // ---- WP1 (K8): the one-shot migration ----------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * K8, Codex v3 r2 P1-1: the one-shot writes NO marker: every tombstone that exists when this build starts is FORGOTTEN, even one shaped like an old expiry (metadata, no row, a surviving description), and one whose photo had a
     * single description forgotten by the user. It purges the stray `lin:` keys (expected none) and keeps every other key. Idempotent.
     */
    @Test fun migrationMarksNothingAndAPreExistingTombstoneIsNeverReadAsExpired() = bothModes { v2 ->
        seed()
        val s3 = graph.db.canvasSlice3Dao()
        s3.putMeta("lin:f:x", "[\"a\"]"); s3.putMeta("lin:d:y", "[\"b\"]"); s3.putMeta("keep_me", "1")
        graph.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("sk", 1)); graph.db.forgetDao().deleteFrame("sk") // shaped like an old expiry: tombstone + metadata, no frame row ...
        graph.db.memoryDao().insert(MemoryEntity("skCap", "sk", "caption sk", "caption", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, skTs, false, "caption sk")) // ... and a description that survived it
        val fx = MomentForgetFixture(graph.db, now)
        fx.frame("fg", now - 9 * day, wire = "wfg", capturedAt = now - 9 * day)
        graph.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("fg", 1)); graph.db.forgetDao().deleteFrame("fg") // metadata + tombstone + no row, nothing cites it
        graph.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("ghost", 1)) // no metadata, no row
        ForgetService(graph.db, graph.proposals) { now }.forgetFact("fact1") // the user forgets ONE description of f1 (it keeps fact2 and cap1): f1 is tombstoned, its row and metadata stay
        RetentionMigration.runOnce(graph.db, now)
        assertEquals("v2=$v2 the stray lineage keys are purged", emptyList<String>(), metaKeys("lin:")); assertEquals("1", s3.meta("keep_me"))
        assertEquals("no marker is written", emptyList<String>(), metaKeys("rx:"))
        assertEquals("every pre-existing tombstone is forgotten, never expired", setOf("sk", "fg", "ghost", "f1"), ProvenanceGate.forGraph(MemoryGraph.get(ctx)).forgottenFrames(setOf("sk", "fg", "ghost", "f1")))
        assertNotNull(s3.meta(RetentionMigration.DONE))
        s3.putMeta("lin:f:z", "[]"); RetentionMigration.runOnce(graph.db, now)
        assertEquals("one-shot: the done marker keeps it from running again", listOf("lin:f:z"), metaKeys("lin:"))
    }

    /** Codex v3 r2 P1-5: the marker range read is COMPLETE: more than 20,000 markers, a page size that divides the count and one that does not, none dropped. */
    @Test fun theMarkerRangeReadReturnsEveryMarkerAboveTheOldCap() = runBlocking {
        val s3 = graph.db.canvasSlice3Dao()
        val total = 20_150; val base = MomentForgetFixture.MS
        graph.db.withTransaction { for (i in 0 until total) ExpiryMarks.mark(s3, "e%05d".format(i), now, base + i) }
        ExpiryMarks.mark(s3, "outLow", now, base - 1); ExpiryMarks.mark(s3, "outHigh", now, base + total)
        val want = (0 until total).map { "e%05d".format(it) }.toSet()
        for (page in listOf(1_000, 997, 20_150, 4_000)) assertEquals("page $page", want, ExpiryMarks.idsBetween(s3, base, base + total - 1, pageSize = page))
        assertEquals("default page size", want, ExpiryMarks.idsBetween(s3, base, base + total - 1))
    }

    // ---- WP2: Delete all, pruning ----------------------------------------------------------------------------------------------------------------------------------------------------------

    /** Delete all removes the expiry markers (and any old lineage) with everything else. */
    @Test fun deleteAllWipesTheMarkers() = bothModes { _ ->
        seed(); retention()
        graph.db.canvasSlice3Dao().putMeta("lin:f:x", "[]")
        assertTrue(metaKeys("rx:").isNotEmpty())
        graph.db.wipeDao().wipeMemory()
        assertEquals(emptyList<String>(), metaKeys("rx:")); assertEquals(emptyList<String>(), metaKeys("lin:"))
    }

    /** Pruning a tombstone (no row, memory or source left) removes its marker in the same transaction, so markers stay bounded. */
    @Test fun pruningATombstoneRemovesItsMarker() = runBlocking {
        val fx = MomentForgetFixture(graph.db, now)
        fx.frame("e1", now - 20 * day, wire = "we1", capturedAt = now - 20 * day); state("e1", "NEAR_DUP", now - 20 * day)
        ForgetService(graph.db, graph.proposals) { now - 20 * day }.expirePhotos(listOf("e1")) // tombstone created 20 days ago, nothing cites it
        assertEquals(setOf("e1"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("e1")))
        assertEquals(1, FrameSweep.pruneWithMarkers(graph.db, now - FrameSweep.TOMBSTONE_KEEP_MS, listOf("")))
        assertEquals(emptyList<String>(), metaKeys("rx:"))
    }

    /** The enumerated user entry points that tombstone a photo, each clearing the marker in its own transaction; the list fails when a new public ForgetService method is added without being classified. */
    @Test fun everyUserPathThatTombstonesAPhotoClearsTheMarker() = bothModes { v2 ->
        val known = setOf("forgetFact", "forgetFacts", "forgetMoment", "forgetSelection", "previewSelection", "removeDescriptions", "removeSuspectDescriptions", "expirePhotos", "expireOrphanFrames", "getExecutor")
        val actual = ForgetService::class.java.methods.map { it.name }.filter { '$' !in it }.filter { it.startsWith("forget") || it.startsWith("expire") || it.startsWith("remove") || it.startsWith("preview") || it == "getExecutor" }.toSet()
        assertTrue("an unclassified ForgetService entry point appeared: ${actual - known}", known.containsAll(actual))
        // each path, on its own world: expire the photo first, then forget through the path, the marker must be gone and the photo forgotten
        suspend fun world(): ForgetService {
            graph.db.close(); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); ForgetV2.testOverride = v2
            seed(); ForgetService(graph.db, graph.proposals) { now }.expirePhotos(listOf("pl", "sk"), requireLinked = false)
            assertEquals(setOf("pl", "sk"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl", "sk")))
            return ForgetService(graph.db, graph.proposals) { now }
        }
        var s = world(); s.forgetMoment("pl"); assertEquals("forgetMoment", setOf("sk"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl", "sk")))
        s = world(); s.forgetFact("capPl"); assertEquals("forgetFact of the photo's caption", setOf("sk"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl", "sk")))
        s = world(); s.forgetFacts(listOf("capPl")); assertEquals("forgetFacts", setOf("sk"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl", "sk")))
        s = world(); s.removeDescriptions(listOf("capPl")); assertEquals("removeDescriptions locks no photo, so it clears nothing", setOf("pl", "sk"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl", "sk")))
        if (v2) { s = world(); val roots = setOf(Ref(Family.PHOTO, "pl")); val tok = s.previewSelection(roots).token(false); s.forgetSelection(roots, tok); assertEquals("forgetSelection", setOf("sk"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl", "sk"))) }
        val fx = MomentForgetFixture(graph.db, now)
        s = world(); com.rokid.context.memory.forget.MomentForget(graph.db, s) { now }.forgetRange(plTs - 1000, plTs + 1000)
        assertEquals("MomentForget range", setOf("sk"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl", "sk")))
        s = world(); com.rokid.context.memory.forget.MomentForget(graph.db, s) { now }.forgetMoments(listOf(momentAt(plTs)))
        assertEquals("MomentForget moments", setOf("sk"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("pl", "sk")))
        fx.hashCode()
    }

    // ---- Codex v3 r1 (round 2): the findings ---------------------------------------------------------------------------------------------------------------------------------------------

    private suspend fun redactedCount(table: String, id: String) = graph.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE id = ? AND redacted = 1", arrayOf(id)).use { c -> c.moveToFirst(); c.getInt(0) }
    private suspend fun reviewState(id: String) = graph.db.openHelper.readableDatabase.query("SELECT reviewState FROM memory WHERE id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** P1-1: a photo that already carries a user tombstone (a Forget of one of its facts while another still describes it) is never marked expired, by the service, the executor, the orphan path or the planner. */
    @Test fun expiryNeverWritesTheMarkerOverAUserTombstone() = bothModes { v2 ->
        seed()
        ForgetService(graph.db, graph.proposals) { now }.forgetFact("fact1") // f1 keeps fact2 and cap1: its row stays, tombstoned
        val fd = graph.db.forgetDao()
        assertNotNull(fd.frame("f1")); assertEquals(listOf("f1"), fd.tombstoned(listOf("f1")))
        val svc = ForgetService(graph.db, graph.proposals) { now }
        assertEquals("v2=$v2 expirePhotos refuses it", 0, svc.expirePhotos(listOf("f1"), requireLinked = true).expiredFrames)
        assertEquals("expireOrphanFrames refuses it", 0, svc.expireOrphanFrames(listOf("f1")).expiredFrames)
        assertEquals(0, svc.expirePhotos(listOf("f1")).expiredFrames)
        assertNotNull("the row stays", fd.frame("f1")); assertEquals(emptyList<String>(), metaKeys("rx:"))
        assertEquals("still forgotten, never expired", setOf("f1"), ProvenanceGate.forGraph(MemoryGraph.get(ctx)).forgottenFrames(setOf("f1")))
        val plan = ForgetPlanner(graph.db).plan(setOf(Ref(Family.PHOTO, "f1")), Policy.RetentionPhotos(requireLinked = true, recheckSkipped = false))
        assertTrue("the planner mirrors the refusal", plan.nodes.keys.none { it.family == Family.PHOTO && it.id == "f1" })
    }

    /** P1-4: a scoped Forget finds an expired legacy photo through its marker by capture time, previews it and, running, withdraws the suggestion and the answer citing it and clears the marker. */
    @Test fun aScopedForgetReachesAnExpiredLegacyPhotoThroughItsMarker() = bothModes { v2 ->
        val fx = MomentForgetFixture(graph.db, now)
        val ts = MomentForgetFixture.MS + 5_000
        graph.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("lgx", 1)) // no frame row, no frame_meta, no memory: only the marker names it
        ExpiryMarks.mark(graph.db.canvasSlice3Dao(), "lgx", now, ts)
        nudge("nLg", """["frame:lgx"]"""); fx.ask("aLg", "lgx")
        val span = (MomentForgetFixture.MS + 4_000)..(MomentForgetFixture.MS + 5_999)
        val counts = fx.forget.preview(listOf(span))
        assertEquals("v2=$v2 the preview counts the expired photo", 1, counts.photos)
        fx.forget.forgetSpans(listOf(span), expectedHash = counts.planHash)
        assertEquals("the suggestion citing it is withdrawn", 0, graph.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM nudge WHERE id = 'nLg' AND redacted = 0").use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals("and the answer", 1, redactedCount("ask", "aLg"))
        assertEquals("the marker is cleared: the photo is forgotten now", emptyList<String>(), metaKeys("rx:"))
        assertEquals(setOf("lgx"), ProvenanceGate.forGraph(MemoryGraph.get(ctx)).forgottenFrames(setOf("lgx")))
    }

    /** P2-12: an expired photo that a retained stored Moment holds keeps its tombstone and markers past the 8 day prune; an unheld one is pruned with its markers. */
    @Test fun aRetainedMomentKeepsTheExpiryEvidenceOfItsPhoto() = runBlocking {
        val fx = MomentForgetFixture(graph.db, now)
        fx.frame("h1", plTs, wire = "wh1", capturedAt = plTs); fx.frame("h2", plTs + 5_000, wire = "wh2", capturedAt = plTs + 5_000)
        ForgetService(graph.db, graph.proposals) { now - 20 * day }.expirePhotos(listOf("h1", "h2"))
        val m1 = momentAt(plTs); fx.project(m1, "FRAME" to "h1")
        assertEquals("only the unheld tombstone is pruned", 1, FrameSweep.pruneWithMarkers(graph.db, now - FrameSweep.TOMBSTONE_KEEP_MS, listOf("")))
        assertEquals(setOf("h1"), ExpiryMarks.marked(graph.db.canvasSlice3Dao(), listOf("h1", "h2")))
        assertEquals(listOf("h1"), graph.db.forgetDao().tombstoned(listOf("h1", "h2")))
        assertTrue("the Moment / pin anchor is still alive for the pin sweep", m1 in graph.db.momentDao().momentsAliveOrExpired(listOf(m1)))
        graph.db.momentDao().remove(listOf(m1)) // once no Moment holds it, a later sweep prunes it
        assertEquals(1, FrameSweep.pruneWithMarkers(graph.db, now - FrameSweep.TOMBSTONE_KEEP_MS, listOf("")))
        assertEquals(emptyList<String>(), metaKeys("rx:"))
    }

    /** P1-7: an unreadable / corrupt pin list is a failure (never "nothing is pinned"): the protection read throws and the worker evicts nothing over the cap. */
    @Test fun corruptPinPreferencesFailTheProtectionReadAndQuotaEvictsNothing() = runBlocking {
        ForgetV2.testOverride = false
        val (ids, _) = quotaWorld()
        com.rokid.context.memory.MemorySettingsStore.get(ctx).update { it.copy(quotaMb = 10, photoDays = MemorySettings.FOREVER) }
        val dir = java.io.File(ctx.filesDir, com.rokid.context.data.frames.RokidFrameInbox.FRAME_DIRS.first()).also { it.mkdirs() }
        ids.forEach { id ->
            val f = java.io.File(dir, "$id.jpg"); java.io.RandomAccessFile(f, "rw").use { it.setLength(3L * 1024 * 1024) }
            graph.db.openHelper.writableDatabase.execSQL("UPDATE frame SET path = '${f.absolutePath}' WHERE id = '$id'")
        }
        val prefs = ctx.getSharedPreferences(com.rokid.context.memory.place.UserPinStore.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("pins", "{ this is not json").commit()
        try {
            assertTrue("the protection read fails", runCatching { QuotaProtection.frames(ctx) }.isFailure)
            assertTrue(TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() is ListenableWorker.Result.Success)
            ids.forEach { assertNotNull("$it stays: fail closed", graph.db.forgetDao().frame(it)); assertTrue(java.io.File(dir, "$it.jpg").exists()) }
            prefs.edit().remove("pins").commit() // readable (empty) again: the cap is enforced
            assertTrue(TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() is ListenableWorker.Result.Success)
            assertEquals(listOf("q1", "q2", "q3"), ids.filter { graph.db.forgetDao().frame(it) == null })
        } finally { prefs.edit().remove("pins").commit() }
    }

    /** P1-8: more protected photo ids than one query can bind never silently drops protection: nothing is evicted; at the limit it still works. */
    @Test fun aProtectionSetAboveTheBindLimitEvictsNothing() = runBlocking {
        ForgetV2.testOverride = false
        quotaWorld()
        val mb = 1024L * 1024
        val before = snapshot()
        assertEquals("20,001 protected ids: fail closed", 0, quotaRetention(2 * mb, protectedIds = (1..20_001).map { "p$it" }.toSet()).runQuota(MemorySettings(quotaMb = 10), now))
        assertEquals(6, graph.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM frame").use { c -> c.moveToFirst(); c.getInt(0) }); assertEquals(before, snapshot())
        assertEquals("20,000 is within the limit and protects its photos", 2, quotaRetention(2 * mb, protectedIds = (1..19_999).map { "p$it" }.toSet() + "q1").runQuota(MemorySettings(quotaMb = 10), now))
        assertNotNull(graph.db.forgetDao().frame("q1")); assertNull(graph.db.forgetDao().frame("q2")); assertNull(graph.db.forgetDao().frame("q3"))
    }

    /** P1-9: an accepted proposal citing a batch fact protects EVERY photo that fact's provenance names (frame_source), not only memory.frameId. */
    @Test fun anAcceptedProposalProtectsTheSecondaryPhotosOfABatchFact() = bothModes { v2 ->
        val (ids, fx) = quotaWorld()
        val mb = 1024L * 1024
        fx.memory("bf", "q1"); graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("bf", "q1", null, "FACT"), FrameSourceEntity("bf", "q2", null, "FACT")))
        nudge("nBf", """["bf"]""")
        graph.proposals.create(ProposalEntity("pBf", ProposalOrigin.NUDGE.name, "nBf", "REMINDER", """{"title":"t"}""", ProposalState.EXECUTED.name, 99_999, null, null, 1), 1)
        val evicted = runBlocking { quotaRetention(2 * mb).runQuota(MemorySettings(quotaMb = 10), now) }
        assertEquals("v2=$v2", 2, evicted)
        assertNotNull("the primary photo stays", graph.db.forgetDao().frame("q1")); assertNotNull("the secondary photo of the cited batch fact stays", graph.db.forgetDao().frame("q2"))
        assertEquals(listOf("q3", "q4"), ids.filter { graph.db.forgetDao().frame(it) == null })
    }
}
