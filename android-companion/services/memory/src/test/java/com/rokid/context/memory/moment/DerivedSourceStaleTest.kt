package com.rokid.context.memory.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A SOURCE of a Moment is removed or changed: the derived fields of the Moment are hidden in the SAME transaction (not at the next rebuild), through the audit hook. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DerivedSourceStaleTest {
    private val db = com.rokid.context.memory.db.DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val audit = ObjectAudit(db.objectDao()) { 5 }
    private val m = ObjectRef(ObjectKind.MOMENT, "00000001", 1)
    private val epoch = DeletionEpoch("i", 1, 0)
    private val fence = DaoFence(0, epoch, { 0 }, { epoch })
    private val s3 get() = db.canvasSlice3Dao()
    @After fun close() = db.close()

    private suspend fun moment(vararg parts: Pair<String, String>) {
        db.momentDao().replace(MomentEntity(m.id, 1, 1, "h", 1, com.rokid.context.memory.moment.MomentType.FRAME), parts.map { MomentPartEntity(m.id, it.first, it.second, "r", 1) })
        DerivedField.ALL.forEach { audit.writeDerived(m, it, "v", null, 1) }
    }
    private suspend fun mark(id: String) = s3.insertLabelFenced(CanvasLabelEntity(id, "MOMENT", "tap", "X", 5, createdAt = 1), fence)
    private suspend fun memory(id: String) = db.memoryDao().insert(MemoryEntity(id, null, "text", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "text"))
    private suspend fun hidden() { DerivedField.ALL.forEach { assertNull(it, audit.readDerived(m, it)) }
        assertNotNull(audit.audit(m).firstOrNull { it.op == AuditOp.INVALIDATED }) }
    private suspend fun fresh() = DerivedField.ALL.forEach { assertNotNull(audit.readDerived(m, it)) }

    @Test fun aMarkDeletedOrUndoneHidesTheDerivedFieldsAtOnce() = runBlocking<Unit> {
        mark("L1"); moment(MomentPartKind.MARK to "L1"); fresh()
        db.withTransaction { s3.deleteLabel("L1"); hidden() }
    }

    @Test fun aMarkCorrectedOrRetaggedHidesThem() = runBlocking<Unit> {
        mark("L1"); moment(MomentPartKind.MARK to "L1"); s3.correctLabel("L1", "Y"); hidden()
        DerivedField.ALL.forEach { audit.writeDerived(m, it, "v2", null, 2) }; fresh()
        s3.retagLabel("L1", "voice"); hidden()
    }

    @Test fun marksDeletedWithTheirPhotosOrRangeHideThem() = runBlocking<Unit> {
        mark("L1"); moment(MomentPartKind.MARK to "L1"); s3.deleteLabelsBetween(0, 10); hidden()
    }

    @Test fun aMemoryEditedReviewedOrTrustChangedHidesThem() = runBlocking<Unit> {
        memory("m1"); moment(MomentPartKind.MEMORY to "m1")
        db.memoryViewDao().editText("m1", "new text"); hidden()
        DerivedField.ALL.forEach { audit.writeDerived(m, it, "v2", null, 2) }; fresh()
        db.memoryViewDao().setTrusted("m1", true); hidden()
        DerivedField.ALL.forEach { audit.writeDerived(m, it, "v3", null, 3) }; fresh()
        db.memoryViewDao().moveReviewState("m1", "OK", "NEEDS_REVIEW"); hidden()
    }

    @Test fun aMemoryOrFrameRemovedDropsTheMomentWithItsDerivedFields() = runBlocking<Unit> {
        moment(MomentPartKind.MEMORY to "m1", MomentPartKind.FRAME to "f1")
        db.momentDao().dropHolding(listOf("f1"), listOf("m1")) // what ForgetService calls when a photo or memory is forgotten
        DerivedField.ALL.forEach { assertNull(audit.readDerived(m, it)) }; assertEquals(0, db.objectDao().derivedCount())
    }

    @Test fun aSourceNoMomentHoldsTouchesNothingAndAnUnchangedLabelWritesNoRow() = runBlocking<Unit> {
        mark("L2"); moment(MomentPartKind.MARK to "L1")
        s3.deleteLabel("L2"); s3.retagLabel("L2", "voice"); fresh()
        mark("L1"); s3.retagLabel("L1", "tap"); fresh() // same source: nothing changed
    }
}
