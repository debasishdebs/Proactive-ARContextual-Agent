package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import com.rokid.context.memory.moment.AuditOp
import com.rokid.context.memory.moment.AuditStep
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.moment.ObjectRef
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

/** The audit rows of the Moment builder and its removal paths (Moment-family base object, Room v10). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentAuditTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private var enabled = true
    private val builder = MomentBuilder(db, enabled = { enabled }, clock = { 5 })
    private val forget = MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }) { 1_000 }
    private val audit = ObjectAudit(db.objectDao()) { 7 }
    private val id = MomentId.format(SEC)
    private val me = ObjectRef("MOMENT", id, 1)
    @After fun close() = db.close()

    private suspend fun ops() = audit.audit(me).map { it.op }

    @Test fun aBuildRecordsBuiltOnceAndAnIdenticalRebuildRecordsNothing() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        builder.build(listOf(id))
        assertEquals(listOf(AuditOp.BUILT), ops()); assertEquals(AuditStep.BUILD, audit.audit(me).single().step)
        builder.build(listOf(id)); builder.rebuildWindow(MS, MS + 999)
        assertEquals("idempotent rebuild writes no audit row", listOf(AuditOp.BUILT), ops())
    }

    @Test fun anAddedPartIsRebuiltPartAddedAndHidesTheDerivedFields() = runBlocking<Unit> {
        fx.frame("a", MS + 5); builder.build(listOf(id))
        audit.writeDerived(me, DerivedField.SUMMARY, "a summary", null, 1)
        fx.frame("b", MS + 9); fx.memory("m1", "a")
        builder.build(listOf(id))
        val rows = audit.audit(me)
        assertTrue(rows.any { it.op == AuditOp.REBUILT })
        assertEquals(setOf("FRAME" to "b", "MEMORY" to "m1"), rows.filter { it.op == AuditOp.PART_ADDED }.map { it.refKind!! to it.refId!! }.toSet())
        assertNull("the summary of the old part set is hidden at once", audit.readDerived(me, DerivedField.SUMMARY))
        assertEquals(1, rows.count { it.op == AuditOp.INVALIDATED }); assertEquals(1, rows.count { it.op == AuditOp.DERIVED_HIDDEN })
    }

    @Test fun aRemovedPartIsPartRemovedAndHidesTheDerivedFields() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a"); builder.build(listOf(id))
        audit.writeDerived(me, DerivedField.CAPTION, "cap", null, 1)
        db.memoryDao().deleteByIds(listOf("m1"))
        builder.build(listOf(id))
        val removed = audit.audit(me).filter { it.op == AuditOp.PART_REMOVED }
        assertEquals(listOf("MEMORY" to "m1"), removed.map { it.refKind!! to it.refId!! })
        assertTrue(audit.audit(me).any { it.op == AuditOp.REBUILT }); assertNull(audit.readDerived(me, DerivedField.CAPTION))
    }

    @Test fun aRebuildAfterTheStaleFieldWasWrittenAgainIsReadableAgain() = runBlocking<Unit> {
        fx.frame("a", MS + 5); builder.build(listOf(id)); audit.writeDerived(me, DerivedField.SUMMARY, "s1", null, 1)
        fx.frame("b", MS + 9); builder.build(listOf(id)); assertNull(audit.readDerived(me, DerivedField.SUMMARY))
        audit.writeDerived(me, DerivedField.SUMMARY, "s2", null, 2); assertEquals("s2", audit.readDerived(me, DerivedField.SUMMARY)?.value)
    }

    @Test fun aMomentTheBuilderRemovesTakesItsAuditAndDerivedFieldsWithIt() = runBlocking<Unit> {
        fx.frame("a", MS + 5); builder.build(listOf(id)); audit.writeDerived(me, DerivedField.SUMMARY, "s", null, 1)
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("a", 1))
        assertEquals(1, builder.build(listOf(id)).removed)
        assertEquals(0, db.objectDao().auditCount()); assertEquals(0, db.objectDao().derivedCount())
        // and a later rebuild of the same second starts a new history
        db.openHelper.writableDatabase.execSQL("DELETE FROM forget_tombstone"); builder.build(listOf(id)); assertEquals(listOf(AuditOp.BUILT), ops())
    }

    @Test fun momentForgetDeletesTheAuditAndTheDerivedFieldsOfTheMomentsItDrops() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("z", MS + 100_000 + 5)
        val other = MomentId.format(SEC + 100); builder.build(listOf(id, other))
        audit.writeDerived(me, DerivedField.SUMMARY, "s", null, 1); audit.writeDerived(ObjectRef("MOMENT", other, 1), DerivedField.SUMMARY, "t", null, 1)
        forget.forgetMoments(listOf(id))
        assertTrue(audit.audit(me).isEmpty()); assertNull(audit.readDerived(me, DerivedField.SUMMARY))
        assertEquals("the other Moment keeps its own", listOf(AuditOp.BUILT, AuditOp.DERIVED_BUILT), audit.audit(ObjectRef("MOMENT", other, 1)).map { it.op })
    }

    @Test fun theSourceForgetPathAlsoTakesTheAuditWithTheDroppedMoment() = runBlocking<Unit> {
        fx.frame("a", MS + 5); builder.build(listOf(id)); audit.writeDerived(me, DerivedField.SUMMARY, "s", null, 1)
        db.momentDao().dropHolding(listOf("a"), emptyList()) // what ForgetService.forgetMoment calls
        assertEquals(0, db.objectDao().auditCount()); assertEquals(0, db.objectDao().derivedCount())
    }

    @Test fun theAuditHoldsIdsOpNamesAndStepsOnlyNeverAnyContent() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a"); fx.mark("L1", MS + 500); fx.ask("ask1", "a"); builder.build(listOf(id))
        audit.writeDerived(me, DerivedField.SUMMARY, "text of m1 and /a.jpg", "emb", 1)
        fx.frame("b", MS + 9); fx.memory("m2", "b"); builder.build(listOf(id)); db.memoryDao().deleteByIds(listOf("m1")); builder.build(listOf(id))
        val rows = db.objectDao().auditByOp(AuditOp.BUILT) + audit.audit(me)
        assertTrue(audit.audit(me).size >= 5)
        val known = setOf("a", "b", "m1", "m2", "L1", "frame:a", "SUMMARY")
        audit.audit(me).forEach { r ->
            assertTrue(r.op, r.op in AuditOp.ALL); assertTrue(r.step, r.step in AuditStep.ALL)
            assertTrue("${r.refKind}", r.refKind == null || r.refKind in setOf("FRAME", "MEMORY", "SUMMARY", "MARK", "CITE", "FIELD"))
            assertTrue("${r.refId}", r.refId == null || r.refId in known)
            listOf("text of", ".jpg", "rokid", "tap", "ask1", "emb").forEach { bad -> assertFalse("$bad in $r", listOf(r.objectKind, r.objectId, r.op, r.step, r.refKind, r.refId).any { it?.contains(bad) == true }) }
        }
        assertNotNull(rows)
    }

    @Test fun theSwitchOffWritesNoAudit() = runBlocking<Unit> {
        enabled = false; fx.frame("a", MS + 5)
        builder.build(listOf(id)); builder.rebuildWindow(MS, MS + 999)
        assertEquals(0, db.objectDao().auditCount())
    }
}
