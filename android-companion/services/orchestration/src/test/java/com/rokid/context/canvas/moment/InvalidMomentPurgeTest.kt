package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.moment.AuditOp
import com.rokid.context.memory.moment.AuditStep
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.moment.NoPhotoReason
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.orchestration.DataOpGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
 * The ONE-TIME purge of invalid Moments (v0.9.6). Invalid = no timestamp (an id that is no second, or only photos without a capture time) or no live photo; the one exception is a MARK Moment
 * with an explicit error reason (the photo-failed fallback), which is valid. Deleted together with their metadata through the existing delete paths; memories whose source frames no longer exist go
 * through the Forget cascade. A marker in `canvas_meta` prevents a rerun.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InvalidMomentPurgeTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val md = db.momentDao()
    private val service = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 100 }
    private var after = mutableListOf<ForgetService.Result>()
    private fun purge(gate: DataOpGate = DataOpGate()) = InvalidMomentPurge(db, service, gate, { 100 }, { after += it })
    @get:org.junit.Rule val pcLogShadow = com.rokid.context.PcLogToShadowRule()
    private fun lines() = org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag == "PcMoments" }.map { it.msg }
    @After fun close() = db.close()

    private val validFrame = MomentId.format(SEC)          // a live captured photo
    private val fallback = MomentId.format(SEC + 1)        // MARK with an explicit error reason: VALID
    private val plainMark = MomentId.format(SEC + 2)       // MARK without a photo or a reason: invalid (the 01:50 ones)
    private val deadPhoto = MomentId.format(SEC + 3)       // FRAME whose photo is gone
    private val noTime = MomentId.format(SEC + 4)          // FRAME whose only photo has no capture time
    private val badId = "zz-not-a-second"                  // no timestamp at all

    private suspend fun moment(id: String, type: MomentType, frame: String? = null, reason: String? = null) {
        val e = MomentEntity(id, 1, 1, "h", 1, type, if (type == MomentType.MARK) NoPhotoReason.UNKNOWN else null, errorReason = reason)
        md.replace(e, listOfNotNull(frame?.let { MomentPartEntity(id, MomentPartKind.FRAME, it, "r", 1) }, if (type == MomentType.MARK) MomentPartEntity(id, MomentPartKind.MARK, "mk-$id", "r", 1, "voice") else null))
    }

    private suspend fun world() {
        fx.frame("live", MS + 5); fx.frame("nots", MS + 4_000, captured = false)
        moment(validFrame, MomentType.FRAME, "live"); moment(fallback, MomentType.MARK, reason = "camera_busy"); moment(plainMark, MomentType.MARK)
        moment(deadPhoto, MomentType.FRAME, "gone"); moment(noTime, MomentType.FRAME, "nots"); moment(badId, MomentType.FRAME, "live")
    }

    private suspend fun stored() = md.allMoments().map { it.momentId }.toSet()

    @Test fun onlyTheValidMomentsSurviveAndAFallbackMarkWithAnErrorReasonIsValid() = runBlocking<Unit> {
        world()
        val r = purge().runOnce()
        assertEquals(InvalidMomentPurge.Status.DONE, r.status); assertEquals(6, r.scanned); assertEquals(4, r.momentsDeleted)
        assertEquals(setOf(validFrame, fallback), stored())
    }

    @Test fun theMetadataOfADeletedMomentGoesAndTheKeptOnesKeepTheirs() = runBlocking<Unit> {
        world()
        val audit = ObjectAudit(db.objectDao()) { 7 }
        val sensor = { m: String -> MomentSensorEntity(m, 1, "t", 1, "OFFSET_ESTIMATED", 40, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "STILL", null, 1, 1) }
        for (id in listOf(plainMark, validFrame)) {
            md.replaceSensor(id, listOf(sensor(id)), 1, "build"); audit.record(md.moment(id)!!, AuditOp.BUILT, AuditStep.BUILD); audit.writeDerived(md.moment(id)!!, DerivedField.SUMMARY, "derived-$id", null, 1)
            db.canvasDao().insertRun(CanvasRunEntity("run-$id", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, 1, null, "[]"))
            db.canvasDao().upsertItem(CanvasRunItemEntity("run-$id", "moment:$id", "n", "OK", null, "{}", null, null, null, null, null, null, null))
        }
        db.sceneDao().replace(SceneEntity("S$validFrame", 1, 0L, 1, 2, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L),
            listOf(plainMark, validFrame).map { ScenePartEntity("S$validFrame", ScenePartKind.MOMENT, it, "r", 1) })
        purge().runOnce()
        assertTrue("sensor rows of the deleted Moment are gone, the kept one keeps its own", md.sensorOf(listOf(plainMark)).isEmpty() && md.sensorOf(listOf(validFrame)).size == 1)
        assertNotNull("the kept Moment keeps its derived field", audit.readDerived(md.moment(validFrame)!!, DerivedField.SUMMARY))
        val q = db.openHelper.readableDatabase
        fun count(sql: String) = q.query(sql).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, count("SELECT COUNT(*) FROM object_audit WHERE objectKind = 'MOMENT' AND objectId = '$plainMark'"))
        assertEquals(0, count("SELECT COUNT(*) FROM object_derived WHERE objectKind = 'MOMENT' AND objectId = '$plainMark'"))
        assertTrue(count("SELECT COUNT(*) FROM object_audit WHERE objectKind = 'MOMENT' AND objectId = '$validFrame'") > 0)
        assertEquals("the run row keyed by the deleted Moment is gone, the kept one stays", listOf("moment:$validFrame"), q.query("SELECT frameId FROM canvas_run_item").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() })
        assertEquals("the Scene shrank to the kept Moment", listOf(validFrame), db.sceneDao().parts("S$validFrame").map { it.refId })
    }

    @Test fun aPurgedSecondIsFencedSoTheBuilderNeverBringsItBackAndTheMarkItselfIsKept() = runBlocking<Unit> {
        fx.mark("mk-1", MS + 2_500) // the mark of the plain mark-only Moment
        moment(plainMark, MomentType.MARK)
        purge().runOnce()
        assertTrue(stored().isEmpty())
        val report = MomentBuilder(db, enabled = { true }, clock = { 5 }).build(listOf(plainMark))
        assertEquals("refused by the tombstone", 1, report.refused); assertEquals(0, report.stored); assertTrue(stored().isEmpty())
        assertNotNull("the mark is a record of its own: kept", db.canvasSlice3Dao().label("mk-1"))
    }

    /** Codex v3 r1 P1-5: the purge no longer deletes any memory (the 19 Nov build ran orphans through Forget at startup); nothing is redacted, nothing is told to the caller. */
    @Test fun noMemoryIsDeletedAndNothingIsRedactedByTheStartupPurge() = runBlocking<Unit> {
        fx.frame("live", MS + 5)
        fx.frame("exp", MS + 9); db.forgetDao().deleteFrame("exp"); db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("exp", 1)) // what photo retention leaves: tombstone + frame_meta, no frame row
        fx.memory("expired", "exp")
        fx.memory("onLive", "live"); fx.memory("typed", null); fx.memory("orphan", "gone"); fx.memory("viaSource", null, derived = true); fx.memory("viaLive", null, derived = true); fx.memory("child", null, derived = true)
        db.frameSourceDao().insertAll(listOf(com.rokid.context.memory.db.FrameSourceEntity("viaSource", "gone2", null, "FACT"), com.rokid.context.memory.db.FrameSourceEntity("viaLive", "live", null, "FACT")))
        db.forgetDao().insertEdges(listOf(MemorySourceEntity("child", "orphan")))
        db.askDao().upsert(com.rokid.context.memory.db.AskEntity("a1", 1, "q", "ANSWER", """["orphan"]""", null, "test", false))
        val r = purge().runOnce()
        val left = db.openHelper.readableDatabase.query("SELECT id FROM memory WHERE reviewState != 'INVALIDATED' ORDER BY id").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        assertEquals("every memory stays, the derived child included", listOf("child", "expired", "onLive", "orphan", "typed", "viaLive", "viaSource"), left)
        assertEquals(0, r.memoriesDeleted); assertTrue("no cascade result reaches the caller", after.isEmpty())
        assertEquals("the answer citing the orphan is not redacted", 0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ask WHERE redacted = 1").use { c -> c.moveToFirst(); c.getInt(0) })
    }

    /** Codex v3 r1 P1-5: a Moment whose photo retention expired (marker, or tombstone + metadata + a citing memory) is retained content, never invalid; a Moment of a really dead photo still goes. */
    @Test fun aMomentOfAnExpiredPhotoIsNeverClassifiedInvalid() = runBlocking<Unit> {
        fx.frame("expM", MS + 20); db.forgetDao().deleteFrame("expM"); db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("expM", 1))
        com.rokid.context.memory.forget.ExpiryMarks.mark(db.canvasSlice3Dao(), "expM", 5, MS + 20)
        fx.frame("expL", MS + 21); db.forgetDao().deleteFrame("expL"); db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("expL", 1)); fx.memory("capL", "expL") // an expiry of an older build: no marker yet
        val m1 = MomentId.format(SEC + 20); val m2 = MomentId.format(SEC + 21)
        moment(m1, MomentType.FRAME, "expM"); moment(m2, MomentType.FRAME, "expL"); moment(deadPhoto, MomentType.FRAME, "gone")
        purge().runOnce()
        assertEquals("the retained Moments stay, the dead-photo one goes", setOf(m1, m2), stored())
    }

    @Test fun itRunsOnceTheMarkerIsStoredAndALaterInvalidMomentIsLeftAlone() = runBlocking<Unit> {
        assertNull(db.canvasSlice3Dao().meta(InvalidMomentPurge.KEY))
        assertEquals("nothing to delete still consumes the one-time run", InvalidMomentPurge.Status.DONE, purge().runOnce().status)
        assertNotNull(db.canvasSlice3Dao().meta(InvalidMomentPurge.KEY))
        moment(plainMark, MomentType.MARK)
        val again = purge().runOnce()
        assertEquals(InvalidMomentPurge.Status.ALREADY_DONE, again.status)
        assertEquals(setOf(plainMark), stored())
    }

    @Test fun aRunningExportDefersItWithoutDeletingOrStoringTheMarkerAndTheNextStartDoesIt() = runBlocking<Unit> {
        world()
        val gate = DataOpGate(waitMs = 50)
        val inExport = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val export = GlobalScope.launch { gate.export { inExport.complete(Unit); release.await() } }
        inExport.await()
        val r = purge(gate).runOnce()
        assertEquals(InvalidMomentPurge.Status.DEFERRED, r.status)
        assertEquals(6, stored().size); assertNull(db.canvasSlice3Dao().meta(InvalidMomentPurge.KEY))
        release.complete(Unit); export.join()
        assertEquals(InvalidMomentPurge.Status.DONE, purge(gate).runOnce().status)
        assertEquals(setOf(validFrame, fallback), stored())
    }

    @Test fun aFailureAfterTheMomentsLeavesNoMarkerSoTheNextStartFinishesTheMemories() = runBlocking<Unit> {
        world(); fx.memory("orphan", "gone")
        val p = purge(); p.testFaultBeforeMarker = { error("boom") }
        try { p.runOnce(); org.junit.Assert.fail("expected the fault") } catch (_: IllegalStateException) {}
        assertNull("no marker after a failure", db.canvasSlice3Dao().meta(InvalidMomentPurge.KEY))
        p.testFaultBeforeMarker = null
        assertEquals(InvalidMomentPurge.Status.DONE, p.runOnce().status)
        assertNotNull("the orphan fact is never deleted by the purge", db.memoryViewDao().byId("orphan")?.takeIf { it.reviewState != com.rokid.context.memory.db.ReviewState.INVALIDATED })
    }

    @Test fun itLogsCountsOnlyAsLiteralLines() = runBlocking<Unit> {
        world(); fx.memory("orphan", "gone")
        purge().runOnce()
        val l = lines().filter { it.startsWith("invalid purge") }
        assertEquals(l.toString(), listOf("invalid purge start", "invalid purge done: scanned=6 deleted=4 memories=0"), l)
        assertTrue(lines().none { m -> listOf(plainMark, validFrame, "orphan", "live", "gone").any { it in m } })
    }
}
