package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import com.rokid.context.memory.moment.AuditOp
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.moment.ObjectKind
import com.rokid.context.memory.moment.ObjectRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Scene builder on the real database: stored Moments in, stored Scenes out; idempotent, audited, and never resurrecting a forgotten Moment. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SceneBuilderTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private var tick = 100L
    private val moments = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val builder = SceneBuilder(db, enabled = { true }, clock = { tick++ })
    private val audit = ObjectAudit(db.objectDao()) { tick++ }
    private val forget = MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }) { 1_000 }
    private val hour = Math.floorDiv(MS, 3_600_000L) * 3_600_000L
    @After fun close() = db.close()

    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))
    private fun sid(sec: Long) = "S" + mid(sec)
    private suspend fun rebuild() = builder.rebuildWindow(t(0), t(3599))

    /** A photo (and its Moment) at second [sec] of the test hour; [hash] is its perceptual hash, [motion] the motion state logged with it. */
    private suspend fun photo(sec: Long, motion: String = "STILL", hash: Long = 0, extra: Long = 0) {
        fx.frame("f$sec-$extra", t(sec) + 5 + extra)
        db.frameMetaDao().upsert(FrameMetaEntity("f$sec-$extra", null, null, motion, false, null, null, "%016x".format(hash), null, null, null, null, null, null, 1, capturedAtMs = t(sec) + 5 + extra))
        moments.build(listOf(mid(sec)))
    }

    private suspend fun snapshot() = db.sceneDao().allScenes().flatMap { s ->
        listOf("S|${s.sceneId}|${s.buildRev}|${s.hourStartMs}|${s.startMs}|${s.endMs}|${s.boundaryRule}|${s.activity}|${s.membershipRev}|${s.builtAt}") + db.sceneDao().parts(s.sceneId).map { "P|${it.sceneId}|${it.kind}|${it.refId}|${it.partRev}|${it.partTimeMs}" }
    }

    @Test fun storedMomentsBecomeScenesWithTheirRuleWindowActivityAndReferences() = runBlocking<Unit> {
        photo(0); photo(1); photo(2); photo(3, "WALKING")
        val r = rebuild()
        assertEquals(2, r.stored)
        val a = db.sceneDao().scene(sid(0))!!; val b = db.sceneDao().scene(sid(3))!!
        assertEquals(SceneRule.ACTIVITY_CHANGE, a.boundaryRule); assertEquals(SceneRule.HOUR_END, b.boundaryRule)
        assertEquals("STILL", a.activity); assertEquals("WALKING", b.activity)
        assertEquals(t(0), a.startMs); assertEquals(t(2) + 999, a.endMs); assertEquals(hour, a.hourStartMs)
        assertEquals(1, a.membershipRev); assertEquals(ObjectKind.SCENE, a.kind)
        assertEquals(listOf(mid(0), mid(1), mid(2)), db.sceneDao().parts(sid(0)).map { it.refId })
        assertEquals(listOf(AuditOp.BUILT), audit.audit(a).map { it.op })
    }

    @Test fun withoutALoggedActivityAFrameChangeCuts() = runBlocking<Unit> {
        photo(0, "UNKNOWN", 0); photo(1, "UNKNOWN", 0); photo(2, "UNKNOWN", -1L)
        rebuild()
        assertEquals(listOf(sid(0), sid(2)), db.sceneDao().allScenes().map { it.sceneId })
        assertEquals(SceneRule.FRAME_CHANGE, db.sceneDao().scene(sid(0))!!.boundaryRule); assertNull(db.sceneDao().scene(sid(0))!!.activity)
    }

    @Test fun aSceneNeverCrossesAnHourAndNeverExceedsFiveMinutes() = runBlocking<Unit> {
        photo(3599); fx.frame("next", t(3600) + 5); moments.build(listOf(mid(3600)))
        (0L..300L).forEach { photo(10 + it) }
        builder.rebuildWindow(t(0), t(3601))
        val scenes = db.sceneDao().allScenes()
        assertTrue(scenes.all { it.endMs - it.startMs + 1 <= 300_000 })
        assertTrue(scenes.all { Math.floorDiv(it.startMs, 3_600_000L) == Math.floorDiv(it.endMs, 3_600_000L) })
        assertTrue(scenes.any { it.boundaryRule == SceneRule.CAP_5_MIN })
    }

    @Test fun aRebuildOfTheSameInputChangesNothing() = runBlocking<Unit> {
        photo(0); photo(1); photo(2, "WALKING")
        rebuild(); val first = snapshot(); val rows = db.objectDao().auditCount()
        val r = rebuild()
        assertEquals(first, snapshot()); assertEquals(rows, db.objectDao().auditCount()); assertEquals(0, r.stored); assertEquals(2, r.unchanged)
    }

    @Test fun twoBuildsOfTheSameInputInSeparateDatabasesAgree() = runBlocking<Unit> {
        photo(0); photo(1); photo(2, "WALKING"); rebuild()
        val db2 = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val fx2 = MomentFixture(db2); val m2 = MomentBuilder(db2, enabled = { true }, clock = { 5 })
            listOf(0L to "STILL", 1L to "STILL", 2L to "WALKING").forEach { (s, mo) ->
                fx2.frame("f$s-0", t(s) + 5); db2.frameMetaDao().upsert(FrameMetaEntity("f$s-0", null, null, mo, false, null, null, "%016x".format(0), null, null, null, null, null, null, 1, capturedAtMs = t(s) + 5)); m2.build(listOf(mid(s)))
            }
            var tk = 100L; SceneBuilder(db2, enabled = { true }, clock = { tk++ }).rebuildWindow(t(0), t(3599))
            fun strip(l: List<String>) = l.map { it.split("|").let { p -> if (p[0] == "S") p.dropLast(1).joinToString("|") else it } }
            val other = db2.sceneDao().allScenes().flatMap { s -> listOf("S|${s.sceneId}|${s.buildRev}|${s.hourStartMs}|${s.startMs}|${s.endMs}|${s.boundaryRule}|${s.activity}|${s.membershipRev}") + db2.sceneDao().parts(s.sceneId).map { "P|${it.sceneId}|${it.kind}|${it.refId}|${it.partRev}|${it.partTimeMs}" } }
            assertEquals(strip(snapshot()), other)
        } finally { db2.close() }
    }

    @Test fun aNewMemberRevisesMembershipRecordsTheChangeAndHidesTheDerivedFields() = runBlocking<Unit> {
        photo(0); photo(1); rebuild()
        DerivedField.ALL.forEach { audit.writeDerived(ObjectRef(ObjectKind.SCENE, sid(0), 1), it, "x", null, 1) }
        photo(2); rebuild()
        val s = db.sceneDao().scene(sid(0))!!
        assertEquals(2, s.membershipRev); assertEquals(3, db.sceneDao().parts(sid(0)).size)
        val ops = audit.audit(s).map { it.op }
        assertTrue(ops.containsAll(listOf(AuditOp.REBUILT, AuditOp.PART_ADDED, AuditOp.INVALIDATED)))
        DerivedField.ALL.forEach { assertNull(audit.readDerived(s, it)) }
    }

    @Test fun aMemberWhoseContentChangedHidesTheDerivedFieldsWithoutChangingMembership() = runBlocking<Unit> {
        photo(0); photo(1); rebuild()
        DerivedField.ALL.forEach { audit.writeDerived(ObjectRef(ObjectKind.SCENE, sid(0), 1), it, "x", null, 1) }
        photo(1, extra = 400); rebuild() // a second photo in the same second: same Moment, new content hash
        val s = db.sceneDao().scene(sid(0))!!
        assertEquals(1, s.membershipRev); assertEquals(2, db.sceneDao().parts(sid(0)).size)
        assertNull(audit.readDerived(s, DerivedField.SUMMARY)); assertEquals(1, audit.audit(s).count { it.op == AuditOp.REBUILT })
    }

    @Test fun aSceneThatNoLongerExistsIsRemovedWithItsAuditAndDerivedFields() = runBlocking<Unit> {
        photo(5); photo(6); rebuild()
        val old = ObjectRef(ObjectKind.SCENE, sid(5), 1); audit.writeDerived(old, DerivedField.SUMMARY, "x", null, 1)
        photo(4); rebuild() // an earlier photo joins: the Scene now starts one second sooner, so it has a new id
        assertNull(db.sceneDao().scene(sid(5))); assertNotNull(db.sceneDao().scene(sid(4)))
        assertTrue(audit.audit(old).isEmpty()); assertTrue(db.objectDao().derivedRows(ObjectKind.SCENE, sid(5)).isEmpty())
        assertEquals(0, db.sceneDao().parts(sid(5)).size)
    }

    @Test fun theKillSwitchStopsTheBuilderButNotTheRemoval() = runBlocking<Unit> {
        photo(0)
        val off = SceneBuilder(db, enabled = { false })
        assertEquals(SceneBuilder.Report.NONE, off.rebuildWindow(t(0), t(10))); assertEquals(0, db.sceneDao().sceneCount())
    }

    /** Forgetting a photo also removes everything within 10 s of it (the time link of marks and traces), so the three Moments are 30 s apart. */
    @Test fun aForgottenMomentShrinksTheSceneAndARebuildDoesNotBringItBack() = runBlocking<Unit> {
        photo(0); photo(30); photo(60); rebuild()
        val ref = ObjectRef(ObjectKind.SCENE, sid(0), 1)
        DerivedField.ALL.forEach { audit.writeDerived(ref, it, "x", null, 1) }
        forget.forgetMoments(listOf(mid(30)))
        assertEquals(listOf(mid(0), mid(60)), db.sceneDao().parts(sid(0)).map { it.refId })
        DerivedField.ALL.forEach { assertNull(audit.readDerived(ref, it)) }
        rebuild()
        assertEquals(listOf(mid(0), mid(60)), db.sceneDao().allScenes().flatMap { s -> db.sceneDao().parts(s.sceneId).map { it.refId } })
    }

    @Test fun aBuilderRemovalOfAMomentIsNotAForget() = runBlocking<Unit> {
        photo(0); photo(1); rebuild()
        db.momentDao().insertTombstones(listOf(com.rokid.context.memory.db.MomentTombstoneEntity(mid(1), 1)))
        moments.build(listOf(mid(1))) // the second is fenced: the Moment no longer qualifies and the builder removes it
        assertEquals(listOf(mid(0)), db.sceneDao().parts(sid(0)).map { it.refId })
        assertEquals(0, audit.audit(ObjectRef(ObjectKind.SCENE, sid(0), 1)).count { it.op == AuditOp.FORGOTTEN })
    }

    /** A derive that started for the old Scene must stay refused after the Scene id was removed and later rebuilt with other members: the input revision never goes back. */
    @Test fun aSceneRecreatedUnderTheSameIdNeverReusesAnInputRevisionOfItsEarlierLife() = runBlocking<Unit> {
        photo(100); photo(130); rebuild()                                  // SA = {A, B}
        val sa = ObjectRef(ObjectKind.SCENE, sid(100), 1)
        val epoch = com.rokid.context.memory.db.DeletionEpoch("i", 1, 0); val fence = com.rokid.context.memory.db.DaoFence(0, epoch, { 0 }, { epoch })
        val startedAt = audit.inputSeq(sa)                                  // the derive for {A, B} starts here
        photo(70); rebuild()                                                // an earlier Moment X: SA is replaced by SX = {X, A, B}
        assertNull(db.sceneDao().scene(sid(100)))
        db.momentDao().remove(listOf(mid(70), mid(130)), "build")           // X and B go
        rebuild()                                                           // SA = {A} is rebuilt under the same id
        assertEquals(listOf(mid(100)), db.sceneDao().parts(sid(100)).map { it.refId })
        assertTrue("revision must have moved on", audit.inputSeq(sa) > startedAt)
        val out = audit.writeDerivedGuarded(sa, DerivedField.SUMMARY, "A and B", null, startedAt, fence, startedAt)
        assertEquals(com.rokid.context.memory.db.ObjectDao.WriteOutcome.STALE_INPUT, out)
        assertNull(audit.readDerived(sa, DerivedField.SUMMARY))
    }
}
