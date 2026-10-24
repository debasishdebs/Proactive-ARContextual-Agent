package com.rokid.context.memory.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.ObjectDao.WriteOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A derive that finishes late must not resurrect a deleted object, survive a Delete all, or expose an answer computed from inputs that changed since it started. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DerivedGuardTest {
    private val db = com.rokid.context.memory.db.DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val audit = ObjectAudit(db.objectDao()) { 9 }
    private var generation = 0L
    private val epoch = DeletionEpoch("i", 1, 0)
    private fun fence() = DaoFence(generation, epoch, { generation }, { epoch })
    private val o = ObjectRef("FAKE", "p1", 1)
    @After fun close() = db.close()

    private suspend fun started(): Pair<DaoFence, Int> { audit.record(o, AuditOp.BUILT, AuditStep.BUILD); return fence() to audit.inputSeq(o) }
    private suspend fun write(f: DaoFence, seq: Int) = audit.writeDerivedGuarded(o, DerivedField.SUMMARY, "v", null, 1, f, seq)

    @Test fun theCurrentRevisionAndGenerationWrite() = runBlocking<Unit> {
        val (f, seq) = started()
        assertEquals(WriteOutcome.WRITTEN, write(f, seq)); assertEquals("v", audit.readDerived(o, DerivedField.SUMMARY)?.value)
    }

    @Test fun finishAfterTheObjectWasDeletedWritesNothing() = runBlocking<Unit> {
        val (f, seq) = started()
        db.objectDao().removeObjects("FAKE", listOf("p1"))
        assertEquals(WriteOutcome.PARENT_GONE, write(f, seq))
        assertEquals(0, db.objectDao().derivedCount()); assertEquals(0, db.objectDao().auditCount())
    }

    @Test fun finishAfterDeleteAllWritesNothing() = runBlocking<Unit> {
        val (f, seq) = started()
        generation++; db.wipeDao().wipeMemory()
        audit.record(o, AuditOp.BUILT, AuditStep.BUILD) // even if a new object of the same id exists again, the old generation is refused
        assertEquals(WriteOutcome.OBSOLETE_GENERATION, write(f, seq))
        assertEquals(0, db.objectDao().derivedCount())
    }

    @Test fun finishAfterAMarkStaleWithAnOlderRevisionIsRejectedAndTheFieldStaysHidden() = runBlocking<Unit> {
        val (f, seq) = started()
        audit.writeDerived(o, DerivedField.SUMMARY, "old", null, 1); audit.partRemoved(o, AuditStep.BUILD, "FRAME", "f1") // inputs changed after the derive started
        assertEquals(WriteOutcome.STALE_INPUT, write(f, seq))
        assertNull("the obsolete answer is not readable", audit.readDerived(o, DerivedField.SUMMARY))
        assertEquals(0, audit.audit(o).count { it.op == AuditOp.DERIVED_BUILT && it.seq > seq + 1 })
        // a derive started after the change succeeds
        assertEquals(WriteOutcome.WRITTEN, write(fence(), audit.inputSeq(o))); assertEquals("v", audit.readDerived(o, DerivedField.SUMMARY)?.value)
    }

    /** A derive paused in its port, Delete all, the SAME object id recreated with the SAME input sequence: the old derive must still be refused and nothing written. */
    @Test fun aPausedDeriveFinishingAfterDeleteAllAndARecreatedObjectIsRefused() = runBlocking<Unit> {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>(); val inPort = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reg = DeriveRegistry(); reg.register("FAKE", summarize = SummarizeInput { inPort.complete(Unit); gate.await(); DerivedWrite(DerivedField.SUMMARY, value = "FORGOTTEN content", builtRev = 1) })
        audit.record(o, AuditOp.BUILT, AuditStep.BUILD)
        val runner = DeriveRunner(audit, reg) { fence() }
        val seqAtStart = audit.inputSeq(o)
        val result = async(kotlinx.coroutines.Dispatchers.Default) { runner.rebuildOutcome(o, DerivedField.SUMMARY) }
        inPort.await()
        generation++; db.wipeDao().wipeMemory()
        audit.record(o, AuditOp.BUILT, AuditStep.BUILD) // the same id again, the same input sequence as when the derive started
        assertEquals(seqAtStart, audit.inputSeq(o))
        gate.complete(Unit)
        assertEquals(WriteOutcome.OBSOLETE_GENERATION, result.await())
        assertNull(audit.readDerived(o, DerivedField.SUMMARY)); assertEquals(0, db.objectDao().derivedCount()); assertEquals(1, db.objectDao().auditCount())
    }
}
