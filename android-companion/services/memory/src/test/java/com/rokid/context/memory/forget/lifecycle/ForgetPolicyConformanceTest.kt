package com.rokid.context.memory.forget.lifecycle

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.GoldenWorld
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Forget redesign STEP 1, conformance: the policy table against what the REAL planner touches on the golden world (the same plans the executor and the golden delete sets are pinned to). Two laws, per source:
 *  1. a family the planner touches is never KEEP in the table for that source kind (the table must not claim a family is left alone when the code reaches it);
 *  2. a node the planner ERASEs belongs to a family whose action is FORGET or CASCADE_BY_EXISTING_RULES, never DE_LINK.
 * Read only (plans never write). The sweep source is not checked this way: its executor plans with `Policy.User` as a SUPERSET only to revoke leases (`ForgetExecutor.kt:153-158`) and deletes less.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetPolicyConformanceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val w = GoldenWorld(db)
    @After fun close() { ForgetV2.testOverride = null; db.close() }

    private fun check(label: String, kind: ForgetSourceKind, plan: ForgetPlan) {
        assertTrue("$label: the plan is empty, the check would prove nothing", plan.size > 0)
        for ((ref, fate) in plan.nodes) {
            val child = ForgetChild.of(ref.family); val action = ForgetPolicy.action(child, kind)
            assertTrue("$label: the planner touches ${ref.family} but the table says $action for $kind (${ForgetPolicy.rule(child, kind).at})", action != PolicyAction.KEEP)
            if (fate == Fate.ERASE) assertTrue("$label: the planner ERASEs ${ref.family} but the table says $action for $kind", action == PolicyAction.FORGET || action == PolicyAction.CASCADE_BY_EXISTING_RULES)
        }
    }

    private fun both(block: suspend () -> Unit) = runBlocking { w.seed(); for (v2 in listOf(false, true)) { ForgetV2.testOverride = v2; block() } }

    @Test fun aSingleFactForgetFollowsTheUserRows() = both { check("fact", ForgetSourceKind.SINGLE, ForgetPlanner(db).plan(setOf(Ref(Family.MEMORY, "mA")), Policy.User)) }

    @Test fun aSinglePhotoForgetFollowsTheUserRows() = both { check("photo", ForgetSourceKind.SINGLE, ForgetPlanner(db).plan(setOf(Ref(Family.PHOTO, "fB")), Policy.User)) }

    @Test fun aMarkForgetFollowsTheUserRows() = both { check("mark", ForgetSourceKind.SINGLE, ForgetPlanner(db).plan(setOf(Ref(Family.MARK, "mkNear")), Policy.User)) }

    @Test fun aMultiSelectFollowsTheUserRows() = both {
        check("multi", ForgetSourceKind.MULTI, ForgetPlanner(db).plan(setOf(Ref(Family.MEMORY, "mA"), Ref(Family.PHOTO, "fB"), Ref(Family.MARK, "mkFar")), Policy.User))
    }

    @Test fun aRangeForgetFollowsTheUserRows() = both { check("range", ForgetSourceKind.RANGE, ForgetPlanner(db).planRange(w.tA - 10, w.tB + 10)) }

    @Test fun aMomentScopedForgetFollowsTheUserRows() = both { check("scope", ForgetSourceKind.SCOPE, ForgetPlanner(db).planMoments(listOf(w.momA))) }

    @Test fun retentionOfMemoriesFollowsTheExpiryRows() = both { check("retention", ForgetSourceKind.SYSTEM_EXPIRY, ForgetPlanner(db).plan(setOf(Ref(Family.MEMORY, "mA")), Policy.Retention)) }

    @Test fun photoExpiryFollowsTheExpiryRows() = both { check("photo expiry", ForgetSourceKind.SYSTEM_EXPIRY, ForgetPlanner(db).plan(setOf(Ref(Family.PHOTO, "fA")), Policy.RetentionPhotos())) }

    /** Retention keeps the accepted proposals and does not rebuild descendants; the table says CASCADE for both, the planner proves the rows differ from the user cascade. */
    @Test fun retentionDoesNotRebuildDescendantsAndTheUserCascadeDoes() = runBlocking {
        w.seed(); ForgetV2.testOverride = false
        val user = ForgetPlanner(db).plan(setOf(Ref(Family.MEMORY, "mA")), Policy.User); val retention = ForgetPlanner(db).plan(setOf(Ref(Family.MEMORY, "mA")), Policy.Retention)
        assertTrue("user: the derived memory mD is rebuilt", user.nodes[Ref(Family.MEMORY, "mD")] == Fate.REBUILD)
        assertTrue("retention: mD is untouched", Ref(Family.MEMORY, "mD") !in retention.nodes)
        assertTrue("the table gives MEMORY FORGET for both", ForgetPolicy.action(ForgetChild.MEMORY, ForgetSourceKind.SINGLE) == PolicyAction.FORGET && ForgetPolicy.action(ForgetChild.MEMORY, ForgetSourceKind.SYSTEM_EXPIRY) == PolicyAction.FORGET)
    }
}
