package com.rokid.context.memory.forget

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.CleanupTarget
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v0.9.5: EVERY path that deletes a photo, frame, trace or derived artefact writes the same content-free `cleanup_outbox` rows (phone files, glasses frames, tombstoned traces, glasses ranges)
 * in the SAME transaction, through the one helper [CleanupOutbox]: `forgetMoment`, `forgetFact(s)`, retention's `expire` and `expirePhotos`. (MomentForget and the late-frame path were first, see their tests.)
 * The helper's copies (`canvas_helper_cleanup`) are written by the range cascade these paths already call.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetOutboxPathsTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentForgetFixture(db)
    private val s3 get() = db.canvasSlice3Dao()
    @After fun close() = db.close()

    private fun file(p: String) = CleanupTarget.PHONE_FILE to "{\"path\":\"$p\"}"
    private fun wire(w: String) = CleanupTarget.GLASSES_FRAME to "{\"wireFrameId\":\"$w\"}"
    private fun linkRange(ts: Long) = CleanupTarget.GLASSES_RANGE to "{\"fromMs\":${ts - 10_000},\"toMs\":${ts + 10_000}}"
    private suspend fun trace() {
        val e = com.rokid.context.memory.db.DeletionEpoch("i", 1, 0); val f = com.rokid.context.memory.db.DaoFence(0, e, { 0 }, { e })
        s3.acceptTraceFenced(com.rokid.context.memory.db.CanvasTraceEntity("trace-aaa-01", "HUB", "COMPLETE", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = MS, endedAtMs = MS + 500, createdAt = 1), f)
    }

    @Test fun forgetMomentQueuesThePhoneFilesTheGlassesFrameTheTraceAndTheLinkRange() = runBlocking<Unit> {
        fx.frame("a", MS + 5, wire = "g_a"); fx.memory("m1", "a"); trace()
        fx.service.forgetMoment("a")
        val box = fx.outbox()
        assertTrue(box.toString(), box.containsAll(listOf(file("/p/a.jpg"), file("/p/a.meta.json"), wire("g_a"), linkRange(MS + 5), CleanupTarget.GLASSES_TRACE to "{\"traceId\":\"trace-aaa-01\"}")))
        assertEquals("the photo and its rows are gone", emptyList<String>(), fx.frameIds())
    }

    @Test fun forgetFactQueuesTheJpegAndGlassesFrameOfAPhotoNoMemoryCitesAnyMore() = runBlocking<Unit> {
        fx.frame("a", MS + 5, wire = "g_a"); fx.memory("m1", "a")
        fx.frame("b", MS + 500_000, wire = "g_b"); fx.memory("m2", "b"); fx.memory("m3", "b")
        fx.service.forgetFact("m1"); fx.service.forgetFact("m2")
        val box = fx.outbox()
        assertTrue(box.toString(), box.containsAll(listOf(file("/p/a.jpg"), wire("g_a"), linkRange(MS + 5))))
        assertFalse("b is still cited by m3: its photo is not queued", box.any { it.second.contains("/p/b.jpg") || it.second.contains("g_b") })
    }

    @Test fun retentionExpiryOfAMemoryQueuesTheLegacyFramesJpeg() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a")
        fx.service.expire(listOf("m1"))
        assertTrue(fx.outbox().toString(), fx.outbox().contains(file("/p/a.jpg")))
    }

    @Test fun photoExpiryQueuesTheJpegTheSidecarTheGlassesFrameAndTheLinkRange() = runBlocking<Unit> {
        fx.frame("a", MS + 5, wire = "g_a"); fx.memory("m1", "a"); trace()
        fx.service.expirePhotos(listOf("a"))
        val box = fx.outbox()
        assertTrue(box.toString(), box.containsAll(listOf(file("/p/a.jpg"), file("/p/a.meta.json"), wire("g_a"), linkRange(MS + 5), CleanupTarget.GLASSES_TRACE to "{\"traceId\":\"trace-aaa-01\"}")))
    }

    @Test fun anExpiryThatSkipsEveryPhotoQueuesNothing() = runBlocking<Unit> {
        fx.frame("a", MS + 5, wire = "g_a")
        fx.service.expirePhotos(listOf("a"), requireLinked = true) // no memory cites it: retention never deletes it
        assertEquals(emptyList<Pair<String, String>>(), fx.outbox()); assertEquals(listOf("a"), fx.frameIds())
    }

    @Test fun aCanvasRangeForgetQueuesTheTombstonedGlassesTracesAndTheGlassesRange() = runBlocking<Unit> {
        trace()
        CanvasSlice3Forget(db).forgetRange(MS, MS + 999)
        assertEquals(setOf(CleanupTarget.GLASSES_TRACE to "{\"traceId\":\"trace-aaa-01\"}", CleanupTarget.GLASSES_RANGE to "{\"fromMs\":$MS,\"toMs\":${MS + 999}}"), fx.outbox().toSet())
    }

    @Test fun momentForgetKeepsItsOwnRowsAndAddsNoPhotoLinkRangeOfItsNestedCascade() = runBlocking<Unit> {
        fx.frame("a", MS + 5, wire = "g_a"); fx.memory("m1", "a")
        fx.forget.forgetMoments(listOf(fx.id))
        assertEquals(listOf(CleanupTarget.GLASSES_RANGE to "{\"fromMs\":$MS,\"toMs\":${MS + 999}}"), fx.outbox().filter { it.first == CleanupTarget.GLASSES_RANGE })
    }

    @Test fun theRowsAndTheDeleteCommitOrRollBackTogether() = runBlocking<Unit> {
        fx.frame("a", MS + 5, wire = "g_a"); fx.memory("m1", "a")
        runCatching { db.withTransaction { fx.service.forgetMomentTx("a"); throw IllegalStateException("boom") } }
        assertEquals("the delete rolled back", listOf("a"), fx.frameIds()); assertEquals("and so did its outbox rows", emptyList<Pair<String, String>>(), fx.outbox())
        runCatching { db.withTransaction { fx.service.expirePhotosTx(listOf("a")); throw IllegalStateException("boom") } }
        assertEquals(emptyList<Pair<String, String>>(), fx.outbox())
    }

    @Test fun aRepeatedForgetQueuesNothingNewAndTheMomentForgetCountStaysItsOwn() = runBlocking<Unit> {
        fx.frame("a", MS + 5, wire = "g_a"); fx.memory("m1", "a")
        fx.service.forgetMoment("a"); val first = fx.outbox()
        fx.service.forgetMoment("a")
        assertEquals(first, fx.outbox())
        val r = fx.forget.forgetMoments(listOf(fx.id)) // the aggregate Moment Forget still reports ITS rows
        assertTrue("the range and tombstoned-second rows are new for it", r.outboxRows >= 1)
    }
}
