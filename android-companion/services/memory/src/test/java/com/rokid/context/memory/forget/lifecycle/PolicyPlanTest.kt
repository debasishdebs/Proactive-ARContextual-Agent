package com.rokid.context.memory.forget.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Forget redesign STEP 1: the pure plan view. Nothing here touches a database. */
class PolicyPlanTest {
    private val pins = (1..7).map { PolicyChild("pin$it", "Pin name $it") }
    private val marks = listOf(PolicyChild("mk1", "Mark one"), PolicyChild("mk2", "Mark two"))
    private val photos = (1..40).map { PolicyChild("f$it", "never shown $it") }
    private val children = mapOf(ForgetChild.PIN to pins, ForgetChild.MARK to marks, ForgetChild.PHOTO to photos, ForgetChild.ANSWER to listOf(PolicyChild("a1")), ForgetChild.LOGS to listOf(PolicyChild("log")))

    private fun plan(kind: ForgetSourceKind) = PolicyPlan.of(ForgetSource(kind, origin = "test"), targetCount = 3, children = children)

    @Test fun anExplicitForgetForgetsPinsAndNamesTheFirstFive() {
        val p = plan(ForgetSourceKind.SINGLE)
        assertEquals(7, p.forgottenCount(ForgetChild.PIN)); assertEquals(0, p.delinkedCount(ForgetChild.PIN))
        assertEquals(listOf("Pin name 1", "Pin name 2", "Pin name 3", "Pin name 4", "Pin name 5"), p.entry(ForgetChild.PIN)!!.names)
        assertEquals("the exact ids, so a confirmation binds to the set and not to a count", (1..7).map { "pin$it" }.toSet(), p.entry(ForgetChild.PIN)!!.ids)
        assertEquals(listOf("Mark one", "Mark two"), p.entry(ForgetChild.MARK)!!.names)
    }

    @Test fun aSystemSourceOnlyDeLinksPins() {
        for (kind in listOf(ForgetSourceKind.SYSTEM_EXPIRY, ForgetSourceKind.SYSTEM_SWEEP, ForgetSourceKind.SYSTEM_PURGE)) {
            val p = plan(kind)
            assertEquals("$kind", 7, p.delinkedCount(ForgetChild.PIN)); assertEquals("$kind", 0, p.forgottenCount(ForgetChild.PIN))
            assertTrue("$kind: pins are in delinked, not forgotten", p.delinked.any { it.child == ForgetChild.PIN } && p.forgotten.none { it.child == ForgetChild.PIN })
        }
    }

    @Test fun manyItemFamiliesAreCountedNeverNamed() {
        val photo = plan(ForgetSourceKind.MULTI).entry(ForgetChild.PHOTO)!!
        assertEquals(40, photo.count); assertTrue(photo.names.isEmpty() && photo.ids.isEmpty())
    }

    @Test fun theBucketsFollowThePolicyTable() {
        val p = plan(ForgetSourceKind.DELETE_ALL)
        assertEquals(setOf(ForgetChild.PIN, ForgetChild.MARK, ForgetChild.PHOTO, ForgetChild.ANSWER, ForgetChild.LOGS), p.forgotten.map { it.child }.toSet())
        val s = plan(ForgetSourceKind.SYSTEM_SWEEP)
        assertEquals(setOf(ForgetChild.PHOTO, ForgetChild.MARK, ForgetChild.LOGS), s.kept.map { it.child }.toSet())
        assertEquals(listOf(ForgetChild.ANSWER), s.cascaded.map { it.child })
        assertEquals(listOf(ForgetChild.PIN), s.delinked.map { it.child })
        assertTrue(s.forgotten.isEmpty())
    }

    @Test fun anAbsentFamilyHasNoEntry() {
        assertNull(plan(ForgetSourceKind.SINGLE).entry(ForgetChild.NUDGE))
        assertTrue(PolicyPlan.of(ForgetSource(ForgetSourceKind.SINGLE), 0, emptyMap()).entries.isEmpty())
    }

    @Test fun duplicatesAreCountedOnceAndUnnamedItemsAreSkippedInTheNames() {
        val p = PolicyPlan.of(ForgetSource(ForgetSourceKind.SINGLE), 1, mapOf(ForgetChild.PIN to listOf(PolicyChild("p", "A"), PolicyChild("p", "A"), PolicyChild("q"))))
        assertEquals(2, p.entry(ForgetChild.PIN)!!.count); assertEquals(listOf("A"), p.entry(ForgetChild.PIN)!!.names)
    }

    @Test fun itIsPure() {
        val a = plan(ForgetSourceKind.MULTI); val b = plan(ForgetSourceKind.MULTI)
        assertEquals(a.toString(), b.toString()); assertEquals(a.entries.map { Triple(it.child, it.action, it.count) }, b.entries.map { Triple(it.child, it.action, it.count) })
        assertEquals("a different source kind gives a different plan", false, a.toString() == plan(ForgetSourceKind.SYSTEM_EXPIRY).toString())
    }

    @Test fun theTextFormNeverHoldsANameOrAnId() {
        val text = plan(ForgetSourceKind.SINGLE).toString() + plan(ForgetSourceKind.SINGLE).entries.joinToString { it.toString() }
        for (secret in listOf("Pin name", "Mark one", "pin1", "mk1", "never shown", "f1")) assertFalse("'$secret' leaked into: $text", text.contains(secret))
        assertTrue(text.contains("PIN FORGET x7"))
    }

    @Test fun targetCountAndSourceAreKept() {
        val p = plan(ForgetSourceKind.RANGE); assertEquals(3, p.targetCount); assertEquals(ForgetSourceKind.RANGE, p.source.kind)
    }
}
