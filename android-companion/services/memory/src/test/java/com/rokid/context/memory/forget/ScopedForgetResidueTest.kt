package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.ObjectDerivedEntity
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.MS
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.SEC
import com.rokid.context.memory.forget.lifecycle.Fate
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.ForgetPlanner
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.moment.MomentId
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Forget lifecycle r2 P1-3 (F3, `forgetV2`): a scoped Forget invalidates a derived summary elsewhere, blanks its surviving Moment and the Moment's and Scene's derived fields (summary, caption, embedding) are ERASED (v1: only hidden),
 * and the containing Scene is in the rebuild set of the plan (preview == delete).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScopedForgetResidueTest {
    private lateinit var db: com.rokid.context.memory.db.ContextDatabase
    private lateinit var fx: MomentForgetFixture
    private lateinit var engine: ScopedForget
    @Before fun open() { ForgetScopeSetting.experimentalAllowed = { true }; db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext()); fx = MomentForgetFixture(db); engine = ScopedForget(db, fx.service) { 1_000 } }
    @After fun close() { ForgetV2.testOverride = null; Leases.shared.revokeAll(); ForgetScopeSetting.experimentalAllowed = { false }; db.close() }
    private fun mid(sec: Long) = MomentId.format(SEC + sec)
    private suspend fun scalar(sql: String): Long = db.query(SimpleSQLiteQuery(sql)).use { c -> c.moveToFirst(); c.getLong(0) }

    private suspend fun world() {
        fx.frame("a1", MS + 5); fx.memory("ma1", "a1")
        fx.project(mid(0), MomentPartKind.FRAME to "a1", MomentPartKind.MEMORY to "ma1")
        fx.memory("dsum", null, derived = true, createdAt = MS + 200_000); fx.derivedFrom("dsum", "ma1")
        db.momentDao().replace(com.rokid.context.memory.db.MomentEntity(mid(200), 1, 1, "h", 1, com.rokid.context.memory.moment.MomentType.FRAME, text = "TEXT OF dsum"), listOf(com.rokid.context.memory.db.MomentPartEntity(mid(200), MomentPartKind.MEMORY, "dsum", "r", 1)))
        fx.project(mid(210), MomentPartKind.MARK to "Lx")
        db.sceneDao().replace(SceneEntity("sc-R", 1, MS, MS + 200_000, MS + 210_999, SceneRule.CAP_5_MIN, membershipRev = 1, builtAt = 1),
            listOf(200L, 210L).map { ScenePartEntity("sc-R", ScenePartKind.MOMENT, mid(it), "r", MS + it * 1000) })
        val o = db.objectDao()
        o.writeDerivedTx(ObjectDerivedEntity("MOMENT", mid(200), "SUMMARY", "forgotten summary text", null, 1, null, 1), "build")
        o.writeDerivedTx(ObjectDerivedEntity("MOMENT", mid(200), "CAPTION", "forgotten caption text", null, 1, null, 1), "build")
        o.writeDerivedTx(ObjectDerivedEntity("SCENE", "sc-R", "SUMMARY", "scene text with forgotten summary", null, 1, null, 1), "build")
    }
    private suspend fun momentRows() = scalar("SELECT COUNT(*) FROM object_derived WHERE objectKind = 'MOMENT' AND objectId = '${mid(200)}'")
    private suspend fun sceneRows() = scalar("SELECT COUNT(*) FROM object_derived WHERE objectKind = 'SCENE' AND objectId = 'sc-R'")

    @Test fun theForgottenSummaryLeavesNoDerivedTextOnTheMomentOrItsSceneAndThePlanNamesIt() = runBlocking<Unit> {
        ForgetV2.testOverride = true
        world()
        val target = ForgetTarget.Photo("a1")
        val plan = ForgetPlanner(db).planScoped(listOf(MS..MS + 999))
        assertEquals("the Scene that holds the surviving Moment is rebuilt", Fate.REBUILD, plan.nodes[Ref(Family.SCENE, "sc-R")])
        assertEquals(Fate.DROP, plan.nodes[Ref(Family.DERIVED_FIELD, "SCENE:sc-R")]); assertEquals(Fate.DROP, plan.nodes[Ref(Family.DERIVED_FIELD, "MOMENT:${mid(200)}")])
        val p = engine.plan(target, ForgetScope.MOMENT); val c = engine.preview(p); engine.forget(p, c.planHash)
        assertEquals("the Moment survives, blank", null, db.momentDao().moment(mid(200))!!.text)
        assertEquals("its derived fields are erased, not hidden", 0L, momentRows())
        assertEquals("the Scene keeps its structure", 2L, scalar("SELECT COUNT(*) FROM scene_part WHERE sceneId = 'sc-R'"))
        assertEquals("and loses its derived text", 0L, sceneRows())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM object_derived WHERE value LIKE '%forgotten%'"))
    }

    @Test fun flagOffOnlyHidesThemAndThePlanDoesNotRebuildTheScene() = runBlocking<Unit> {
        ForgetV2.testOverride = false
        world()
        val plan = ForgetPlanner(db).planScoped(listOf(MS..MS + 999))
        assertNull(plan.nodes[Ref(Family.SCENE, "sc-R")]); assertNull(plan.nodes[Ref(Family.DERIVED_FIELD, "MOMENT:${mid(200)}")])
        val p = engine.plan(ForgetTarget.Photo("a1"), ForgetScope.MOMENT); engine.forget(p, engine.preview(p).planHash)
        assertEquals(2L, momentRows()); assertEquals(1L, sceneRows())
        assertTrue("hidden (stale) as v1", scalar("SELECT COUNT(*) FROM object_derived WHERE objectKind = 'MOMENT' AND staleSince IS NOT NULL") > 0)
    }
}
