package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CleanupTarget
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import com.rokid.context.memory.moment.AuditOp
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.moment.NoPhotoReason
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

/**
 * A Moment can be born from a mark alone (design decisions 8, 8.1): the stored Moment of a second that holds a mark but no photo is a MARK Moment with the mark's code as part metadata and a content-free
 * no-photo reason; a second with a photo stays FRAME; deleting the mark deletes the MARK Moment; Forget, the audit, the outbox and Scenes treat it like any other Moment.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkMomentTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private var tick = 100L
    private val builder = MomentBuilder(db, enabled = { true }, clock = { tick++ })
    private val scenes = SceneBuilder(db, enabled = { true }, clock = { tick++ })
    private val md = db.momentDao()
    private val forget = MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }) { 1_000 }
    private val id = MomentId.format(SEC)
    private fun id(i: Long) = MomentId.format(SEC + i)
    @After fun close() = db.close()

    @Test fun aMarkAloneMakesAMarkMomentWithItsCodeAndAnUnknownReason() = runBlocking<Unit> {
        fx.mark("mk:1:1", MS + 500, "voice")
        assertEquals(1, builder.build(listOf(id)).stored)
        val m = md.moment(id)!!
        assertEquals(MomentType.MARK, m.momentType); assertEquals(NoPhotoReason.UNKNOWN, m.noPhotoReason)
        val p = md.parts(id).single()
        assertEquals(MomentPartKind.MARK to "mk:1:1", p.kind to p.refId); assertEquals("voice", p.code)
        assertEquals("the id is the phone second's 8-hex id", 8, m.momentId.length)
    }

    @Test fun aSecondWithAPhotoAndAMarkIsAFrameMomentAndTheMarkKeepsItsCode() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.mark("mk:1:1", MS + 500, "tap")
        builder.build(listOf(id))
        val m = md.moment(id)!!
        assertEquals(MomentType.FRAME, m.momentType); assertNull(m.noPhotoReason)
        assertEquals("tap", md.parts(id).single { it.kind == MomentPartKind.MARK }.code); assertNull(md.parts(id).single { it.kind == MomentPartKind.FRAME }.code)
    }

    @Test fun theStoredTypeFollowsThePartsWhenThePhotoGoesAndTheMarkStays() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.mark("mk:1:1", MS + 500)
        builder.build(listOf(id)); assertEquals(MomentType.FRAME, md.moment(id)!!.momentType)
        db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("a", 1))
        builder.build(listOf(id))
        assertEquals(MomentType.MARK, md.moment(id)!!.momentType); assertEquals(NoPhotoReason.UNKNOWN, md.moment(id)!!.noPhotoReason)
    }

    @Test fun aRetaggedMarkChangesTheCodeOfItsPartAndTheMomentIsRebuilt() = runBlocking<Unit> {
        fx.mark("mk:1:1", MS + 500, "tap")
        builder.build(listOf(id)); val before = md.moment(id)!!.contentHash
        db.canvasSlice3Dao().retagLabel("mk:1:1", "ask_tap")
        builder.build(listOf(id))
        assertEquals("ask_tap", md.parts(id).single().code); assertTrue(md.moment(id)!!.contentHash != before)
        assertEquals(listOf(AuditOp.BUILT, AuditOp.REBUILT), ObjectAudit(db.objectDao()).audit(ObjectRef(ObjectKind.MOMENT, id)).map { it.op }.filter { it == AuditOp.BUILT || it == AuditOp.REBUILT })
    }

    @Test fun forgettingTheMarkDeletesTheMarkMoment() = runBlocking<Unit> {
        fx.mark("mk:1:1", MS + 500)
        builder.build(listOf(id)); assertNotNull(md.moment(id))
        db.canvasSlice3Dao().deleteLabel("mk:1:1")
        val r = builder.build(listOf(id))
        assertEquals(1, r.removed); assertNull(md.moment(id)); assertTrue(md.parts(id).isEmpty())
        assertTrue("its audit goes with it", ObjectAudit(db.objectDao()).audit(ObjectRef(ObjectKind.MOMENT, id)).isEmpty())
    }

    @Test fun momentForgetTakesAMarkMomentLikeAnyOtherTheMarkTheTombstoneTheOutboxAndTheAudit() = runBlocking<Unit> {
        fx.mark("mk:1:1", MS + 500, "voice"); fx.mark("mk:1:2", MS + 1500, "tap")
        builder.build(listOf(id, id(1)))
        val r = forget.forgetMoments(listOf(id))
        assertEquals(1, r.marks)
        assertNull(md.moment(id)); assertNotNull("only the named second", md.moment(id(1)))
        assertEquals(listOf("mk:1:2"), db.canvasSlice3Dao().labels().map { it.labelId })
        assertEquals(listOf(id), md.tombstonedAmong(listOf(id, id(1))))
        assertTrue(md.pendingOutbox().any { it.target == CleanupTarget.GLASSES_RANGE })
        assertTrue(ObjectAudit(db.objectDao()).audit(ObjectRef(ObjectKind.MOMENT, id)).isEmpty())
        assertEquals("a rebuild never brings it back", 0, builder.build(listOf(id)).stored); assertNull(md.moment(id))
    }

    @Test fun aRangeForgetFencesAMarkOnlySecondSoNoBuildRecreatesIt() = runBlocking<Unit> {
        forget.forgetRange(MS, MS + 5_000)
        fx.mark("mk:1:1", MS + 500)
        val r = builder.build(listOf(id))
        assertEquals(0, r.stored); assertNull(md.moment(id))
    }

    private val hour = Math.floorDiv(MS, 3_600_000L) * 3_600_000L
    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))

    @Test fun aLostMarkMomentShrinksItsSceneAsBefore() = runBlocking<Unit> {
        fx.frame("a", t(0) + 5)
        db.frameMetaDao().upsert(FrameMetaEntity("a", null, null, "STILL", false, null, null, "%016x".format(0L), null, null, null, null, null, null, 1, capturedAtMs = t(0) + 5))
        fx.mark("mk:1:1", t(1) + 100)
        builder.build(listOf(mid(0), mid(1)))
        scenes.rebuildWindow(t(0), t(3599))
        val sid = "S" + mid(0)
        assertEquals("the mark joins the neighbouring Scene by time", listOf(mid(0), mid(1)), db.sceneDao().parts(sid).map { it.refId })
        forget.forgetMoments(listOf(mid(1)))
        assertEquals(listOf(mid(0)), db.sceneDao().parts(sid).map { it.refId })
        assertNotNull("the Scene survives with its remaining member", db.sceneDao().scene(sid))
    }

    @Test fun aMarkOnlySceneIsBuiltFromMarkMomentsAlone() = runBlocking<Unit> {
        fx.mark("mk:1:1", t(0) + 100); fx.mark("mk:1:2", t(5) + 100)
        builder.build(listOf(mid(0), mid(5))); scenes.rebuildWindow(t(0), t(3599))
        assertEquals(listOf("S" + mid(0)), db.sceneDao().allScenes().map { it.sceneId })
        assertEquals(listOf(mid(0), mid(5)), db.sceneDao().parts("S" + mid(0)).map { it.refId })
    }

    // --- the one-time catch-up of marks that already exist ---

    @Test fun theCatchUpGivesExistingMarksOfTheLastSevenDaysTheirMarkMomentsOnceAndOnlyOnce() = runBlocking<Unit> {
        val now = MS + 8 * 24 * 3_600_000L
        fx.mark("old", now - 8 * 24 * 3_600_000L + 500)          // 8 days old: outside the look-back
        fx.mark("recent", now - 3 * 24 * 3_600_000L + 500, "voice")
        fx.frame("p", now - 2 * 24 * 3_600_000L + 5); fx.mark("withPhoto", now - 2 * 24 * 3_600_000L + 700)
        val r = builder.catchUpMarks(now)
        assertEquals(2, r.stored)
        val all = md.allMoments()
        assertEquals(setOf(MomentType.MARK, MomentType.FRAME), all.map { it.momentType }.toSet()); assertEquals(2, all.size)
        assertEquals(listOf("voice"), md.parts(all.single { it.momentType == MomentType.MARK }.momentId).map { it.code })
        // done once: a mark recorded (or restored) later is not swept by a second call
        fx.mark("later", now - 1000 + 1)
        assertEquals(0, builder.catchUpMarks(now).stored); assertEquals(2, md.allMoments().size)
        assertNotNull(db.canvasSlice3Dao().meta(MomentBuilder.KEY_CATCHUP))
    }

    @Test fun theCadenceRunsTheCatchUpBeforeItsWindowRebuild() = runBlocking<Unit> {
        val now = MS + 3_600_000L * 30
        fx.mark("recent", now - 5 * 3_600_000L, "tap")
        val trigger = MomentBuildTrigger(builder::build, builder::rebuildWindow, { true }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default), catchUp = { builder.catchUpMarks(it) })
        trigger.rebuildRecent(now)
        assertEquals(1, md.allMoments().size); assertEquals(MomentType.MARK, md.allMoments().single().momentType)
    }

    @Test fun theCadenceWindowRebuildAlsoPicksUpMarksOfTheLastHours() = runBlocking<Unit> {
        fx.mark("mk:1:1", t(10) + 100)
        builder.rebuildWindow(t(0), t(3599))
        assertEquals(listOf(mid(10)), md.allMoments().map { it.momentId })
    }

    @Test fun theCatchUpIsOffWithTheStoreSwitchAndLeavesNoMarker() = runBlocking<Unit> {
        fx.mark("mk:1:1", MS + 100)
        val off = MomentBuilder(db, enabled = { false }, clock = { 5 })
        assertEquals(0, off.catchUpMarks(MS + 1000).stored); assertNull(db.canvasSlice3Dao().meta(MomentBuilder.KEY_CATCHUP)); assertTrue(md.allMoments().isEmpty())
    }
}
