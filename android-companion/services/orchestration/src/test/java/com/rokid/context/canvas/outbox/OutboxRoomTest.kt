package com.rokid.context.canvas.outbox

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CleanupOutboxEntity
import com.rokid.context.memory.db.CleanupPayload
import com.rokid.context.memory.db.CleanupTarget
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The drainer on the real Room outbox: rows written by Forget become deletions, and Delete all keeps them for the drainer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OutboxRoomTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val forget = MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }) { 1_000 }
    private val glasses = FakeGlasses(reachable = false); private val helper = FakeHelper(reachable = false)
    private var now = 10_000L
    @After fun close() = db.close()

    private fun drainer(files: PhoneFilePort) = OutboxDrainer(RoomOutboxStore(db), files, glasses, helper, clock = { now }, log = RecordingLog())

    @Test fun aForgottenPhotosJpegIsGoneFromTheFilePortAfterADrainAndTheRowIsDeleted() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("b", MS + 90_000)
        val files = FakeFiles(listOf("/a.jpg", "/b.jpg"))
        forget.forgetRange(MS, MS + 999)
        assertTrue("Forget queued the JPEG", db.momentDao().pendingOutbox().any { it.target == CleanupTarget.PHONE_FILE && it.payloadJson.contains("/a.jpg") })
        drainer(files).drain()
        assertEquals(setOf("/b.jpg"), files.present)
        assertTrue(db.momentDao().pendingOutbox().none { it.target == CleanupTarget.PHONE_FILE })
        assertEquals("only the glasses range row waits", listOf(CleanupTarget.GLASSES_RANGE), db.momentDao().pendingOutbox().map { it.target })
    }

    @Test fun aRowRemovedFromTheStoreCanBeQueuedAgainForTheSamePath() = runBlocking<Unit> {
        val md = db.momentDao(); val row = CleanupOutboxEntity(target = CleanupTarget.PHONE_FILE, payloadJson = CleanupPayload.one("path", "/a.jpg"), createdAt = 1)
        md.insertOutbox(listOf(row)); drainer(FakeFiles(listOf("/a.jpg"))).drain(); assertEquals(0, md.outboxCount())
        assertEquals(1, md.insertOutbox(listOf(row)).count { it != -1L })
    }

    @Test fun deleteAllKeepsTheOutboxAndTheDrainerStillWorksAfterwards() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        val files = FakeFiles(listOf("/a.jpg"))
        db.momentDao().insertOutbox(listOf(CleanupOutboxEntity(target = CleanupTarget.PHONE_FILE, payloadJson = CleanupPayload.one("path", "/a.jpg"), createdAt = 1)))
        db.wipeDao().wipeMemory(5_000)
        val kept = db.momentDao().pendingOutbox(); assertTrue(kept.any { it.target == CleanupTarget.PHONE_FILE }); assertTrue(kept.any { it.target == CleanupTarget.ALL })
        val r = drainer(files).drain()
        assertEquals(emptySet<String>(), files.present); assertEquals(1, r.removed)
        assertEquals("the ALL row waits for glasses and helper", listOf(CleanupTarget.ALL), db.momentDao().pendingOutbox().map { it.target })
        glasses.reachable = true; helper.reachable = true; drainer(files).drain()
        assertEquals(0, db.momentDao().outboxCount())
    }

    @Test fun attemptsPersistAcrossDrainerInstancesAndSurviveNoRowGrowth() = runBlocking<Unit> {
        db.momentDao().insertOutbox(listOf(CleanupOutboxEntity(target = CleanupTarget.PHONE_FILE, payloadJson = CleanupPayload.one("path", "/a.jpg"), createdAt = 1)))
        val files = FakeFiles(listOf("/a.jpg")).also { it.failing += "/a.jpg" }
        drainer(files).drain(); drainer(files).drain() // the second is inside the backoff
        assertEquals(1, files.calls.size)
        now += OutboxDrainer.backoffMs(1); drainer(files).drain()
        assertEquals(2, files.calls.size); assertEquals(2, RoomOutboxStore(db).attempts().values.single().count)
    }

    @Test fun theDueQueryIsBoundedInSqlPhoneFirstAndSkipsExcludedIds() = runBlocking<Unit> {
        val md = db.momentDao()
        md.insertOutbox((1..300).map { CleanupOutboxEntity(target = CleanupTarget.GLASSES_FRAME, payloadJson = CleanupPayload.one("wireFrameId", "w$it"), createdAt = it.toLong()) })
        md.insertOutbox(listOf(CleanupOutboxEntity(target = CleanupTarget.PHONE_FILE, payloadJson = CleanupPayload.one("path", "/a.jpg"), createdAt = 999)))
        val store = RoomOutboxStore(db)
        val first = store.due(10, emptyList())
        assertEquals(10, first.size); assertEquals("phone files first", CleanupTarget.PHONE_FILE, first.first().target)
        val skipped = first[1].id
        assertTrue(store.due(10, listOf(skipped)).none { it.id == skipped })
        assertEquals(301, store.count())
    }

    @Test fun trimKeepsTheNewestRowsOfOneTargetOnly() = runBlocking<Unit> {
        val md = db.momentDao()
        md.insertOutbox((1..6).map { CleanupOutboxEntity(target = CleanupTarget.GLASSES_FRAME, payloadJson = CleanupPayload.one("wireFrameId", "w$it"), createdAt = it.toLong()) })
        md.insertOutbox((1..3).map { CleanupOutboxEntity(target = CleanupTarget.PHONE_FILE, payloadJson = CleanupPayload.one("path", "/$it.jpg"), createdAt = it.toLong()) })
        assertEquals(4, RoomOutboxStore(db).trim(CleanupTarget.GLASSES_FRAME, 2))
        assertEquals(listOf("{\"wireFrameId\":\"w5\"}", "{\"wireFrameId\":\"w6\"}"), md.pendingOutbox().filter { it.target == CleanupTarget.GLASSES_FRAME }.map { it.payloadJson })
        assertEquals(3, md.pendingOutbox().count { it.target == CleanupTarget.PHONE_FILE })
    }
}
