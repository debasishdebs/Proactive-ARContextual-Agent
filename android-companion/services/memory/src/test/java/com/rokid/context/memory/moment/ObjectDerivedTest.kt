package com.rokid.context.memory.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The derived fields (SUMMARY, CAPTION, EMBEDDING) of ANY Moment-family object and the stale rule: a stale field is HIDDEN from readers at once and rebuilt later. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ObjectDerivedTest {
    private val db = com.rokid.context.memory.db.DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private var now = 1_000L
    private val audit = ObjectAudit(db.objectDao()) { now++ }
    private val fake = ObjectRef("FAKE", "p1", 2)
    private val moment = ObjectRef(ObjectKind.MOMENT, "00000001", 1)
    @After fun close() = db.close()

    @Test fun aFreshFieldIsReadableWithItsRevisionAndNoStaleSince() = runBlocking<Unit> {
        audit.writeDerived(fake, DerivedField.SUMMARY, "a summary", null, 7)
        val v = audit.readDerived(fake, DerivedField.SUMMARY)!!
        assertEquals("a summary", v.value); assertEquals(7, v.builtRev); assertNull(v.staleSince)
        assertNull("not written", audit.readDerived(fake, DerivedField.CAPTION))
        assertEquals(listOf(AuditOp.DERIVED_BUILT), audit.audit(fake).map { it.op })
    }

    @Test fun markStaleHidesEveryFieldAtOnceAndRecordsIt() = runBlocking<Unit> {
        audit.writeDerived(fake, DerivedField.SUMMARY, "s", null, 1); audit.writeDerived(fake, DerivedField.EMBEDDING, null, "emb:1", 1)
        val n = audit.markStale("FAKE", "p1")
        assertEquals(2, n)
        assertNull("stale value must not be readable", audit.readDerived(fake, DerivedField.SUMMARY)); assertNull(audit.readDerived(fake, DerivedField.EMBEDDING))
        assertNotNull(audit.staleSince(fake, DerivedField.SUMMARY))
        val ops = audit.audit(fake).map { it.op }
        assertEquals(2, ops.count { it == AuditOp.DERIVED_STALE }); assertEquals(2, ops.count { it == AuditOp.DERIVED_HIDDEN }); assertEquals(1, ops.count { it == AuditOp.INVALIDATED })
    }

    @Test fun theFirstStaleTimeIsKeptAndASecondMarkIsANoOp() = runBlocking<Unit> {
        audit.writeDerived(fake, DerivedField.CAPTION, "c", null, 1)
        audit.markStale("FAKE", "p1"); val first = audit.staleSince(fake, DerivedField.CAPTION)
        assertEquals(0, audit.markStale("FAKE", "p1")); assertEquals(first, audit.staleSince(fake, DerivedField.CAPTION))
        assertEquals(1, audit.audit(fake).count { it.op == AuditOp.INVALIDATED })
    }

    @Test fun aRebuildMakesTheFieldVisibleAgain() = runBlocking<Unit> {
        audit.writeDerived(fake, DerivedField.SUMMARY, "old", null, 1); audit.markStale("FAKE", "p1")
        audit.writeDerived(fake, DerivedField.SUMMARY, "new", null, 2)
        val v = audit.readDerived(fake, DerivedField.SUMMARY)!!
        assertEquals("new", v.value); assertEquals(2, v.builtRev); assertNull(v.staleSince)
    }

    @Test fun aPartRemovedOrAddedMarksTheParentStaleForAMomentAndAFakeKind() = runBlocking<Unit> {
        listOf(moment, fake).forEach { o ->
            audit.writeDerived(o, DerivedField.SUMMARY, "s", null, 1)
            audit.partRemoved(o, AuditStep.BUILD, "FRAME", "f1")
            assertNull(o.kind, audit.readDerived(o, DerivedField.SUMMARY))
            assertEquals(o.kind, listOf(AuditOp.DERIVED_BUILT, AuditOp.PART_REMOVED), audit.audit(o).map { it.op }.filter { it == AuditOp.DERIVED_BUILT || it == AuditOp.PART_REMOVED })
            audit.writeDerived(o, DerivedField.CAPTION, "c", null, 1)
            audit.partAdded(o, AuditStep.BUILD, "FRAME", "f2"); assertNull(audit.readDerived(o, DerivedField.CAPTION))
            audit.writeDerived(o, DerivedField.CAPTION, "c", null, 1); audit.forgotten(o, "FRAME", "f3"); assertNull("forgotten part", audit.readDerived(o, DerivedField.CAPTION))
        }
    }

    @Test fun staleObjectsOfOtherKindsAndIdsAreUntouched() = runBlocking<Unit> {
        audit.writeDerived(fake, DerivedField.SUMMARY, "s", null, 1); audit.writeDerived(moment, DerivedField.SUMMARY, "m", null, 1)
        audit.markStale("FAKE", "p1")
        assertEquals("m", audit.readDerived(moment, DerivedField.SUMMARY)?.value)
    }

    @Test fun derivedRowsAreDeletedWithTheirObject() = runBlocking<Unit> {
        audit.writeDerived(moment, DerivedField.SUMMARY, "s", null, 1)
        db.objectDao().removeObjects(ObjectKind.MOMENT, listOf(moment.id))
        assertEquals(0, db.objectDao().derivedCount())
    }

    /** The audit rows of derived writes carry the field NAME only, never its value or ref. */
    @Test fun derivedAuditRowsAreContentFree() = runBlocking<Unit> {
        audit.writeDerived(fake, DerivedField.SUMMARY, "SECRET value about Alice", "emb:SECRET", 1); audit.markStale("FAKE", "p1")
        val all = audit.audit(fake).flatMap { listOf(it.objectKind, it.objectId, it.op, it.step, it.refKind, it.refId) }.filterNotNull()
        assertTrue(all.none { it.contains("SECRET") || it.contains("Alice") })
        assertTrue(audit.audit(fake).all { it.op in AuditOp.ALL && it.step in AuditStep.ALL })
        assertEquals(DerivedField.SUMMARY, audit.audit(fake).first { it.op == AuditOp.DERIVED_BUILT }.refId)
    }
}
