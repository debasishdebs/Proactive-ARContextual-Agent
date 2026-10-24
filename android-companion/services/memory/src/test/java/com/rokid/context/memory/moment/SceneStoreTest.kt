package com.rokid.context.memory.moment

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.SceneRule
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

/**
 * The Scene tables (Room v11): a Scene is a [MomentObject] of kind SCENE holding REFERENCES to Moments. Removing a Moment (Forget, builder removal) removes the part, shrinks the Scene and
 * hides the Scene's derived fields in the SAME transaction; the audit stays content-free.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SceneStoreTest {
    private val db = com.rokid.context.memory.db.DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private var now = 10_000L
    private val audit = ObjectAudit(db.objectDao()) { now++ }
    private val sd = db.sceneDao()
    private val md = db.momentDao()
    private val ids = listOf("00000010", "00000011", "00000012")
    private val sceneId = "S00000010"
    @After fun close() = db.close()

    private fun scene(id: String = sceneId, startMs: Long = 16_000L, endMs: Long = 18_999L) =
        SceneEntity(id, 1, 0L, startMs, endMs, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L)

    private fun parts(id: String = sceneId, momentIds: List<String> = ids) =
        momentIds.map { ScenePartEntity(id, ScenePartKind.MOMENT, it, "r", it.toLong(16) * 1000) }

    private suspend fun seed(id: String = sceneId, momentIds: List<String> = ids) {
        momentIds.forEach { md.replace(MomentEntity(it, 1, 1, "h", 1), emptyList()) }
        sd.replace(scene(id), parts(id, momentIds))
        audit.record(scene(id), AuditOp.BUILT, AuditStep.BUILD)
        DerivedField.ALL.forEach { audit.writeDerived(scene(id), it, "a photo of a cat", null, 1) }
    }

    @Test fun aSceneIsAMomentObjectOfKindSceneHoldingReferences() = runBlocking<Unit> {
        seed()
        val s: MomentObject = sd.scene(sceneId)!!
        assertEquals(ObjectKind.SCENE, s.kind); assertEquals(sceneId, s.id)
        assertEquals(ids, sd.parts(sceneId).map { it.refId }); assertTrue(sd.parts(sceneId).all { it.kind == ScenePartKind.MOMENT })
        assertEquals("the Moments are referenced, not copied", 3, md.allMoments().size)
    }

    @Test fun forgettingOneMomentRemovesThePartShrinksTheSceneAndHidesItsDerivedFieldsAtOnce() = runBlocking<Unit> {
        seed()
        md.remove(listOf("00000011"))
        assertEquals(listOf("00000010", "00000012"), sd.parts(sceneId).map { it.refId })
        assertEquals(2, sd.scene(sceneId)!!.membershipRev)
        DerivedField.ALL.forEach { assertNull("$it must be hidden", audit.readDerived(scene(), it)); assertNotNull(audit.staleSince(scene(), it)) }
        val ops = audit.audit(scene()).map { it.op }
        assertEquals(1, ops.count { it == AuditOp.PART_REMOVED }); assertEquals(1, ops.count { it == AuditOp.FORGOTTEN }); assertEquals(1, ops.count { it == AuditOp.INVALIDATED })
        assertEquals(3, ops.count { it == AuditOp.DERIVED_STALE }); assertEquals(3, ops.count { it == AuditOp.DERIVED_HIDDEN })
        val removed = audit.audit(scene()).first { it.op == AuditOp.PART_REMOVED }
        assertEquals("MOMENT", removed.refKind); assertEquals("00000011", removed.refId); assertEquals(AuditStep.FORGET, removed.step)
    }

    @Test fun theSceneWindowFollowsTheRemainingMembers() = runBlocking<Unit> {
        seed()
        md.remove(listOf("00000010"))
        val s = sd.scene(sceneId)!!
        assertEquals(17_000L, s.startMs); assertEquals(18_999L, s.endMs)
    }

    @Test fun losingTheLastMemberDeletesTheSceneItsPartsItsAuditAndItsDerivedFields() = runBlocking<Unit> {
        seed()
        md.remove(ids)
        assertNull(sd.scene(sceneId)); assertTrue(sd.parts(sceneId).isEmpty())
        assertTrue(audit.audit(scene()).isEmpty()); assertTrue(db.objectDao().derivedRows(ObjectKind.SCENE, sceneId).isEmpty())
    }

    @Test fun aBuilderRemovalIsPartRemovedButNotForgotten() = runBlocking<Unit> {
        seed()
        md.remove(listOf("00000012"), AuditStep.BUILD)
        val ops = audit.audit(scene()).map { it.op }
        assertEquals(1, ops.count { it == AuditOp.PART_REMOVED }); assertEquals(0, ops.count { it == AuditOp.FORGOTTEN })
        assertNull(audit.readDerived(scene(), DerivedField.SUMMARY))
    }

    @Test fun theShrinkAndTheHidingRollBackWithTheMomentRemoval() = runBlocking<Unit> {
        seed()
        try { db.withTransaction { md.remove(listOf("00000011")); error("boom") } } catch (_: IllegalStateException) { }
        assertEquals(3, sd.parts(sceneId).size); assertEquals(1, sd.scene(sceneId)!!.membershipRev)
        assertEquals("a photo of a cat", audit.readDerived(scene(), DerivedField.SUMMARY)?.value)
        assertEquals(3, md.allMoments().size)
    }

    @Test fun onlyScenesHoldingTheMomentAreTouched() = runBlocking<Unit> {
        seed(); seed("S00000020", listOf("00000020", "00000021"))
        md.remove(listOf("00000011"))
        assertEquals(2, sd.parts("S00000020").size); assertEquals(1, sd.scene("S00000020")!!.membershipRev)
        assertEquals("a photo of a cat", audit.readDerived(scene("S00000020"), DerivedField.CAPTION)?.value)
    }

    @Test fun theSceneAuditHoldsNamesAndShortIdsOnly() = runBlocking<Unit> {
        seed(); md.remove(listOf("00000011"))
        val rows = audit.audit(scene())
        assertTrue(rows.all { it.op in AuditOp.ALL && it.step in AuditStep.ALL })
        assertTrue(rows.all { it.refKind == null || it.refKind in setOf("MOMENT", "FIELD") })
        assertTrue(rows.all { r -> listOfNotNull(r.refKind, r.refId, r.op, r.step).none { it.contains("cat") } })
        assertFalse(rows.isEmpty())
    }

    @Test fun deleteAllEmptiesTheSceneTables() = runBlocking<Unit> {
        seed()
        db.wipeDao().wipeMemory()
        assertEquals(0, sd.sceneCount()); assertEquals(0, sd.partCount()); assertEquals(0, db.objectDao().auditCount()); assertEquals(0, db.objectDao().derivedCount())
    }
}
