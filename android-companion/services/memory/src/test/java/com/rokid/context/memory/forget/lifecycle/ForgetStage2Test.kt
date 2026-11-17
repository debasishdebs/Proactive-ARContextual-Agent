package com.rokid.context.memory.forget.lifecycle

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SimpleSQLiteQuery
import com.rokid.context.memory.db.CanvasHelperJobEntity
import com.rokid.context.memory.db.CanvasReplayEntity
import com.rokid.context.memory.db.CanvasSweepEntity
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetDao
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.ObjectDerivedEntity
import com.rokid.context.memory.forget.ForgetContentChanged
import com.rokid.context.memory.forget.GoldenWorld
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Forget lifecycle Stage 2 (proxy rulings F1, F3, F5, F6, 2026-11-17), all behind `forgetV2`: residue and survivor checks, preview == delete, and the flag OFF behaviour pinned as v1.
 *  F1 a carried caption goes at once and its photo is described again alone; F5 the other original photo of a mixed memory is never locked or deleted and is described again alone;
 *  F3 a Scene that keeps Moments loses its old derived text now; F6 replays, sweeps, helper jobs and sensor rows are planned nodes and the plan hash changes (old confirmation tokens are invalid).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetStage2Test {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val w = GoldenWorld(db)
    @After fun close() { ForgetV2.testOverride = null; Leases.shared.revokeAll(); db.close() }

    private suspend fun scalar(sql: String): Long = db.query(SimpleSQLiteQuery(sql)).use { c -> c.moveToFirst(); c.getLong(0) }
    private suspend fun state(f: String) = db.frameStateDao().get(f)
    private suspend fun extracted(vararg ids: String) = db.frameStateDao().insertIgnore(ids.map { FrameStateEntity(it, "EXTRACTED", 0, null, null, null, 1L) })

    /** Two photos pX (forgotten) and pY, a mixed memory over both, and pB whose caption only CARRIES pX. */
    private suspend fun mixedWorld() {
        w.fx.frame("pX", w.tA, wire = "wX", capturedAt = w.tA); w.fx.frame("pY", w.tFar, wire = "wY", capturedAt = w.tFar); w.fx.frame("pB", w.tFar + 5_000_000, wire = "wB", capturedAt = w.tFar + 5_000_000)
        w.fx.memory("mXY", "pX"); w.fx.memory("mB", "pB")
        db.frameSourceDao().insertAll(listOf(
            FrameSourceEntity("mXY", "pX", null, "FACT"), FrameSourceEntity("mXY", "pY", null, "FACT"),
            FrameSourceEntity("mB", "pB", null, "FACT"), FrameSourceEntity("mB", "pX", null, "CARRIED"),
        ))
        extracted("pX", "pY", "pB")
    }

    // ---- F5 ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    @Test fun f5TheOtherPhotoOfAMixedMemoryIsPreservedAndDescribedAgainAlone() = runBlocking {
        ForgetV2.testOverride = true
        mixedWorld()
        w.service.forgetMoment("pX")
        assertNull("the mixed memory (its text) is gone first", db.forgetDao().memories(listOf("mXY")).firstOrNull())
        assertTrue("the other original photo is still stored", db.frameDao().all().any { it.id == "pY" })
        assertTrue("and never locked", db.forgetDao().tombstoned(listOf("pY")).isEmpty())
        assertEquals(listOf("pX"), db.forgetDao().tombstoned(listOf("pX", "pY")))
        val s = state("pY")!!
        assertEquals("PENDING", s.state); assertEquals(ForgetDao.SOLO_REBUILD, s.lastError); assertEquals(0, s.attempts)
        assertEquals("the forgotten photo has no state any more", null, state("pX"))
    }

    @Test fun f5FlagOffKeepsTheV1CascadeToTheOtherPhoto() = runBlocking {
        ForgetV2.testOverride = false
        mixedWorld()
        w.service.forgetMoment("pX")
        assertEquals("v1: the other photo is locked too", listOf("pX", "pY"), db.forgetDao().tombstoned(listOf("pX", "pY")).sorted())
        assertEquals("v1 never queues a rebuild", "EXTRACTED", state("pY")!!.state)
    }

    @Test fun f5APhotoWithAnotherDescriptionIsNotQueuedButItsOutputsAreScrubbed() = runBlocking {
        ForgetV2.testOverride = true
        w.seed(); extracted("fFar")
        w.service.forgetMoment("fA") // mMulti cites fA and fFar; fFar keeps mFar
        assertTrue("fFar kept", db.frameDao().all().any { it.id == "fFar" }); assertTrue(db.forgetDao().tombstoned(listOf("fFar")).isEmpty())
        assertEquals("it still has its own description: nothing to rebuild", "EXTRACTED", state("fFar")!!.state)
        assertNotNull(db.forgetDao().memories(listOf("mFar")).firstOrNull())
        assertEquals("the stored run outputs of the survivor (they may carry mixed text) are scrubbed", 0L, scalar("SELECT COUNT(*) FROM canvas_run_item WHERE frameId = 'fFar'"))
    }

    // ---- F1 ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    @Test fun f1ACarriedCaptionGoesAtOnceAndItsPhotoIsDescribedAgainAlone() = runBlocking {
        ForgetV2.testOverride = true
        mixedWorld()
        w.service.forgetMoment("pX")
        assertNull("the carried caption is erased at once", db.forgetDao().memories(listOf("mB")).firstOrNull())
        assertTrue("its photo is preserved", db.frameDao().all().any { it.id == "pB" }); assertTrue(db.forgetDao().tombstoned(listOf("pB")).isEmpty())
        val s = state("pB")!!
        assertEquals("PENDING", s.state); assertEquals(ForgetDao.SOLO_REBUILD, s.lastError)
    }

    @Test fun f1FlagOffErasesTheCarriedCaptionAndNeverRebuilds() = runBlocking {
        ForgetV2.testOverride = false
        mixedWorld()
        w.service.forgetMoment("pX")
        assertNull(db.forgetDao().memories(listOf("mB")).firstOrNull())
        assertEquals("EXTRACTED", state("pB")!!.state)
    }

    @Test fun descendantsOfTheCarriedCaptionAreInvalidatedImmediately() = runBlocking {
        ForgetV2.testOverride = true
        mixedWorld(); w.fx.memory("mSum", null, derived = true); w.fx.derivedFrom("mSum", "mB")
        w.service.forgetMoment("pX")
        assertEquals(com.rokid.context.memory.db.ReviewState.INVALIDATED, db.forgetDao().memories(listOf("mSum")).single().reviewState)
    }

    // ---- F3 ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    private suspend fun seedSceneField() = db.objectDao().writeDerivedTx(ObjectDerivedEntity("SCENE", "sMixed", "SUMMARY", "OLD SCENE TEXT", null, 1, null, w.tB), "build")
    private suspend fun sceneRows() = scalar("SELECT COUNT(*) FROM object_derived WHERE objectKind = 'SCENE' AND objectId = 'sMixed'")

    @Test fun f3ASceneThatKeepsMomentsLosesItsOldDerivedTextAndKeepsItsStructure() = runBlocking {
        ForgetV2.testOverride = true
        w.seed(); seedSceneField()
        w.service.forgetMoment("fB") // sMixed held momB (gone) and momFar (survives)
        assertEquals("the old derived text is erased, not just hidden", 0L, sceneRows())
        assertEquals("the Scene itself stays", 1L, scalar("SELECT COUNT(*) FROM scene WHERE sceneId = 'sMixed'"))
        assertEquals("with its surviving Moment", 1L, scalar("SELECT COUNT(*) FROM scene_part WHERE sceneId = 'sMixed'"))
    }

    @Test fun f3FlagOffOnlyHidesTheField() = runBlocking {
        ForgetV2.testOverride = false
        w.seed(); seedSceneField()
        w.service.forgetMoment("fB")
        assertEquals("v1: the row stays, hidden", 1L, sceneRows()); assertEquals(1L, scalar("SELECT COUNT(*) FROM object_derived WHERE objectKind = 'SCENE' AND objectId = 'sMixed' AND staleSince IS NOT NULL"))
    }

    @Test fun f3AnEmptySceneIsDroppedWithItsFields() = runBlocking {
        ForgetV2.testOverride = true
        w.seed(); db.objectDao().writeDerivedTx(ObjectDerivedEntity("SCENE", "sNear", "SUMMARY", "OLD", null, 1, null, w.tA), "build")
        w.service.forgetMoment("fA") // sNear held momA and momB, both go
        assertEquals(0L, scalar("SELECT COUNT(*) FROM scene WHERE sceneId = 'sNear'")); assertEquals(0L, scalar("SELECT COUNT(*) FROM object_derived WHERE objectId = 'sNear'"))
    }

    @Test fun f3PlanNamesTheRebuildAndTheErasedField() = runBlocking {
        ForgetV2.testOverride = true
        w.seed(); seedSceneField()
        val plan = ForgetPlanner(db).plan(setOf(Ref(Family.PHOTO, "fB")), Policy.User)
        assertEquals(Fate.REBUILD, plan.nodes[Ref(Family.SCENE, "sMixed")]); assertEquals(Fate.DROP, plan.nodes[Ref(Family.DERIVED_FIELD, "SCENE:sMixed")])
        ForgetV2.testOverride = false
        assertEquals("v1 plan: the field is only hidden (REBUILD)", Fate.REBUILD, ForgetPlanner(db).plan(setOf(Ref(Family.PHOTO, "fB")), Policy.User).nodes[Ref(Family.DERIVED_FIELD, "SCENE:sMixed")])
    }

    // ---- F6 ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    private suspend fun seedSideEffects() {
        w.seed()
        val s3 = db.canvasSlice3Dao()
        s3.insertReplay(CanvasReplayEntity("rp1", null, "{}", "EXACT", resultJson = "{\"secret\":1}", jobState = "DONE", fromMs = w.tA, toMs = w.tA + 500, createdAt = 1))
        s3.insertSweep(CanvasSweepEntity("sw1", "{}", resultJson = "{\"secret\":2}", jobState = "DONE", fromMs = w.tA, toMs = w.tA + 500, createdAt = 1))
        s3.insertHelperJob(CanvasHelperJobEntity("job1", "py_exec", "QUEUED", frameIdsJson = "[\"fA\"]", createdAt = 1, updatedAt = 1))
        db.momentDao().replaceSensor(w.momA, listOf(MomentSensorEntity(w.momA, w.tA / 1000, "trNear", 1, "OFFSET_ESTIMATED", 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "STILL", null, 1, 1)), 1, "build", hide = false)
    }
    private val sideEffects = setOf(Ref(Family.RUN_RESULT, "replay:rp1"), Ref(Family.RUN_RESULT, "sweep:sw1"), Ref(Family.RUN_RESULT, "job:job1"), Ref(Family.MOMENT, "sensor:${"68f6baa4"}"))

    @Test fun f6RangePlanNamesReplaysSweepsHelperJobsAndSensorRows() = runBlocking {
        ForgetV2.testOverride = true; seedSideEffects()
        val plan = ForgetPlanner(db).planRange(w.tA, w.tB)
        assertTrue("all four are planned: ${plan.render()}", plan.nodes.keys.containsAll(sideEffects))
        val planned = plan.nodes.keys.filter { SideEffectRefs.isSideEffect(it) }.toSet()
        w.fx.forget.forgetRange(w.tA, w.tB)
        // preview == delete: what the plan named is exactly what the Forget changed
        assertEquals(1L, scalar("SELECT COUNT(*) FROM canvas_replay WHERE replayId = 'rp1' AND invalidated = 1 AND resultJson IS NULL"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM canvas_sweep WHERE sweepId = 'sw1' AND invalidated = 1 AND resultJson IS NULL"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM canvas_helper_job WHERE jobId = 'job1' AND state = 'VOIDED'"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM moment_sensor"))
        assertEquals(sideEffects, planned)
    }

    @Test fun f6FlagOffPlanIsTheV1PlanWithoutSideEffects() = runBlocking {
        ForgetV2.testOverride = false; seedSideEffects()
        assertTrue(ForgetPlanner(db).planRange(w.tA, w.tB).nodes.keys.none { SideEffectRefs.isSideEffect(it) })
    }

    @Test fun f6ThePlanHashChangesAndAnOldConfirmationTokenIsInvalid() = runBlocking {
        seedSideEffects()
        ForgetV2.testOverride = false
        val v1 = w.fx.forget.preview(listOf(w.tA..w.tA + 999)); assertEquals(0, v1.sideEffects)
        ForgetV2.testOverride = true
        val v2 = w.fx.forget.preview(listOf(w.tA..w.tA + 999))
        assertNotEquals("the plan hash changed with the plan", v1.planHash, v2.planHash)
        assertTrue("the preview counts the side effects it will invalidate", v2.sideEffects >= 4)
        try { w.fx.forget.forgetSpans(listOf(w.tA..w.tA + 999), expectedHash = v1.planHash); throw AssertionError("an old token must not authorise the new plan") } catch (_: ForgetContentChanged) { }
        assertEquals("nothing was deleted by the refused token", 1L, scalar("SELECT COUNT(*) FROM canvas_replay WHERE invalidated = 0"))
        w.fx.forget.forgetSpans(listOf(w.tA..w.tA + 999), expectedHash = v2.planHash) // a fresh preview works
        assertEquals(1L, scalar("SELECT COUNT(*) FROM canvas_replay WHERE invalidated = 1"))
    }

    @Test fun f6FlagOffHashAndPreviewAreUnchanged() = runBlocking {
        seedSideEffects(); ForgetV2.testOverride = false
        val a = w.fx.forget.preview(listOf(w.tA..w.tA + 999)); val b = w.fx.forget.preview(listOf(w.tA..w.tA + 999))
        assertEquals(a.planHash, b.planHash); assertEquals(0, a.rebuiltPhotos)
        w.fx.forget.forgetSpans(listOf(w.tA..w.tA + 999), expectedHash = a.planHash); Unit
    }

    @Test fun aRangeForgetRebuildsASurvivorOnlyWhenNoDescriptionIsLeft() = runBlocking {
        ForgetV2.testOverride = true
        mixedWorld()
        val before = db.forgetDao().tombstoned(listOf("pY"))
        assertTrue(before.isEmpty())
        w.fx.forget.forgetRange(w.tA, w.tA + 999) // forgets pX only (pY and pB are far away)
        assertTrue("pY is not in the range: preserved", db.frameDao().all().any { it.id == "pY" }); assertTrue(db.forgetDao().tombstoned(listOf("pY")).isEmpty())
        assertEquals(ForgetDao.SOLO_REBUILD, state("pY")!!.lastError)
        val plan = ForgetPlanner(db).planRange(w.tA, w.tA + 999)
        assertNotNull(plan)
    }

    @Test fun thePlannerSurvivorSetEqualsWhatTheForgetRebuilt() = runBlocking {
        ForgetV2.testOverride = true
        mixedWorld()
        val plan = ForgetPlanner(db).plan(setOf(Ref(Family.PHOTO, "pX")), Policy.User)
        assertEquals("preview names the photos that are rebuilt, and none is erased but pX", setOf("pY", "pB"), plan.refs(Family.PHOTO, Fate.REBUILD)); assertEquals(setOf("pX"), plan.refs(Family.PHOTO, Fate.ERASE))
        assertEquals(setOf("mXY", "mB"), plan.refs(Family.MEMORY, Fate.ERASE))
        w.service.forgetMoment("pX")
        val queued = db.frameStateDao().byIds(listOf("pY", "pB")).filter { it.lastError == ForgetDao.SOLO_REBUILD }.map { it.frameId }.toSet()
        assertEquals(plan.refs(Family.PHOTO, Fate.REBUILD), queued)
    }

    // ---- r2 P1-2: the descendants of an erased caption keep no text -----------------------------------------------------------------------------------------------------------------------

    private suspend fun seedDescendant() {
        mixedWorld()
        db.memoryDao().insert(com.rokid.context.memory.db.MemoryEntity("mSum", null, "zebracrossing summary", "summary", "zebracrossing tag", com.rokid.context.memory.db.MemoryTier.SHORT, 0.8, com.rokid.context.memory.db.ReviewState.OK, false, 7, true, "zebracrossing summary zebracrossing tag"))
        w.fx.derivedFrom("mSum", "mB")
    }
    private suspend fun ftsHits() = scalar("SELECT COUNT(*) FROM memory_fts WHERE memory_fts MATCH 'zebracrossing'")

    @Test fun r2DescendantsOfAnErasedCaptionKeepNoTextTagsSearchTextOrFtsTerms() = runBlocking {
        ForgetV2.testOverride = true
        seedDescendant(); assertEquals("indexed before", 1L, ftsHits())
        w.service.forgetMoment("pX")
        val m = db.forgetDao().memories(listOf("mSum")).single()
        assertEquals(com.rokid.context.memory.db.ReviewState.INVALIDATED, m.reviewState)
        assertEquals("", m.text); assertEquals("", m.tags); assertEquals("", m.searchText)
        assertEquals("the forgotten term is not searchable", 0L, ftsHits())
        assertEquals("the placeholder keeps what the re-summarise worker reads", 7L, m.createdAt); assertTrue(m.derived)
        assertEquals("and has no vectors", 0L, scalar("SELECT COUNT(*) FROM memory_vector WHERE memoryId = 'mSum'"))
    }

    @Test fun r2FlagOffStillKeepsTheHiddenTextAsV1() = runBlocking {
        ForgetV2.testOverride = false
        seedDescendant()
        w.service.forgetMoment("pX")
        val m = db.forgetDao().memories(listOf("mSum")).single()
        assertEquals(com.rokid.context.memory.db.ReviewState.INVALIDATED, m.reviewState); assertEquals("zebracrossing summary", m.text)
    }
}
