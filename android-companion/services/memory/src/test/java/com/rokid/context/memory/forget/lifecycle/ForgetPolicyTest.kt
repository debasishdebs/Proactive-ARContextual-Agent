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
        ForgetChild.MEMORY to listOf(F, F, K, F, F), // retention v3: retention never deletes a memory (expiry = KEEP)
        ForgetChild.MOMENT to listOf(C, F, C, C, F),
        ForgetChild.SCENE to listOf(C, F, C, C, C),
        ForgetChild.DERIVED_FIELD to listOf(C, F, C, C, C),
        ForgetChild.MARK to listOf(F, F, C, K, K),
        ForgetChild.TRACE to listOf(C, F, C, K, K),
        ForgetChild.RUN_RESULT to listOf(C, F, C, K, C),
        ForgetChild.ANSWER to listOf(C, F, C, C, C),
        ForgetChild.NUDGE to listOf(C, F, C, C, C),
        ForgetChild.PROPOSAL to listOf(C, F, C, C, C),
        ForgetChild.AUDIO to listOf(K, K, K, K, K),
        ForgetChild.PERSON to listOf(K, K, K, K, K),
        ForgetChild.OBJECT to listOf(K, K, K, K, K),
        ForgetChild.PLACE_PACK to listOf(K, C, K, K, K),
        ForgetChild.PLACE_CARD to listOf(K, C, K, K, K),
        ForgetChild.MOMENT_LOCATION to listOf(F, F, K, K, K), // v18 WP2: only a user Forget and Delete all delete a Moment's saved location
        ForgetChild.PLACE_LINK to listOf(F, F, K, K, K), // place object S1: only a user Forget and Delete all delete a place link
        ForgetChild.SCENE_CENTROID to listOf(F, F, K, C, C), // v18 WP10: a user Forget and Delete all delete it; expiry keeps it; the sweep and the purge only take it with an emptied Scene (purgeScenes)
        ForgetChild.MODEL_ATTRIBUTION to listOf(F, F, C, C, C), // v18 WP11a: follows its item on every path
        ForgetChild.PLACE_HISTORY to listOf(F, F, L, K, K), // v18 WP3: a user Forget and Delete all delete the rows; photo expiry only unlinks the photo
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

    /** Counted per source kind (the four user kinds share one rule, so an unverified user cell counts 4). Every cell was re-read at its cited line on 2026-11-20: the ceiling is 0 and must stay there; a new cell lands verified or not at all. */
    @Test fun theUnverifiedCellsAreListedSoStep2ConfirmsThem() {
        val unverified = ForgetPolicy.rows.flatMap { row -> ForgetSourceKind.entries.filter { !row.rule(it).verified }.map { "${row.child} x $it" } } +
            ForgetPolicy.sideDeletes.flatMap { d -> d.cells.filterValues { !it.verified }.keys.map { "${d.name} x $it" } }
        assertTrue("unverified cells grew to ${unverified.size}: $unverified", unverified.size <= 0)
    }

    // ---- deletes outside the Forget pipeline (watch-off, canvas run delete, run prune) ----

    private val sideExpected: Map<String, Pair<Boolean, Map<ForgetChild, PolicyAction>>> = mapOf(
        "WATCH_OFF" to (false to mapOf(ForgetChild.RUN_RESULT to F, ForgetChild.CONTROL to F)),
        "CANVAS_RUN_DELETE" to (true to mapOf(ForgetChild.RUN_RESULT to F, ForgetChild.CONTROL to F)),
        "MARK_UNDO" to (true to mapOf(ForgetChild.MARK to F, ForgetChild.PIN to L)),
        "PLACE_HISTORY_PIN_REMOVAL" to (true to mapOf(ForgetChild.PLACE_HISTORY to F)),
        "PLACE_HISTORY_ROW_DELETE" to (true to mapOf(ForgetChild.PLACE_HISTORY to F)),
        "PLACE_HISTORY_PRUNE" to (false to mapOf(ForgetChild.PLACE_HISTORY to F)),
        "PLACE_LINK_PLACE_REMOVAL" to (true to mapOf(ForgetChild.PLACE_LINK to F)),
        "MOMENT_LOCATION_SETTING_OFF" to (true to mapOf(ForgetChild.MOMENT_LOCATION to F)),
        "CANVAS_RUN_PRUNE" to (false to mapOf(ForgetChild.RUN_RESULT to F, ForgetChild.CONTROL to F)),
    )

    /** FAILS when a side delete loses its row, changes a cell, or a new one is added without being written here too. */
    @Test fun everySideDeleteHasARowThatEqualsTodaysBehaviour() {
        assertEquals("one row per side delete: add the new one here and in ForgetPolicy.sideDeletes", sideExpected.keys, ForgetPolicy.sideDeletes.map { it.name }.toSet())
        assertEquals("no side delete twice", ForgetPolicy.sideDeletes.size, ForgetPolicy.sideDeletes.map { it.name }.toSet().size)
        for (d in ForgetPolicy.sideDeletes) {
            val (explicit, cells) = sideExpected.getValue(d.name)
            assertEquals("${d.name} explicit", explicit, d.explicit)
            for (child in ForgetChild.entries) assertEquals("${d.name} x $child", cells[child] ?: K, d.action(child))
            for ((child, rule) in d.cells) assertTrue("${d.name} x $child has no file:line evidence: '${rule.at}'", Regex("[A-Za-z]+(\\.kt)?:\\d+").containsMatchIn(rule.at))
        }
    }

    /** The privacy rule for the non-Forget deletes: a system delete (watch-off, prune) never FORGETs a user-authored object (a pin, a mark). */
    @Test fun noSystemSideDeleteForgetsAUserAuthoredObject() {
        for (d in ForgetPolicy.sideDeletes.filter { !it.explicit }) for (child in listOf(ForgetChild.PIN, ForgetChild.MARK)) assertTrue("${d.name} must not delete $child", d.action(child) != F)
    }

    /** Ruling O6: the undo of a mark (glasses voice or tap, phone "Forget this mark") never deletes a pin: it only de-links it. */
    @Test fun aMarkUndoNeverForgetsAPin() {
        assertEquals(L, ForgetPolicy.sideDelete("MARK_UNDO").action(ForgetChild.PIN))
    }

    /** FAILS when a production file starts calling one of the side-delete entry points without a row, or a listed caller no longer calls it (the row is then stale). */
    @Test fun everySideDeleteCallerIsListedAndEveryListedCallerExists() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "phone").isDirectory && java.io.File(it, "services").isDirectory }
        val files = listOf("services/memory/src/main", "services/orchestration/src/main", "phone/src/main").flatMap { r -> java.io.File(root, r).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }
        assertTrue("the scan sees the DAO and the callers", files.any { it.name == "CanvasDao.kt" } && files.any { it.name == "CanvasRunner.kt" } && files.any { it.name == "CanvasBackendImpl.kt" })
        val code = files.associate { f -> f.name to f.readLines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n") }
        for (d in ForgetPolicy.sideDeletes) {
            val found = code.filter { (name, c) -> name != "CanvasDao.kt" && name != "ForgetPolicy.kt" && d.entryPoints.any { it in c } }.keys
            assertEquals("${d.name}: the files that call ${d.entryPoints} (a NEW deleter needs a row in ForgetPolicy.sideDeletes)", d.callers.toSet(), found)
        }
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
