package com.rokid.context.memory.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The audit: rows, append-only, content-free, queryable by object and op, deleted with its object and by Delete all. The derived-field stale rule is in [ObjectDerivedTest]. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ObjectAuditTest {
    private val db = com.rokid.context.memory.db.DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private var now = 100L
    private val audit = ObjectAudit(db.objectDao()) { now++ }
    private val a = ObjectRef(ObjectKind.MOMENT, "00000001", 1)
    private val b = ObjectRef(ObjectKind.SCENE, "s1", 1)
    @After fun close() = db.close()

    @Test fun everyOperationIsOneRowWithAnIncreasingSeqPerObject() = runBlocking<Unit> {
        audit.record(a, AuditOp.BUILT, AuditStep.BUILD); audit.record(b, AuditOp.BUILT, AuditStep.BUILD)
        audit.record(a, AuditOp.REBUILT, AuditStep.BUILD); audit.partAdded(a, AuditStep.BUILD, "FRAME", "f1")
        assertEquals(listOf(1 to AuditOp.BUILT, 2 to AuditOp.REBUILT, 3 to AuditOp.PART_ADDED), audit.audit(a).map { it.seq to it.op })
        assertEquals(listOf(1 to AuditOp.BUILT), audit.audit(b).map { it.seq to it.op })
        val last = audit.audit(a).last()
        assertEquals("FRAME", last.refKind); assertEquals("f1", last.refId); assertEquals(AuditStep.BUILD, last.step)
        assertEquals(100L, audit.audit(a).first().atMs); assertTrue(audit.audit(a).map { it.atMs }.zipWithNext().all { (x, y) -> x < y })
    }

    @Test fun queryableByObjectAndByOp() = runBlocking<Unit> {
        audit.record(a, AuditOp.BUILT, AuditStep.BUILD); audit.record(b, AuditOp.BUILT, AuditStep.BUILD); audit.record(a, AuditOp.REBUILT, AuditStep.BUILD)
        assertEquals(setOf("00000001" to ObjectKind.MOMENT, "s1" to ObjectKind.SCENE), audit.auditByOp(AuditOp.BUILT).map { it.objectId to it.objectKind }.toSet())
        assertEquals(1, audit.auditByOp(AuditOp.REBUILT).size); assertTrue(audit.auditByOp(AuditOp.FORGOTTEN).isEmpty())
    }

    @Test fun forgottenAndOutboxQueuedAreRecordedForAnObjectThatSurvives() = runBlocking<Unit> {
        audit.forgotten(b, "MOMENT", "00000002"); audit.outboxQueued(b, "GLASSES_FRAME")
        assertEquals(listOf(AuditOp.FORGOTTEN, AuditOp.OUTBOX_QUEUED), audit.audit(b).map { it.op })
        assertEquals("GLASSES_FRAME", audit.audit(b).last().refId)
    }

    /** Content-free by construction: the table has only ids, op names, times and step names, and the recorder refuses an op or step that is not on the closed lists. */
    @Test fun theAuditTableHasNoRoomForContent() {
        val cols = db.openHelper.readableDatabase.query("PRAGMA table_info(object_audit)").use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList() }
        assertEquals(listOf("objectKind", "objectId", "seq", "op", "atMs", "step", "refKind", "refId"), cols)
    }

    @Test fun anUnknownOpOrStepIsRefused() = runBlocking<Unit> {
        var refused = 0
        try { audit.record(a, "free text about a cat", AuditStep.BUILD) } catch (_: IllegalArgumentException) { refused++ }
        try { audit.record(a, AuditOp.BUILT, "some caption") } catch (_: IllegalArgumentException) { refused++ }
        assertEquals(2, refused)
        assertTrue(audit.audit(a).isEmpty())
    }

    @Test fun deleteAllEmptiesTheAuditAndTheDerivedFields() = runBlocking<Unit> {
        audit.record(a, AuditOp.BUILT, AuditStep.BUILD); audit.writeDerived(a, DerivedField.SUMMARY, "s", null, 1)
        db.wipeDao().wipeMemory()
        assertEquals(0, db.objectDao().auditCount()); assertEquals(0, db.objectDao().derivedCount())
    }

    @Test fun removingAnObjectRemovesItsAuditAndDerivedRowsAndOnlyTheirs() = runBlocking<Unit> {
        audit.record(a, AuditOp.BUILT, AuditStep.BUILD); audit.writeDerived(a, DerivedField.CAPTION, "c", null, 1)
        audit.record(b, AuditOp.BUILT, AuditStep.BUILD); audit.writeDerived(b, DerivedField.CAPTION, "c", null, 1)
        db.objectDao().removeObjects(ObjectKind.MOMENT, listOf(a.id))
        assertTrue(audit.audit(a).isEmpty()); assertNull(audit.readDerived(a, DerivedField.CAPTION))
        assertEquals(2, audit.audit(b).size); assertEquals("c", audit.readDerived(b, DerivedField.CAPTION)?.value)
        assertFalse(db.objectDao().derivedRows(ObjectKind.MOMENT, a.id).isNotEmpty())
    }
}
