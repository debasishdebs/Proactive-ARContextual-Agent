package com.rokid.context.memory.forget.lifecycle

import com.rokid.context.memory.forget.DeletionGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forget redesign STEP 1: the ONE policy table (family x source) pins TODAY's behaviour and cannot silently lose a family. Pure JVM: no database. The expected matrix below is deliberately written a second time,
 * apart from the table, so changing a cell is a conscious, reviewed edit of two places (and of the `at` evidence). The cells are checked against the real code in `ForgetPolicyConformanceTest` (planner) and
 * `ForgetPolicyPinConformanceTest` (pins).
 */
class ForgetPolicyTest {
    private val F = PolicyAction.FORGET; private val L = PolicyAction.DE_LINK; private val C = PolicyAction.CASCADE_BY_EXISTING_RULES; private val K = PolicyAction.KEEP

    /** child -> [user (SINGLE, MULTI, RANGE, SCOPE), DELETE_ALL, SYSTEM_EXPIRY, SYSTEM_SWEEP, SYSTEM_PURGE] */
    private val expected: Map<ForgetChild, List<PolicyAction>> = mapOf(
        ForgetChild.PHOTO to listOf(F, F, C, K, K),
        ForgetChild.MEMORY to listOf(F, F, F, F, F),
        ForgetChild.MOMENT to listOf(C, F, C, C, F),
        ForgetChild.SCENE to listOf(C, F, C, C, C),
        ForgetChild.DERIVED_FIELD to listOf(C, F, C, C, C),
        ForgetChild.MARK to listOf(F, F, C, K, K),
        ForgetChild.TRACE to listOf(C, F, C, K, C),
        ForgetChild.RUN_RESULT to listOf(C, F, C, K, C),
        ForgetChild.ANSWER to listOf(C, F, C, C, C),
        ForgetChild.NUDGE to listOf(C, F, C, C, C),
        ForgetChild.PROPOSAL to listOf(C, F, C, C, C),
        ForgetChild.AUDIO to listOf(K, K, K, K, K),
        ForgetChild.PERSON to listOf(K, K, K, K, K),
        ForgetChild.OBJECT to listOf(K, K, K, K, K),
        ForgetChild.PLACE_PACK to listOf(K, C, K, K, K),
        ForgetChild.PLACE_CARD to listOf(K, C, K, K, K),
        ForgetChild.CONFIG to listOf(K, C, K, K, K),
        ForgetChild.CONTROL to listOf(C, C, C, C, C),
        ForgetChild.EMBEDDING to listOf(F, F, C, F, F),
        ForgetChild.FILES to listOf(F, F, C, K, C),
        ForgetChild.PIN to listOf(F, F, L, L, L),
        ForgetChild.GLASSES to listOf(C, F, C, K, K),
        ForgetChild.LOGS to listOf(K, F, K, K, K),
    )
    private val groups = listOf(ForgetSourceKind.SINGLE, ForgetSourceKind.DELETE_ALL, ForgetSourceKind.SYSTEM_EXPIRY, ForgetSourceKind.SYSTEM_SWEEP, ForgetSourceKind.SYSTEM_PURGE)

    @Test fun everyChildHasExactlyOneRowAndItIsInTheExpectedMatrix() {
        assertEquals("one row per child", ForgetChild.entries.toSet(), ForgetPolicy.rows.map { it.child }.toSet())
        assertEquals("no child twice", ForgetPolicy.rows.size, ForgetPolicy.rows.map { it.child }.toSet().size)
        assertEquals("the expected matrix lists every child: add the new one there too", ForgetChild.entries.toSet(), expected.keys)
    }

    /** One assertion per (child, source group): a changed cell names itself. */
    @Test fun everyCellEqualsTodaysBehaviour() {
        val wrong = ForgetChild.entries.flatMap { child ->
            groups.mapIndexedNotNull { i, kind -> if (ForgetPolicy.action(child, kind) != expected.getValue(child)[i]) "$child x $kind: table says ${ForgetPolicy.action(child, kind)}, expected ${expected.getValue(child)[i]}" else null }
        }
        assertTrue("cells that differ: $wrong", wrong.isEmpty())
    }

    @Test fun theFourUserKindsShareOneRule() {
        for (child in ForgetChild.entries) for (kind in listOf(ForgetSourceKind.MULTI, ForgetSourceKind.RANGE, ForgetSourceKind.SCOPE)) {
            assertEquals("$child x $kind", ForgetPolicy.action(child, ForgetSourceKind.SINGLE), ForgetPolicy.action(child, kind))
        }
    }

    @Test fun everyRuleNamesTheCodeThatImplementsItToday() {
        val bad = ForgetPolicy.rows.flatMap { row -> ForgetSourceKind.entries.filter { !Regex("[A-Za-z]+(\\.kt)?:\\d+").containsMatchIn(row.rule(it).at) }.map { "${row.child} x $it: '${row.rule(it).at}'" } }
        assertTrue("cells with no file:line evidence: $bad", bad.isEmpty())
    }

    // ---- the new-family guards ----

    /** FAILS when a [Family] is added without a [ForgetChild] (and so without a policy row). */
    @Test fun everyLifecycleFamilyHasAPolicyRow() {
        for (f in Family.entries) { val child = ForgetChild.of(f); assertEquals(f, child.lifecycle); assertTrue("Family.$f has no policy row", ForgetPolicy.rows.any { it.child == child }) }
        val mapped = ForgetChild.entries.mapNotNull { it.lifecycle }
        assertEquals("a Family is the lifecycle of exactly one ForgetChild", mapped.size, mapped.toSet().size)
    }

    /** FAILS when a registered [ForgetFamily] (Families.ALL, the family inventory) has no policy row. */
    @Test fun everyRegisteredFamilyOfTheInventoryHasAPolicyRow() {
        for (f in Families.ALL) assertTrue("${f.family} is registered in Families.ALL but has no policy row", ForgetPolicy.rows.any { it.child.lifecycle == f.family })
    }

    /** FAILS when a table of the deletion graph is owned by a family that has no policy row (a table cannot be added without a family, a family not without a row). */
    @Test fun everyDeletionGraphTableBelongsToAFamilyWithAPolicyRow() {
        for (t in DeletionGraph.TABLES) {
            val owner = Families.ownerOfTable(t.name)
            assertTrue("table ${t.name} has no owning family", owner != null)
            assertTrue("table ${t.name} is owned by $owner which has no policy row", ForgetPolicy.rows.any { it.child.lifecycle == owner })
        }
    }

    /** A table the deletion graph WIPES on Delete all must not be KEEP under DELETE_ALL for its family (the table cannot say KEEP and the DAO delete it). */
    @Test fun aWipedTableIsNeverKeptByDeleteAll() {
        for (t in DeletionGraph.TABLES.filter { it.fate == DeletionGraph.Fate.WIPED }) {
            val child = ForgetChild.of(Families.ownerOfTable(t.name)!!)
            assertTrue("${t.name} is WIPED by Delete all but ${child} says KEEP", ForgetPolicy.action(child, ForgetSourceKind.DELETE_ALL) != K)
        }
    }

    @Test fun aKeptTableIsNeverForgottenByDeleteAll() {
        for (t in DeletionGraph.TABLES.filter { it.fate == DeletionGraph.Fate.KEPT }) {
            val child = ForgetChild.of(Families.ownerOfTable(t.name)!!)
            assertFalse("${t.name} is KEPT by Delete all but ${child} says FORGET", ForgetPolicy.action(child, ForgetSourceKind.DELETE_ALL) == F)
        }
    }

    @Test fun theReservedFamiliesAreKeptByEverySource() {
        for (f in Families.ALL.filter { it.reserved }) for (kind in ForgetSourceKind.entries) assertEquals("${f.family} x $kind", K, ForgetPolicy.action(f.family, ForgetSource(kind)))
    }

    // ---- the privacy rule of the redesign: only an explicit source deletes a user-authored object ----

    @Test fun pinsAreForgottenByExplicitSourcesAndOnlyDeLinkedBySystemSources() {
        for (kind in ForgetSourceKind.entries) assertEquals("PIN x $kind", if (kind.explicit) F else L, ForgetPolicy.action(ForgetChild.PIN, kind))
    }

    @Test fun noSystemSourceForgetsAPin() {
        for (kind in ForgetSourceKind.entries.filter { !it.explicit }) assertTrue("$kind must not delete a pin", ForgetPolicy.action(ForgetChild.PIN, kind) != F)
    }

    @Test fun theDeleteAllKindIsExplicitAndTheSystemKindsAreNot() {
        assertEquals(setOf(ForgetSourceKind.SINGLE, ForgetSourceKind.MULTI, ForgetSourceKind.RANGE, ForgetSourceKind.SCOPE, ForgetSourceKind.DELETE_ALL), ForgetSourceKind.entries.filter { it.explicit }.toSet())
        assertEquals(setOf(ForgetSourceKind.SYSTEM_EXPIRY, ForgetSourceKind.SYSTEM_SWEEP, ForgetSourceKind.SYSTEM_PURGE), ForgetSourceKind.entries.filter { !it.explicit }.toSet())
    }

    /** Marks are user-authored too, yet photo expiry erases the marks in its radius today (coverage map G9, needs a ruling). The table records that as a known divergence from the rule, not as policy. */
    @Test fun knownDivergenceMarkUnderPhotoExpiryIsCascadeNotKeep() {
        assertEquals(C, ForgetPolicy.action(ForgetChild.MARK, ForgetSourceKind.SYSTEM_EXPIRY))
        assertTrue("G9: a ruling is pending", ForgetPolicy.rule(ForgetChild.MARK, ForgetSourceKind.SYSTEM_EXPIRY).at.contains("needs a ruling"))
    }

    @Test fun theUnverifiedCellsAreListedSoStep2ConfirmsThem() {
        val unverified = ForgetPolicy.rows.flatMap { row -> ForgetSourceKind.entries.filter { !row.rule(it).verified }.map { "${row.child} x $it" } }
        // not a failure: a count that only shrinks. Raise the ceiling only with a written reason.
        assertTrue("unverified cells grew to ${unverified.size}: $unverified", unverified.size <= 29)
    }

    // ---- ForgetSource ----

    @Test fun aSourceCannotClaimToBeExplicitWhenItsKindIsASystemJob() {
        for (kind in ForgetSourceKind.entries) {
            ForgetSource(kind) // the default agrees
            try { ForgetSource(kind, explicit = !kind.explicit); org.junit.Assert.fail("$kind accepted the wrong explicit flag") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun aSourceDescribesCountsAndKindsNeverIds() {
        val s = ForgetSource(ForgetSourceKind.MULTI, selection = SourceSelection.Ids(setOf("secret-id-1", "secret-id-2")), origin = "activity-list")
        assertEquals("ForgetSource(MULTI explicit=true ids=2 origin=activity-list)", s.toString())
        assertEquals("range", SourceSelection.TimeRange(1, 2).describe()); assertEquals("scope=SCENE ids=1", SourceSelection.Scoped("SCENE", setOf("sc")).describe()); assertEquals("none", SourceSelection.None.describe())
    }

    @Test fun actionForSourceAgreesWithActionForKind() {
        for (child in ForgetChild.entries) for (kind in ForgetSourceKind.entries) assertEquals(ForgetPolicy.action(child, kind), ForgetPolicy.action(child, ForgetSource(kind)))
    }
}
