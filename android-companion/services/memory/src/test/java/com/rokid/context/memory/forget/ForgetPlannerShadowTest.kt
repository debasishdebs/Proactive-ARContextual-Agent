package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.ForgetPlan
import com.rokid.context.memory.forget.lifecycle.ForgetPlanner
import com.rokid.context.memory.forget.lifecycle.Policy
import com.rokid.context.memory.forget.lifecycle.Ref
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Forget lifecycle M2: the planner in SHADOW mode. For every current Forget path the planner computes its set BEFORE the real code runs (and must not write), the real code then runs on the same world, and the planner's
 * refs must equal the refs read off the real code's row delta ([GoldenDeleteSetTest] pins that delta). A difference means the planner (or the design) disagrees with v1 and needs a ruling before M4 switches that path.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetPlannerShadowTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val w = GoldenWorld(db)
    private val planner = ForgetPlanner(db)
    @After fun close() = db.close()

    /** Tables whose rows are control data, fences, counters or the edges and parts of a node that is itself compared: no ref of their own. */
    private val ignored = setOf(
        "canvas_meta", "cleanup_outbox", "canvas_helper_cleanup", "moment_tombstone", "action_log", "frame_source", "memory_source", "memory_vector", "moment_part", "scene_part",
        "forget_tombstone", "frame_vector", "frame_state", "frame_cluster",
    )

    /** The refs the real code's delta amounts to, rendered like [ForgetPlan.render]. An unclassified table that changed fails: a new effect needs a decision. */
    private fun refsOf(d: DbSnapshot.Delta): String {
        val out = HashMap<Ref, com.rokid.context.memory.forget.lifecycle.Fate>()
        fun put(f: Family, id: String, fate: com.rokid.context.memory.forget.lifecycle.Fate) { val r = Ref(f, id); val o = out[r]; if (o == null || fate.ordinal < o.ordinal) out[r] = fate }
        val erase = com.rokid.context.memory.forget.lifecycle.Fate.ERASE; val rebuild = com.rokid.context.memory.forget.lifecycle.Fate.REBUILD; val drop = com.rokid.context.memory.forget.lifecycle.Fate.DROP
        for (t in (d.deleted.keys + d.changed.keys + d.added.keys).toSortedSet()) {
            val del = d.deletedKeys(t); val chg = d.changedKeys(t); val add = d.addedKeys(t)
            if (t in ignored && !(t == "forget_tombstone" && add.isNotEmpty())) continue
            when (t) {
                "forget_tombstone" -> add.forEach { put(Family.PHOTO, it, erase) }
                "frame", "frame_meta" -> del.forEach { put(Family.PHOTO, it, erase) }
                "memory" -> { del.forEach { put(Family.MEMORY, it, erase) }; chg.forEach { put(Family.MEMORY, it, rebuild) } }
                "ask" -> chg.forEach { put(Family.ANSWER, it, rebuild) }
                "nudge" -> chg.forEach { put(Family.NUDGE, it, rebuild) }
                "proposal" -> chg.forEach { put(Family.PROPOSAL, it, rebuild) }
                "canvas_label" -> del.forEach { put(Family.MARK, it, erase) }
                "canvas_trace" -> chg.forEach { put(Family.TRACE, it, erase) }
                "canvas_run_item", "canvas_score", "canvas_live_trace" -> del.forEach { put(Family.RUN_RESULT, it.split('|')[1], drop) }
                "moment" -> { del.forEach { put(Family.MOMENT, it, drop) }; chg.forEach { put(Family.MOMENT, it, rebuild) } }
                "scene" -> { del.forEach { put(Family.SCENE, it, drop) }; chg.forEach { put(Family.SCENE, it, rebuild) } }
                "object_derived", "object_audit" -> {
                    del.forEach { k -> k.split('|').let { put(Family.DERIVED_FIELD, "${it[0]}:${it[1]}", drop) } }
                    if (t == "object_derived") chg.forEach { k -> k.split('|').let { put(Family.DERIVED_FIELD, "${it[0]}:${it[1]}", rebuild) } }
                }
                "object_audit " -> Unit
                else -> if (del.isNotEmpty() || chg.isNotEmpty()) throw AssertionError("table $t changed (deleted $del, changed $chg) and the shadow test has no ref for it: classify it")
            }
        }
        return ForgetPlan(out).render()
    }

    private fun shadow(plan: suspend () -> ForgetPlan, op: suspend () -> Unit) = runBlocking {
        w.seed()
        val before = DbSnapshot.of(db)
        val planned = plan()
        assertTrue("planning writes nothing", before.deltaTo(DbSnapshot.of(db)).render().isEmpty())
        val real = refsOf(w.deltaOf(op))
        assertEquals("planner set == what the real code deleted (planned left, real right)", real, planned.render())
        assertTrue("the plan is not trivially empty", planned.size > 0)
    }

    private fun roots(vararg r: Ref) = r.toSet()
    private val A = Ref(Family.MEMORY, "mA")

    @Test fun forgetFact() = shadow({ planner.plan(roots(A), Policy.User) }) { w.service.forgetFact("mA") }
    @Test fun forgetPhoto() = shadow({ planner.plan(roots(Ref(Family.PHOTO, "fA")), Policy.User) }) { w.service.forgetMoment("fA") }
    @Test fun forgetFarPhotoCitedByAMultiPhotoFact() = shadow({ planner.plan(roots(Ref(Family.PHOTO, "fFar")), Policy.User) }) { w.service.forgetMoment("fFar") }
    @Test fun forgetBatchPhoto() = shadow({ planner.plan(roots(Ref(Family.PHOTO, "fB")), Policy.User) }) { w.service.forgetMoment("fB") }
    @Test fun markUndo() = shadow({ planner.plan(roots(Ref(Family.MARK, "mkNear"), Ref(Family.MARK, "mkNear:i")), Policy.User) }) {
        w.db.canvasSlice3Dao().deleteLabel("mkNear"); w.db.canvasSlice3Dao().deleteLabel("mkNear:i")
    }
    @Test fun rangeForget() = shadow({ planner.planRange(w.tA, w.tB) }) { w.fx.forget.forgetRange(w.tA, w.tB) }
    @Test fun momentForgetById() = shadow({ planner.planMoments(listOf(w.momA)) }) { w.fx.forget.forgetMoments(listOf(w.momA)) }
    @Test fun momentScopeSpans() = shadow({ planner.planScoped(listOf(w.tA..w.tA + 999)) }) { w.fx.forget.forgetSpans(listOf(w.tA..w.tA + 999)) }
    @Test fun sceneScopeSpans() = shadow({ planner.planScoped(listOf(w.tA..w.tB + 999), listOf("sNear")) }) { w.fx.forget.forgetSpans(listOf(w.tA..w.tB + 999), listOf("sNear")) }
    @Test fun retentionExpirePhoto() = shadow({ planner.plan(roots(Ref(Family.PHOTO, "fA")), Policy.RetentionPhotos(requireLinked = true)) }) { w.service.expirePhotos(listOf("fA"), requireLinked = true) }
}
