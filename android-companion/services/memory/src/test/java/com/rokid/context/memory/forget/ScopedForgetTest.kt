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
            assertEquals("moments $c", before[3] - after[3], counts.moments); assertTrue("scenes $c", before[4] - scenes().size <= counts.scenes)
        }
    }

    @Test fun theSceneCountNamesTheScenesThatGo() = runBlocking<Unit> {
        world(); assertEquals(1, engine.preview(engine.plan(ForgetTarget.Photo("a1"), ForgetScope.SCENE)).scenes)
        assertEquals(1, engine.preview(engine.plan(ForgetTarget.Scene("sc-A"), ForgetScope.SCENE)).scenes)
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
