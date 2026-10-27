package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.MS
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.SEC
import com.rokid.context.memory.moment.MomentId
import kotlinx.coroutines.runBlocking
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
 * Forget SCOPE (Photo | Moment | Scene) on every target. World: Scene A = Moments at 0 s (photo a1, memory ma1, mark La), 3 s (a2, ma2) and 25 s (a3, ma3); Scene N = Moment at 90 s (n1, mn1) and a mark at 91 s.
 * The default photo Forget radius is 10 s: from 0 s it reaches 3 s but not 25 s.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScopedForgetTest {
    private lateinit var db: com.rokid.context.memory.db.ContextDatabase
    private lateinit var fx: MomentForgetFixture
    private lateinit var s3: com.rokid.context.memory.db.CanvasSlice3Dao
    private lateinit var engine: ScopedForget
    private fun fresh() { if (this::db.isInitialized) db.close(); db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext()); fx = MomentForgetFixture(db); s3 = db.canvasSlice3Dao(); engine = ScopedForget(db, fx.service) { 1_000 } }
    @org.junit.Before fun open() = fresh()
    @After fun close() { ForgetRadius.provider = { ForgetRadius.DEFAULT_MS }; db.close() }
    private fun mid(sec: Long) = MomentId.format(SEC + sec)

    private suspend fun scene(id: String, vararg moments: Long) = db.sceneDao().replace(
        SceneEntity(id, 1, MS, MS + moments.min() * 1000, MS + moments.max() * 1000 + 999, SceneRule.CAP_5_MIN, membershipRev = 1, builtAt = 1),
        moments.map { ScenePartEntity(id, ScenePartKind.MOMENT, mid(it), "r", MS + it * 1000) })

    private suspend fun world() {
        fx.frame("a1", MS + 5); fx.frame("a2", MS + 3005); fx.frame("a3", MS + 25_005); fx.frame("n1", MS + 90_005)
        fx.memory("ma1", "a1"); fx.memory("ma2", "a2"); fx.memory("ma3", "a3"); fx.memory("mn1", "n1")
        fx.mark("La", MS + 500); fx.mark("Ln", MS + 91_000)
        fx.project(mid(0), MomentPartKind.FRAME to "a1", MomentPartKind.MEMORY to "ma1", MomentPartKind.MARK to "La")
        fx.project(mid(3), MomentPartKind.FRAME to "a2", MomentPartKind.MEMORY to "ma2")
        fx.project(mid(25), MomentPartKind.FRAME to "a3", MomentPartKind.MEMORY to "ma3")
        fx.project(mid(90), MomentPartKind.FRAME to "n1", MomentPartKind.MEMORY to "mn1")
        scene("sc-A", 0, 3, 25); scene("sc-N", 90)
    }
    private suspend fun frames() = db.frameDao().all().map { it.id }.sorted()
    private suspend fun memories() = db.forgetDao().memories(listOf("ma1", "ma2", "ma3", "mn1")).filter { it.reviewState != com.rokid.context.memory.db.ReviewState.INVALIDATED }.map { it.id }.sorted()
    private suspend fun marks() = s3.labels().map { it.labelId }.sorted()
    private suspend fun moments() = db.momentDao().allMoments().map { it.momentId }.sorted()
    private suspend fun scenes() = db.sceneDao().allScenes().map { it.sceneId }.sorted()
    private suspend fun run(target: ForgetTarget, scope: ForgetScope) = engine.forget(engine.plan(target, scope))

    // ---- PHOTO ----
    @Test fun photoScopeDeletesExactlyThatPhotoAndItsRadiusAndFencesNothing() = runBlocking<Unit> {
        world(); val d = run(ForgetTarget.Photo("a1"), ForgetScope.PHOTO)
        assertEquals(ForgetScope.PHOTO, d.plan.effective)
        assertEquals("neighbouring photos survive", listOf("a2", "a3", "n1"), frames()); assertEquals(listOf("ma2", "ma3", "mn1"), memories())
        assertEquals("the mark in the radius goes as today", listOf("Ln"), marks())
        assertEquals("the Moments of the radius are rebuildable and dropped", listOf(mid(25), mid(90)), moments())
        assertFalse("PHOTO scope fences nothing", s3.momentFenced(MS, MS + 999)); assertEquals(0, db.momentDao().tombstoneCount())
        assertEquals(listOf("sc-A", "sc-N"), scenes())
    }

    @Test fun aFactAtPhotoScopeIsExactlyTodaysForgetFactWithNoSecondFence() = runBlocking<Unit> {
        world(); val d = run(ForgetTarget.Fact("ma1"), ForgetScope.PHOTO)
        assertEquals(listOf("ma2", "ma3", "mn1"), memories()); assertEquals("its photo file goes, the row stays locked, as today", listOf("/p/a1.jpg"), d.cascade.framesToDelete.filter { it.endsWith(".jpg") }); assertEquals(listOf("a1", "a2", "a3", "n1"), frames())
        assertEquals(0, db.momentDao().tombstoneCount()); assertTrue(s3.momentForgetRanges().isEmpty())
    }

    // ---- MOMENT ----
    @Test fun momentScopeOnAPhotoDeletesTheWholeMomentWindowWithTheRadiusAndFences() = runBlocking<Unit> {
        world(); val d = run(ForgetTarget.Photo("a1"), ForgetScope.MOMENT)
        assertEquals(ForgetScope.MOMENT, d.plan.effective)
        assertEquals(listOf("a3", "n1"), frames()); assertEquals(listOf("ma3", "mn1"), memories()); assertEquals(listOf("Ln"), marks())
        assertEquals(listOf(mid(25), mid(90)), moments()); assertEquals("Scene A shrinks to what is left, N is untouched", listOf("sc-A", "sc-N"), scenes())
        assertEquals(listOf(mid(25)), db.sceneDao().parts("sc-A").map { it.refId })
        assertTrue(s3.momentFenced(MS, MS + 999)); assertTrue(s3.momentFenced(MS - 10_000, MS - 9_000)); assertTrue(s3.momentFenced(MS + 10_000, MS + 10_999)); assertFalse(s3.momentFenced(MS + 11_000, MS + 11_000))
    }

    @Test fun momentScopeOnAFactForgetsTheMomentOfItsPhoto() = runBlocking<Unit> {
        world(); run(ForgetTarget.Fact("ma2"), ForgetScope.MOMENT)
        assertEquals("the window of 3 s reaches 0 s too", listOf("a3", "n1"), frames()); assertEquals(listOf("Ln"), marks())
    }

    /** Review fix: the planned span already includes the radius; a photo at its EDGE must not cascade its own radius to marks and Moments outside the span (deletion set == preview set). */
    @Test fun aPhotoAtTheEdgeOfTheSpanDoesNotCascadeItsOwnRadiusPastTheSpan() = runBlocking<Unit> {
        world(); fx.frame("edge", MS + 10_500); fx.mark("Lout", MS + 20_000); fx.memory("medge", "edge")
        fx.project(mid(20), MomentPartKind.MARK to "Lout")
        val p = engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT); val counts = engine.preview(p)
        engine.forget(p)
        assertFalse("the edge photo is inside the span and goes", "edge" in frames()); assertTrue("the mark 20 s away is outside it and stays", "Lout" in marks())
        assertTrue("so does its Moment", mid(20) in moments())
        assertEquals("the preview named exactly what went: marks (La only)", 1, counts.marks); assertEquals(counts.photos, 3) // a1, a2, edge
    }

    // ---- review fix: the confirm deletes EXACTLY what the preview showed, or nothing ----
    private suspend fun refused(plan: ScopedForget.Plan, hash: String) { try { engine.forget(plan, hash); org.junit.Assert.fail("expected ForgetContentChanged") } catch (e: ForgetContentChanged) { } }

    @Test fun theConfirmDeletesTheSetThatWasPreviewedWhenNothingChanged() = runBlocking<Unit> {
        ForgetScope.values().forEach { sc ->
            fresh(); world(); val c = engine.preview(engine.plan(ForgetTarget.Photo("a1"), sc)); assertTrue(c.planHash.isNotEmpty())
            engine.forget(engine.plan(ForgetTarget.Photo("a1"), sc), c.planHash); assertFalse("$sc", "a1" in frames())
        }
    }

    @Test fun aPhotoThatArrivedInsideTheSpanAfterThePreviewIsNeverDeletedUncounted() = runBlocking<Unit> {
        world(); val c = engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT))
        fx.frame("late", MS + 6_000)
        refused(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT), c.planHash)
        assertEquals("nothing was deleted and nothing was fenced", listOf("a1", "a2", "a3", "late", "n1"), frames()); assertEquals(0, db.momentDao().tombstoneCount()); assertTrue(s3.momentForgetRanges().isEmpty())
        val again = engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT)); assertEquals(c.photos + 1, again.photos)
        engine.forget(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT), again.planHash); assertFalse("late" in frames())
    }

    @Test fun aSceneMembershipChangeAfterThePreviewIsRefused() = runBlocking<Unit> {
        world(); db.sceneDao().remove(listOf("sc-A"))
        val c = engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.SCENE)) // previewed as a MOMENT (no Scene yet)
        scene("sc-A", 0, 3, 25) // the Scene appears: the same confirm would now be the whole Scene
        refused(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.SCENE), c.planHash)
        assertEquals(listOf("a1", "a2", "a3", "n1"), frames())
    }

    @Test fun aMarkThatArrivedInTheRadiusAfterAPhotoPreviewIsRefusedAndSoIsAChangedRadius() = runBlocking<Unit> {
        world(); val c = engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.PHOTO))
        fx.mark("Lnew", MS + 4_000); refused(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.PHOTO), c.planHash); assertTrue("Lnew" in marks()); assertTrue("a1" in frames())
        val c2 = engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.PHOTO)); ForgetRadius.provider = { 30_000L }
        refused(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.PHOTO), c2.planHash); assertTrue("a1" in frames())
    }

    @Test fun aFactPreviewIsPinnedToo() = runBlocking<Unit> {
        world(); val c = engine.preview(engine.plan(ForgetTarget.Fact("ma1"), ForgetScope.PHOTO)); fx.mark("Lnew", MS + 4_000)
        refused(engine.plan(ForgetTarget.Fact("ma1"), ForgetScope.PHOTO), c.planHash); assertEquals(listOf("ma1", "ma2", "ma3", "mn1"), memories())
    }

    @Test fun noHashMeansNoCheckAndTheHashNamesTheScope() = runBlocking<Unit> {
        world()
        val hashes = ForgetScope.values().map { engine.preview(engine.plan(ForgetTarget.Photo("a1"), it)).planHash }
        assertEquals("one hash per distinct set", 3, hashes.distinct().size)
        engine.forget(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.PHOTO)); assertFalse("a1" in frames())
    }

    // ---- Codex round 2: plan == delete for Scenes' membership, for photos cited by facts of the span, and for shared photos ----
    @Test fun aMomentAddedToAnAffectedSceneAfterThePreviewIsRefusedWhoseRemovalOutcomeDidNotChange() = runBlocking<Unit> {
        ForgetScope.values().forEach { sc ->
            fresh(); world(); val c = engine.preview(engine.plan(ForgetTarget.Photo("a1"), sc))
            db.sceneDao().insertParts(listOf(ScenePartEntity("sc-A", ScenePartKind.MOMENT, mid(90), "r", MS + 90_000))) // the builder put an existing Moment into the Scene
            refused(engine.plan(ForgetTarget.Photo("a1"), sc), c.planHash); assertTrue("$sc: nothing deleted", "a1" in frames())
        }
    }

    /** Round 3 design: BOUNDED exact-span. An outside photo cited by an in-span fact is KEPT (only locked); nothing is followed transitively. */
    @Test fun aPhotoOutsideTheSpansThatAnInSpanFactCitesIsKeptAndOnlyCountedAsAffected() = runBlocking<Unit> {
        world(); db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity("n1", "g1_n1", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = MS + 90_005))
        fx.memory("mx", "n1", createdAt = MS + 500) // a fact born in the span that cites the photo 90 s away
        val p = engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT); val c = engine.preview(p)
        assertEquals("a1 and a2 only", 2, c.photos); assertEquals("ma1, ma2 and mx; n1's own mn1 is NOT reached", 3, c.memories); assertEquals("n1 is outside and affected", 1, c.outside)
        val done = engine.forget(p, c.planHash)
        assertEquals("n1 and a3 are kept", listOf("a3", "n1"), frames()); assertEquals("mx is gone, n1's own fact stays", listOf("ma3", "mn1"), memories())
        assertTrue("n1 is only locked", "n1" in db.forgetDao().tombstoned(listOf("n1")))
        assertTrue("no file of n1 is deleted", done.cascade.framesToDelete.none { it.contains("n1") }); assertTrue(fx.outbox().none { it.second.contains("n1") })
        assertTrue("n1's Moment survives", mid(90) in moments())
    }

    @Test fun aNewInSpanFactCitingAnotherOutsidePhotoAfterThePreviewIsRefused() = runBlocking<Unit> {
        world(); val c = engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT)); fx.memory("mx", "a3", createdAt = MS + 500)
        refused(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT), c.planHash); assertTrue("a3" in frames())
    }

    /** Round 4 (1): a mark linked to an in-span photo is deleted with the photo whatever its own time, so it is counted and hashed (preview == delete). */
    @Test fun aMarkLinkedToAnInSpanPhotoButTimedOutsideTheSpanIsInThePreviewAndTheDelete() = runBlocking<Unit> {
        world(); s3.insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("Llinked", "MOMENT", "tap", "X", MS + 60_000, frameId = "a1", createdAt = 1), com.rokid.context.memory.db.DaoFence(0, com.rokid.context.memory.db.DeletionEpoch("i", 1, 0), { 0 }, { com.rokid.context.memory.db.DeletionEpoch("i", 1, 0) }))
        val p = engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT); val c = engine.preview(p)
        assertEquals("La and Llinked", 2, c.marks)
        val before = marks().size; engine.forget(p, c.planHash)
        assertEquals("exactly the previewed marks went", c.marks, before - marks().size); assertTrue("Ln stays", "Ln" in marks()); assertFalse("Llinked" in marks())
    }

    @Test fun aDerivedFactThatAppearsAfterThePreviewIsRefused() = runBlocking<Unit> {
        world(); val c = engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT))
        fx.memory("dnew", null, derived = true, createdAt = MS + 300_000); fx.derivedFrom("dnew", "ma1")
        refused(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT), c.planHash)
    }

    @Test fun aDerivedFactElsewhereIsHiddenNotDeletedAndItsMomentSurvivesAndRebuilds() = runBlocking<Unit> {
        world(); fx.memory("dsum", null, derived = true, createdAt = MS + 200_000); fx.derivedFrom("dsum", "ma1")
        db.momentDao().replace(com.rokid.context.memory.db.MomentEntity(mid(200), 1, 1, "h", 1, com.rokid.context.memory.moment.MomentType.FRAME, text = "TEXT OF dsum"), listOf(com.rokid.context.memory.db.MomentPartEntity(mid(200), MomentPartKind.MEMORY, "dsum", "r", 1)))
        val c = engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT)); assertEquals("the derived fact is outside and affected", 1, c.outside)
        engine.forget(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT), c.planHash)
        assertEquals(com.rokid.context.memory.db.ReviewState.INVALIDATED, db.forgetDao().memories(listOf("dsum")).single().reviewState)
        assertTrue("the remote Moment survives", mid(200) in moments()); assertEquals("but its text copy is gone", null, db.momentDao().moment(mid(200))!!.text)
    }

    @Test fun aSharedPhotoIsLockedAndRadiusCleanedSoItsWindowIsInThePlan() = runBlocking<Unit> {
        world(); fx.memory("ma1b", "a1") // a second fact of the same photo: forgetting ma1 locks a1 but keeps its file
        val c = engine.preview(engine.plan(ForgetTarget.Fact("ma1"), ForgetScope.PHOTO))
        assertEquals("the shared photo stays", 0, c.photos); assertEquals("its radius still removes the mark La", 1, c.marks)
        fx.mark("Llate", MS + 4_000) // arrives in the shared photo's radius after the preview
        refused(engine.plan(ForgetTarget.Fact("ma1"), ForgetScope.PHOTO), c.planHash); assertTrue("Llate" in marks())
        val c2 = engine.preview(engine.plan(ForgetTarget.Fact("ma1"), ForgetScope.PHOTO)); assertEquals(2, c2.marks)
        engine.forget(engine.plan(ForgetTarget.Fact("ma1"), ForgetScope.PHOTO), c2.planHash); assertFalse("Llate" in marks()); assertFalse("La" in marks())
    }

    @Test fun aMomentEntryWithTheSettingOnPhotoIsStillAMomentForget() = runBlocking<Unit> {
        world(); val d = run(ForgetTarget.Moment(mid(0)), ForgetScope.PHOTO)
        assertEquals(ForgetScope.MOMENT, d.plan.effective); assertEquals(listOf("a3", "n1"), frames()); assertTrue(s3.momentFenced(MS, MS + 999))
    }

    // ---- SCENE ----
    @Test fun sceneScopeOnAPhotoDeletesEveryMomentOfItsSceneAndTheSceneItself() = runBlocking<Unit> {
        world(); val d = run(ForgetTarget.Photo("a1"), ForgetScope.SCENE)
        assertEquals(ForgetScope.SCENE, d.plan.effective)
        assertEquals(listOf("n1"), frames()); assertEquals(listOf("mn1"), memories()); assertEquals(listOf("Ln"), marks()); assertEquals(listOf(mid(90)), moments())
        assertEquals(listOf("sc-N"), scenes()); assertTrue(db.sceneDao().parts("sc-A").isEmpty())
        assertTrue(s3.momentFenced(MS + 25_000, MS + 25_999)); assertTrue(s3.momentFenced(MS, MS + 999)); assertFalse(s3.momentFenced(MS + 90_000, MS + 90_999))
    }

    @Test fun theSceneForgetAlsoCoversTheInteriorOfTheSceneThatNoMomentWindowReaches() = runBlocking<Unit> {
        world(); fx.frame("gap", MS + 14_500) // between the windows of the 3 s and the 25 s Moments, in no Moment
        run(ForgetTarget.Photo("a1"), ForgetScope.SCENE)
        assertEquals(listOf("n1"), frames())
    }

    @Test fun aSceneEntryWithTheSettingOnPhotoIsStillASceneForget() = runBlocking<Unit> {
        world(); val d = run(ForgetTarget.Scene("sc-A"), ForgetScope.PHOTO)
        assertEquals(ForgetScope.SCENE, d.plan.effective); assertEquals(listOf("n1"), frames()); assertEquals(listOf("sc-N"), scenes())
    }

    @Test fun sceneScopeOnAFactForgetsItsScene() = runBlocking<Unit> {
        world(); run(ForgetTarget.Fact("ma3"), ForgetScope.SCENE)
        assertEquals(listOf("n1"), frames()); assertEquals(listOf("sc-N"), scenes())
    }

    // ---- fallbacks and edges ----
    @Test fun aMomentInNoSceneIsForgottenAsAMomentWhenTheScopeIsScene() = runBlocking<Unit> {
        world(); db.sceneDao().remove(listOf("sc-A"))
        val d = run(ForgetTarget.Photo("a1"), ForgetScope.SCENE)
        assertEquals(ForgetScope.MOMENT, d.plan.effective); assertEquals(listOf("a3", "n1"), frames())
    }

    @Test fun aFactWithNoPhotoIsForgottenAsAFactAtEveryScope() = runBlocking<Unit> {
        world(); fx.memory("loose", null)
        val d = run(ForgetTarget.Fact("loose"), ForgetScope.SCENE)
        assertEquals(ForgetScope.PHOTO, d.plan.effective); assertEquals(listOf("a1", "a2", "a3", "n1"), frames()); assertEquals(0, db.momentDao().tombstoneCount())
        assertTrue(db.forgetDao().memories(listOf("loose")).isEmpty())
    }

    @Test fun aPhotoWithNoKnownTimeIsForgottenAsAPhoto() = runBlocking<Unit> {
        world(); val p = engine.plan(ForgetTarget.Photo("ghost"), ForgetScope.SCENE)
        assertEquals(ForgetScope.PHOTO, p.effective); assertFalse(p.isEmpty)
    }

    @Test fun aMalformedMomentOrAnUnknownSceneIsANoOp() = runBlocking<Unit> {
        world()
        listOf(ForgetTarget.Moment("zz"), ForgetTarget.Moment("1234567"), ForgetTarget.Scene("nope")).forEach { t ->
            val p = engine.plan(t, ForgetScope.SCENE); assertTrue("$t", p.isEmpty); assertEquals(ForgetService.Result.EMPTY, engine.forget(p).cascade)
        }
        assertEquals(listOf("a1", "a2", "a3", "n1"), frames()); assertEquals(0, db.momentDao().tombstoneCount())
    }

    @Test fun theRadiusSettingWidensTheMomentWindow() = runBlocking<Unit> {
        world(); ForgetRadius.provider = { 30_000L }
        run(ForgetTarget.Photo("a1"), ForgetScope.MOMENT)
        assertEquals("30 s reaches the photo at 25 s", listOf("n1"), frames())
    }

    @Test fun theSettingIsReadWhenNoScopeIsPassed() = runBlocking<Unit> {
        world(); ForgetScopeSetting.provider = { ForgetScope.SCENE }
        try { assertEquals(ForgetScope.SCENE, engine.plan(ForgetTarget.Photo("a1")).effective) } finally { ForgetScopeSetting.provider = { ForgetScope.PHOTO } }
    }

    // ---- counts ----
    @Test fun thePreviewIsExactlyWhatEachScopeThenRemoves() = runBlocking<Unit> {
        data class Case(val target: ForgetTarget, val scope: ForgetScope)
        listOf(Case(ForgetTarget.Photo("a1"), ForgetScope.PHOTO), Case(ForgetTarget.Photo("a1"), ForgetScope.MOMENT), Case(ForgetTarget.Photo("a1"), ForgetScope.SCENE), Case(ForgetTarget.Fact("ma1"), ForgetScope.PHOTO), Case(ForgetTarget.Scene("sc-A"), ForgetScope.SCENE)).forEach { c ->
            fresh(); world()
            val before = listOf(frames().size, memories().size, marks().size, moments().size, scenes().size)
            val p = engine.plan(c.target, c.scope); val counts = engine.preview(p)
            assertEquals("preview wrote nothing for $c", before, listOf(frames().size, memories().size, marks().size, moments().size, scenes().size))
            val done = engine.forget(p)
            val after = listOf(frames().size, memories().size, marks().size, moments().size)
            assertEquals("photos $c", if (c.target is ForgetTarget.Fact) done.cascade.framesToDelete.count { it.endsWith(".jpg") } else before[0] - after[0], counts.photos); assertEquals("memories $c", before[1] - after[1], counts.memories); assertEquals("marks $c", before[2] - after[2], counts.marks)
            assertEquals("moments $c", before[3] - after[3], counts.moments); assertEquals("scenes removed $c", before[4] - scenes().size, counts.scenesRemoved)
        }
    }

    /** Review fix: a Scene that only LOSES Moments is "shrunk", not "deleted"; Scene scope does not count a neighbouring Scene touched only by the radius as removed. */
    @Test fun scenesThatOnlyLoseMomentsAreCountedAsShrunkNotRemoved() = runBlocking<Unit> {
        world()
        val moment = engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT))
        assertEquals(0, moment.scenesRemoved); assertEquals("Scene A keeps its 25 s Moment", 1, moment.scenesShrunk)
        engine.forget(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT)); assertEquals(listOf("sc-A", "sc-N"), scenes())
        fresh(); world(); fx.frame("nb", MS + 8_000); fx.project(mid(8), MomentPartKind.FRAME to "nb"); scene("sc-NB", 8, 60)
        val scene = engine.preview(engine.plan(ForgetTarget.Scene("sc-A"), ForgetScope.SCENE))
        assertEquals("only Scene A is removed; the neighbour inside the radius merely shrinks", listOf(1, 1), listOf(scene.scenesRemoved, scene.scenesShrunk))
    }

    @Test fun theSceneCountNamesTheScenesThatGo() = runBlocking<Unit> {
        world(); assertEquals(1, engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.SCENE)).scenesRemoved)
        assertEquals(1, engine.preview(engine.plan(ForgetTarget.Scene("sc-A"), ForgetScope.SCENE)).scenesRemoved)
    }

    // ---- listeners ----
    @Test fun theFileAndGlassesListenersHearEachSpanAfterTheCommit() = runBlocking<Unit> {
        world(); val heard = ArrayList<Pair<Long, Long>>()
        val l = object : ForgetListener { override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { heard += fromMs to toMs } }
        ForgetListeners.register(l)
        try { run(ForgetTarget.Photo("a1"), ForgetScope.SCENE) } finally { ForgetListeners.unregister(l) }
        assertTrue("the Scene's span was announced: $heard", heard.any { it.first <= MS && it.second >= MS + 25_999 })
    }
}
