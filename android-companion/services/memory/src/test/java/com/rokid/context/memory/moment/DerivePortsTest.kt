package com.rokid.context.memory.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** The generic ports: SummarizeInput, CaptionInput and EmbedInput take ANY MomentObject, are looked up by objectKind, and write only a derived field. No model call is involved. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DerivePortsTest {
    private val db = com.rokid.context.memory.db.DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val audit = ObjectAudit(db.objectDao()) { 5 }
    /** A fence that always holds (these tests do not wipe). */
    private val alwaysHolds = { com.rokid.context.memory.db.DeletionEpoch("i", 1, 0).let { e -> com.rokid.context.memory.db.DaoFence(0, e, { 0 }, { e }) } }
    @After fun close() = db.close()

    /** Test-only adapters: echo the object id and the reason. */
    private val seen = ArrayList<DeriveRequest>()
    private fun echo(kind: String, registry: DeriveRegistry) = registry.register(
        kind,
        summarize = SummarizeInput { seen += it; DerivedWrite(DerivedField.SUMMARY, value = "sum ${it.obj.id}", builtRev = it.obj.buildRev) },
        caption = CaptionInput { seen += it; DerivedWrite(DerivedField.CAPTION, value = "cap ${it.obj.id}", builtRev = it.obj.buildRev) },
        embed = EmbedInput { seen += it; DerivedWrite(DerivedField.EMBEDDING, ref = "emb:${it.obj.id}", builtRev = it.obj.buildRev) },
    )

    @Test fun theSameCallSitesServeEveryKindOfTheFamily() = runBlocking<Unit> {
        val reg = DeriveRegistry(); val runner = DeriveRunner(audit, reg, alwaysHolds)
        listOf(ObjectKind.MOMENT, ObjectKind.AUDIO, ObjectKind.SCENE, "FAKE").forEach { k ->
            echo(k, reg)
            val o = ObjectRef(k, "id-$k", 3); audit.record(o, AuditOp.BUILT, AuditStep.BUILD); if (k == ObjectKind.MOMENT) db.momentDao().upsertMoment(com.rokid.context.memory.db.MomentEntity(o.id, 1, 1, "h", 1, com.rokid.context.memory.moment.MomentType.FRAME))
            assertNotNull(k, reg.summarizer(k)); assertNotNull(reg.captioner(k)); assertNotNull(reg.embedder(k))
            assertTrue(k, runner.rebuild(o, DerivedField.SUMMARY)); assertEquals("sum id-$k", audit.readDerived(o, DerivedField.SUMMARY)?.value)
            assertTrue(runner.rebuild(o, DerivedField.EMBEDDING)); assertEquals("emb:id-$k", audit.readDerived(o, DerivedField.EMBEDDING)?.ref)
        }
    }

    @Test fun aKindWithNoRegisteredPortIsNotRebuiltAndNothingIsWritten() = runBlocking<Unit> {
        val reg = DeriveRegistry(); echo(ObjectKind.MOMENT, reg)
        val o = ObjectRef(ObjectKind.AUDIO, "a1", 1)
        assertNull(reg.summarizer(ObjectKind.AUDIO)); assertFalse(DeriveRunner(audit, reg, alwaysHolds).rebuild(o, DerivedField.SUMMARY))
        assertTrue(audit.audit(o).isEmpty()); assertEquals(0, db.objectDao().derivedCount())
    }

    @Test fun theRequestCarriesTheRebuildReasonFromTheAudit() = runBlocking<Unit> {
        val reg = DeriveRegistry(); echo("FAKE", reg); val o = ObjectRef("FAKE", "p1", 1)
        audit.record(o, AuditOp.BUILT, AuditStep.BUILD); audit.partRemoved(o, AuditStep.BUILD, "FRAME", "f1")
        DeriveRunner(audit, reg, alwaysHolds).rebuild(o, DerivedField.SUMMARY)
        assertEquals(RebuildReason(AuditOp.PART_REMOVED, "FRAME", "f1"), seen.single().reason)
    }

    @Test fun rebuildMissingOrStaleRebuildsOnlyWhatIsHiddenOrAbsent() = runBlocking<Unit> {
        val reg = DeriveRegistry(); echo("FAKE", reg); val o = ObjectRef("FAKE", "p1", 4); audit.record(o, AuditOp.BUILT, AuditStep.BUILD)
        audit.writeDerived(o, DerivedField.SUMMARY, "fresh", null, 4); audit.writeDerived(o, DerivedField.CAPTION, "old", null, 4); audit.markStale("FAKE", "p1")
        audit.writeDerived(o, DerivedField.SUMMARY, "fresh", null, 4) // summary rebuilt already
        val n = DeriveRunner(audit, reg, alwaysHolds).rebuildMissingOrStale(o)
        assertEquals("caption (stale) and embedding (absent)", 2, n)
        assertEquals("fresh", audit.readDerived(o, DerivedField.SUMMARY)?.value); assertEquals("cap p1", audit.readDerived(o, DerivedField.CAPTION)?.value)
    }

    @Test fun aPortThatReturnsNothingWritesNothing() = runBlocking<Unit> {
        val reg = DeriveRegistry(); reg.register("FAKE", summarize = SummarizeInput { null })
        assertFalse(DeriveRunner(audit, reg, alwaysHolds).rebuild(ObjectRef("FAKE", "p1", 1), DerivedField.SUMMARY)); assertEquals(0, db.objectDao().derivedCount())
    }

    @Test fun aPortMayOnlyWriteTheFieldItWasAskedFor() = runBlocking<Unit> {
        val reg = DeriveRegistry(); reg.register("FAKE", summarize = SummarizeInput { DerivedWrite(DerivedField.CAPTION, value = "x", builtRev = 1) })
        assertFalse(DeriveRunner(audit, reg, alwaysHolds).rebuild(ObjectRef("FAKE", "p1", 1), DerivedField.SUMMARY)); assertEquals(0, db.objectDao().derivedCount())
    }
}
