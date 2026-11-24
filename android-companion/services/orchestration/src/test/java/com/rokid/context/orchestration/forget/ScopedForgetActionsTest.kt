package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.ForgetScope
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryExport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import java.io.File

/** Forget scope through the gate: typed outcomes, the post-commit cleanup (cached export, reminders, notifications, JPEGs), fences, previews. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScopedForgetActionsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val original = DataOpGate.shared
    private val sec = 1_761_000_000L
    private val ms = sec * 1000
    private fun mid(s: Long) = MomentId.format(sec + s)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        DataOpGate.shared = DataOpGate(waitMs = 200); MemoryExport.productionBuild = { true }; MemoryExport.dir(ctx).deleteRecursively()
        com.rokid.context.memory.forget.ForgetScopeSetting.experimentalAllowed = { true } // Developer mode on (the gate has its own tests)
    }
    @After fun tearDown() {
        com.rokid.context.memory.forget.ForgetScopeSetting.experimentalAllowed = { false }
        MemoryExport.testDelete = null; MemoryExport.productionBuild = MemoryExport.defaultProductionBuild; MemoryExport.dir(ctx).deleteRecursively()
        ctx.getSharedPreferences(MemoryExport.CLEANUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        DataOpGate.shared = original; graph.db.close(); MemoryGraph.resetForTests(null)
    }

    private val jpgs = HashMap<String, File>()
    private suspend fun photo(id: String, atMs: Long, memory: String? = null) {
        val f = File(ctx.filesDir, "rokid_archive/$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }; jpgs[id] = f
        graph.db.frameDao().insertIgnore(FrameEntity(id, atMs, f.absolutePath, "rokid_glasses", phash = "p$id"))
        graph.db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = atMs))
        if (memory != null) graph.db.memoryDao().insert(MemoryEntity(memory, id, "text of $memory", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, atMs, false, "text of $memory"))
    }
    private suspend fun moment(s: Long, vararg parts: Pair<String, String>) =
        graph.db.momentDao().replace(MomentEntity(mid(s), 1, 1, "h", 1, MomentType.FRAME), parts.map { MomentPartEntity(mid(s), it.first, it.second, "r", 1) })
    private suspend fun scene(id: String, vararg moments: Long) = graph.db.sceneDao().replace(
        SceneEntity(id, 1, ms, ms + moments.min() * 1000, ms + moments.max() * 1000 + 999, SceneRule.CAP_5_MIN, membershipRev = 1, builtAt = 1),
        moments.map { ScenePartEntity(id, ScenePartKind.MOMENT, mid(it), "r", ms + it * 1000) })
    private fun cachedExport() = File(MemoryExport.dir(ctx).apply { mkdirs() }, "proactive-context-memories-1.json").apply { writeText("{\"memories\":[{\"text\":\"text of ma1\"}]}") }

    /** Scene A = 0 s (a1) and 25 s (a3); Scene N = 90 s (n1). */
    private suspend fun world() {
        photo("a1", ms + 5, "ma1"); photo("a3", ms + 25_005, "ma3"); photo("n1", ms + 90_005, "mn1")
        moment(0, MomentPartKind.FRAME to "a1", MomentPartKind.MEMORY to "ma1"); moment(25, MomentPartKind.FRAME to "a3"); moment(90, MomentPartKind.FRAME to "n1")
        scene("sc-A", 0, 25); scene("sc-N", 90)
    }
    private suspend fun frames() = graph.db.frameDao().all().map { it.id }.sorted()

    @Test fun forgettingAMarkRunsTheRecordersUndoAndMapsTheGateOutcomes() = runBlocking<Unit> {
        val undone = mutableListOf<String>()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Mark("mk:1:2"), undoMark = { undone += it }))
        assertEquals(listOf("mk:1:2"), undone)
        assertEquals(ForgetActions.Outcome.EXPORT_IN_PROGRESS, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Mark("m"), undoMark = { throw DataOpGate.ExportInProgress() }))
        assertEquals(ForgetActions.Outcome.SAVE_IN_PROGRESS, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Mark("m"), undoMark = { throw DataOpGate.WriteInProgress() }))
        assertEquals("an upload block is its own outcome, not a save", ForgetActions.Outcome.UPLOAD_IN_PROGRESS, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Mark("m"), undoMark = { throw DataOpGate.UploadInProgress() }))
        assertEquals("the photo's cache-left notice survives the undo", ForgetActions.Outcome.CACHE_CLEANUP_FAILED, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Mark("m"), undoMark = { ForgetActions.MarkPhotoOutcome(ForgetActions.Outcome.CACHE_CLEANUP_FAILED) }))
        assertEquals(ForgetActions.Outcome.FAILED, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Mark("m"), undoMark = { error("x") }))
        assertEquals("no recorder, nothing removed", ForgetActions.Outcome.FAILED, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Mark("m")))
    }

    @Test fun aMomentScopeForgetRunsTheWholeCleanupOfAnyForget() = runBlocking<Unit> {
        world(); photo("b2", ms + 3_005); val export = cachedExport() // b2: a neighbour 3 s away, inside the Moment window
        graph.asks.add(com.rokid.context.memory.db.AskEntity("ask1", 1, "q", "a", "[\"frame:a1\"]", null, "phone", false))
        val pid = "prop_ASK_ask1"; val r = com.rokid.context.connectors.actions.ActionParams.Reminder("Call the clinic", System.currentTimeMillis() + 3_600_000, "n")
        graph.proposals.addIgnore(com.rokid.context.memory.db.ProposalEntity(pid, "ASK", "ask1", "REMINDER", com.rokid.context.connectors.actions.ActionParams.encode(r), "EXECUTED", 0, "auto", "alarm:$pid", 1))
        com.rokid.context.connectors.actions.executors.ReminderExecutor(ctx).arm(pid, r)
        val alarms = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.AlarmManager::class.java)); assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.MOMENT))
        assertFalse("the JPEG", jpgs["a1"]!!.exists()); assertFalse("the Moment window takes its neighbour", jpgs["b2"]!!.exists()); assertTrue("a photo outside the window stays", jpgs["a3"]!!.exists())
        assertFalse("the cached export", export.exists()); assertEquals("the armed reminder", 0, alarms.scheduledAlarms.size)
        assertEquals(listOf("a3", "n1"), frames())
    }

    @Test fun aSceneScopeForgetDeletesEveryJpegOfTheSceneAndTheSceneRows() = runBlocking<Unit> {
        world()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.SCENE))
        assertFalse(jpgs["a1"]!!.exists()); assertFalse(jpgs["a3"]!!.exists()); assertTrue(jpgs["n1"]!!.exists())
        assertEquals(listOf("sc-N"), graph.db.sceneDao().allScenes().map { it.sceneId })
        assertTrue("the durable outbox holds the phone files", graph.db.momentDao().pendingOutbox().any { it.payloadJson.contains("a3.jpg") })
    }

    @Test fun photoScopeForgetsThePhotoAndKeepsTheNeighbour() = runBlocking<Unit> {
        world()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.PHOTO))
        assertEquals(listOf("a3", "n1"), frames()); assertTrue(jpgs["a3"]!!.exists()); assertFalse(jpgs["a1"]!!.exists())
        assertFalse("PHOTO scope fences nothing", graph.db.canvasSlice3Dao().momentFenced(ms, ms + 999))
    }

    @Test fun inProductionAnUndeletableExportIsTypedAtEveryScopeAndTheForgetIsCommitted() = runBlocking<Unit> {
        listOf(ForgetScope.PHOTO, ForgetScope.MOMENT, ForgetScope.SCENE).forEach { scope ->
            graph.db.clearAllTables(); jpgs.clear(); world(); val f = cachedExport(); MemoryExport.testDelete = { false }
            assertEquals("$scope", ForgetActions.Outcome.CACHE_CLEANUP_FAILED, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), scope))
            assertTrue(f.exists()); assertFalse("committed: the photo is gone", "a1" in frames()); MemoryExport.testDelete = null; f.delete()
        }
    }

    @Test fun aForgetBlockedByACanvasSaveOrAnExportSaysSoAndForgetsNothingAtEveryScope() = runBlocking<Unit> {
        world()
        listOf(ForgetScope.PHOTO, ForgetScope.MOMENT, ForgetScope.SCENE).forEach { scope ->
            run {
                val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val save = async { DataOpGate.shared.write { inside.complete(Unit); release.await() } }; inside.await()
                assertEquals("$scope", ForgetActions.Outcome.SAVE_IN_PROGRESS, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), scope)); release.complete(Unit); save.await()
            }
            run {
                val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val exp = async { DataOpGate.shared.export { inside.complete(Unit); release.await() } }; inside.await()
                assertEquals("$scope", ForgetActions.Outcome.EXPORT_IN_PROGRESS, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), scope)); release.complete(Unit); exp.await()
            }
            assertEquals("nothing was forgotten", listOf("a1", "a3", "n1"), frames()); assertEquals(0, graph.db.momentDao().tombstoneCount()); assertEquals(2, graph.db.sceneDao().allScenes().size)
        }
    }

    @Test fun theSettingIsUsedWhenNoScopeIsGiven() = runBlocking<Unit> {
        world(); com.rokid.context.memory.forget.ForgetScopeSetting.provider = { ForgetScope.SCENE }
        try { assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"))) } finally { com.rokid.context.memory.forget.ForgetScopeSetting.provider = { ForgetScope.PHOTO } }
        assertEquals(listOf("n1"), frames())
    }

    @Test fun theOnForgottenRefreshRunsAfterACommittedScopedForgetOnly() = runBlocking<Unit> {
        world(); var refreshed = 0
        ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Moment("zz"), ForgetScope.MOMENT) { refreshed++ } // a no-op Forget still committed nothing harmful: the refresh is harmless
        assertEquals(1, refreshed)
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val save = async { DataOpGate.shared.write { inside.complete(Unit); release.await() } }; inside.await()
        ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.MOMENT) { refreshed++ }; release.complete(Unit); save.await()
        assertEquals("a blocked Forget does not refresh", 1, refreshed)
    }

    @Test fun thePreviewNamesTheEffectiveScopeAndTheCountsAndWritesNothing() = runBlocking<Unit> {
        world()
        val p = ForgetActions.previewScoped(ctx, ForgetTarget.Photo("a1"), ForgetScope.SCENE)
        assertEquals(ForgetScope.SCENE, p.effective); assertEquals(2, p.photos); assertEquals(2, p.memories); assertEquals(2, p.moments); assertEquals(1, p.scenesRemoved); assertEquals(0, p.scenesShrunk)
        assertEquals(listOf("a1", "a3", "n1"), frames()); assertEquals(0, graph.db.momentDao().tombstoneCount())
        assertEquals(ForgetScope.MOMENT, ForgetActions.previewScoped(ctx, ForgetTarget.Moment(mid(0)), ForgetScope.PHOTO).effective)
        assertNotNull(graph.db.sceneDao().scene("sc-A")); assertNull(graph.db.sceneDao().scene("none"))
    }

    // ---- review fix: the confirm deletes EXACTLY the previewed set ----
    @Test fun aContentChangeSinceThePreviewIsATypedOutcomeThatDeletesAndCleansNothing() = runBlocking<Unit> {
        world(); val export = cachedExport()
        ForgetScope.values().forEach { sc ->
            val p = ForgetActions.previewScoped(ctx, ForgetTarget.Photo("a1"), sc)
            photo("late$sc", ms + 4_000 + sc.ordinal * 1_000); moment(4L + sc.ordinal, MomentPartKind.FRAME to "late$sc") // a photo and its stored Moment inside every span and every radius
            val o = ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), sc, expectedHash = p.planHash)
            assertEquals("$sc", ForgetActions.Outcome.CONTENT_CHANGED, o); assertFalse(o.committed)
            assertTrue("a1 stays", "a1" in frames()); assertTrue(jpgs["a1"]!!.exists()); assertTrue("no cleanup ran", export.exists()); assertEquals(0, graph.db.momentDao().tombstoneCount())
        }
    }

    @Test fun aTooConnectedForgetIsATypedOutcomeAndThePreviewSaysSo() = runBlocking<Unit> {
        world(); var prev = "ma1"
        graph.db.memoryDao().let { d -> for (i in 1..40) { d.insert(MemoryEntity("c$i", null, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, ms + 300_000L + i, true, "t")); graph.db.forgetDao().insertEdges(listOf(com.rokid.context.memory.db.MemorySourceEntity("c$i", prev))); prev = "c$i" } }
        assertTrue(ForgetActions.previewScoped(ctx, ForgetTarget.Photo("a1"), ForgetScope.MOMENT).tooLarge)
        val o = ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.MOMENT)
        assertEquals(ForgetActions.Outcome.TOO_LARGE, o); assertFalse(o.committed); assertTrue("a1" in frames())
        assertEquals("the photo scope is unaffected", ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.PHOTO))
    }

    @Test fun theSamePreviewConfirmsWhenNothingChanged() = runBlocking<Unit> {
        world(); val p = ForgetActions.previewScoped(ctx, ForgetTarget.Photo("a1"), ForgetScope.SCENE)
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.SCENE, expectedHash = p.planHash))
        assertEquals(listOf("n1"), frames())
    }
}
