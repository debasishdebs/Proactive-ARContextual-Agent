package com.rokid.context.data.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RokidFrameInboxTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_793_000_000_000L
    private val inbox get() = RokidFrameInbox.inboxDir(ctx)
    private val archive get() = RokidFrameInbox.archiveDir(ctx)

    @Before fun setUp() {
        DirSync.testOverride = DirSync { true } // Robolectric cannot fsync a directory
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        inbox.deleteRecursively(); archive.deleteRecursively()
        inbox.mkdirs()
    }
    @After fun tearDown() { DirSync.testOverride = null; MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun inboxFile(name: String, ageMs: Long = 0) = File(inbox, name).apply { writeBytes(byteArrayOf(1, 2)); setLastModified(now - ageMs) }
    private suspend fun rows() = MemoryGraph.get(ctx).db.frameDao().count()

    @Test fun ingestLeavesRoutedAtNullOnlyWhenTheSceneIndexerIsActive() = runBlocking {
        archive.mkdirs()
        val a = File(archive, "a.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        val b = File(archive, "b.jpg").apply { writeBytes(byteArrayOf(3, 4)) }
        assertEquals(InboxResult.STORED, RokidFrameInbox.ingestFile(ctx, a, "rokid_glasses", nowMs = { now }, sceneActive = { true }))
        assertEquals(InboxResult.STORED, RokidFrameInbox.ingestFile(ctx, b, "rokid_glasses", nowMs = { now }, sceneActive = { false }))
        val meta = MemoryGraph.get(ctx).db.frameMetaDao()
        assertNull("the router sees the frame first", meta.get("a")!!.routedAt)
        assertEquals("wave-1 path: claimable at once", now, meta.get("b")!!.routedAt)
    }

    @Test fun ingestRecordsTheGlassesCaptureTimeOnlyForAReceipt() = runBlocking {
        archive.mkdirs()
        val captured = now - 90_000
        val a = File(archive, "a.jpg").apply { writeBytes(byteArrayOf(1, 2)); setLastModified(captured) }
        val b = File(archive, "b.jpg").apply { writeBytes(byteArrayOf(3, 4)); setLastModified(captured) }
        val receipt = ReceiptMeta(wireFrameId = "w-a", capturedAtMs = 7, size = 2)
        assertEquals(InboxResult.STORED, RokidFrameInbox.ingestFile(ctx, a, "rokid_glasses", receipt = receipt, nowMs = { now }, sceneActive = { false }))
        assertEquals(InboxResult.STORED, RokidFrameInbox.ingestFile(ctx, b, "rokid_glasses", nowMs = { now }, sceneActive = { false }))
        val meta = MemoryGraph.get(ctx).db.frameMetaDao()
        assertEquals("phone-clock capture time (the receipt file's mtime), not the receive time", captured, meta.get("a")!!.capturedAtMs)
        assertEquals(now, meta.get("a")!!.createdAt)
        assertNull("a file without a receipt has no known capture time", meta.get("b")!!.capturedAtMs)
    }

    @Test fun theMarkOfAReceiptReachesTheFrameMetaAndLinksAnExistingMark() = runBlocking {
        archive.mkdirs()
        val db = MemoryGraph.get(ctx).db
        val e = com.rokid.context.memory.db.DeletionEpoch("i", 1, 0)
        db.canvasSlice3Dao().insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("mk:ep-1:4", "MOMENT", "tap", "MOMENT", now - 91_000, createdAt = 1, photo = "pending"), com.rokid.context.memory.db.DaoFence(0, e, { 0 }, { e }))
        val a = File(archive, "a.jpg").apply { writeBytes(byteArrayOf(1, 2)); setLastModified(now - 90_000) }
        val b = File(archive, "b.jpg").apply { writeBytes(byteArrayOf(3, 4)); setLastModified(now - 80_000) }
        RokidFrameInbox.ingestFile(ctx, a, "rokid_glasses", receipt = ReceiptMeta(wireFrameId = "w-a", capturedAtMs = 7, size = 2, markRef = "ep-1:4"), nowMs = { now }, sceneActive = { false })
        RokidFrameInbox.ingestFile(ctx, b, "rokid_glasses", receipt = ReceiptMeta(wireFrameId = "w-b", capturedAtMs = 8, size = 2), nowMs = { now }, sceneActive = { false })
        assertEquals("ep-1:4", db.frameMetaDao().get("a")!!.markRef); assertNull(db.frameMetaDao().get("b")!!.markRef)
        assertEquals("a", db.canvasSlice3Dao().label("mk:ep-1:4")!!.frameId)
    }

    @Test fun theSidecarCarriesTheMarkAndAnOldSidecarHasNone() {
        val dir = File(inbox, "side").apply { mkdirs() }
        val withMark = ReceiptMeta(wireFrameId = "w", capturedAtMs = 7, size = 2, markRef = "ep:4")
        val f = File(dir, "x.meta.json").apply { writeBytes(withMark.toJson()) }
        assertEquals(withMark, ReceiptMeta.read(f))
        assertEquals("ep:4", ReceiptMeta.read(f)!!.markRef)
        val old = File(dir, "y.meta.json").apply { writeText("""{"v":1,"wireFrameId":"w","capturedAtMs":7,"motion":"UNKNOWN","dwell":false,"size":2}""") }
        assertNull(ReceiptMeta.read(old)!!.markRef)
        assertFalse("a resend that names another mark is not the same receipt", withMark == withMark.copy(markRef = "ep:5"))
    }

    @Test fun aReceiptMtimeOutsideOneToNowIsNotACaptureTime() = runBlocking {
        archive.mkdirs()
        val future = File(archive, "f.jpg").apply { writeBytes(byteArrayOf(1, 2)); setLastModified(now + 3_600_000) }
        val receipt = ReceiptMeta(wireFrameId = "w-f", capturedAtMs = 7, size = 2)
        assertEquals(InboxResult.STORED, RokidFrameInbox.ingestFile(ctx, future, "rokid_glasses", receipt = receipt, nowMs = { now }, sceneActive = { false }))
        assertNull("a capture time after the receive time is a clock error: unknown", MemoryGraph.get(ctx).db.frameMetaDao().get("f")!!.capturedAtMs)
    }

    @Test fun orphanPartsOlderThanAMinuteAreSwept() {
        val old = inboxFile("rokid_1_a.jpg.part", ageMs = 120_000)
        assertEquals(1, RokidFrameInbox.sweepOrphanParts(ctx, nowMs = now))
        assertFalse(old.exists())
    }

    @Test fun aFreshPartIsKept() {
        val fresh = inboxFile("rokid_1_a.jpg.part", ageMs = 10_000)
        assertEquals(0, RokidFrameInbox.sweepOrphanParts(ctx, nowMs = now))
        assertTrue(fresh.exists())
    }

    @Test fun ingestPendingSweepsOrphanPartsFirst() = runBlocking {
        val old = inboxFile("rokid_1_a.jpg.part", ageMs = 120_000)
        RokidFrameInbox.ingestPending(ctx, nowMs = { now })
        assertFalse(old.exists())
    }

    @Test fun aFailedIngestReturnsTheFileToTheInbox() = runBlocking {
        inboxFile("rokid_1_a.jpg")
        val n = RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { _, _, _ -> throw IllegalStateException("db") }
        assertEquals(0, n)
        assertTrue(File(inbox, "rokid_1_a.jpg").isFile)
        assertTrue(archive.list().orEmpty().isEmpty())
        assertEquals(1, RokidFrameInbox.ingestPending(ctx, nowMs = { now + 1 }))
        assertEquals(1, rows())
    }

    @Test fun aDuplicateStaysArchived() = runBlocking {
        val archived = File(archive.apply { mkdirs() }, "${now}_rokid_1_a.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(PipelineRecords.appendFrame(ctx, FrameEntity("${now}_rokid_1_a", 1, archived.absolutePath, "rokid_glasses")))
        inboxFile("rokid_1_a.jpg")
        assertEquals(0, RokidFrameInbox.ingestPending(ctx, nowMs = { now }))
        assertTrue(File(archive, "${now}_rokid_1_a.jpg").isFile)
        assertFalse(File(inbox, "rokid_1_a.jpg").exists())
    }

    /** Review Focus 3: a Delete all that starts while a frame is being ingested leaves no row and no JPEG. */
    @Test fun aWipeDuringIngestLeavesNoRowAndNoFile() = runBlocking {
        inboxFile("rokid_1_a.jpg")
        val n = RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { c, f, _ ->
            PipelineRecords.appendFrame(c, f).also { MemoryWipe.generation.incrementAndGet() }
        }
        assertEquals(0, n)
        assertEquals(0, rows())
        assertTrue(archive.list().orEmpty().isEmpty())
        assertTrue(inbox.list().orEmpty().isEmpty())
    }

    /** P7xP8 I3: a phone capture (single-path ingest) racing Delete all leaves no row and no file either. */
    @Test fun aWipeDuringASinglePathIngestLeavesNoRowAndNoFile() = runBlocking {
        val shot = File(archive.apply { mkdirs() }, "rokid_phone_1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = RokidFrameInbox.ingestSinglePath(ctx, shot.absolutePath, source = "rokid_phone", placeLabel = "home") { c, f, _ ->
            PipelineRecords.appendFrame(c, f).also { MemoryWipe.generation.incrementAndGet() }
        }
        assertEquals(null, id)
        assertEquals(0, rows())
        assertFalse(shot.exists())
    }

    /** Fix round 1 I4: a drain cancelled mid-insert puts the file back, so the next drain still ingests it. */
    @Test fun aCancelledIngestKeepsTheFileEligible() = runBlocking {
        inboxFile("rokid_1_a.jpg")
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { _, _, _ -> entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(File(inbox, "rokid_1_a.jpg").isFile)
        assertTrue(archive.list().orEmpty().isEmpty())
        assertEquals(0, rows())
    }

    /** Fix round 2 I4: a drain cancelled while a Delete all runs deletes the file instead of resurrecting it. */
    @Test fun aCancelDuringAWipeDeletesTheFile() = runBlocking {
        inboxFile("rokid_1_a.jpg")
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { _, _, _ -> entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        MemoryWipe.generation.incrementAndGet()
        job.cancelAndJoin()
        assertTrue(inbox.list().orEmpty().isEmpty())
        assertTrue(archive.list().orEmpty().isEmpty())
        assertEquals(0, rows())
    }

    /** Fix round 3: an ingest that fails while a Delete all runs deletes the file instead of returning it to the inbox. */
    @Test fun aFailedIngestDuringAWipeDeletesTheFile() = runBlocking {
        inboxFile("rokid_1_a.jpg")
        val n = RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { _, _, _ ->
            MemoryWipe.generation.incrementAndGet()
            throw IllegalStateException("db")
        }
        assertEquals(0, n)
        assertTrue(inbox.list().orEmpty().isEmpty())
        assertTrue(archive.list().orEmpty().isEmpty())
        assertEquals("nothing left to store later", 0, RokidFrameInbox.ingestPending(ctx, nowMs = { now + 1 }))
        assertEquals(0, rows())
    }

    /** Fix round 1: Delete all walks the inbox before the archive, so a file an ingest moves mid-walk is still seen. */
    @Test fun theInboxIsWalkedBeforeTheArchive() {
        assertEquals(listOf("rokid_inbox", "rokid_archive"), RokidFrameInbox.FRAME_DIRS)
    }
}
