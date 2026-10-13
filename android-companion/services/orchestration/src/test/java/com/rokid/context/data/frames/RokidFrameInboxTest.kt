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
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        inbox.deleteRecursively(); archive.deleteRecursively()
        inbox.mkdirs()
    }
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun inboxFile(name: String, ageMs: Long = 0) = File(inbox, name).apply { writeBytes(byteArrayOf(1, 2)); setLastModified(now - ageMs) }
    private suspend fun rows() = MemoryGraph.get(ctx).db.frameDao().count()

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
        val n = RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { _, _ -> throw IllegalStateException("db") }
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
        val n = RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { c, f ->
            PipelineRecords.appendFrame(c, f).also { MemoryWipe.generation.incrementAndGet() }
        }
        assertEquals(0, n)
        assertEquals(0, rows())
        assertTrue(archive.list().orEmpty().isEmpty())
        assertTrue(inbox.list().orEmpty().isEmpty())
    }

    /** Fix round 1 I4: a drain cancelled mid-insert puts the file back, so the next drain still ingests it. */
    @Test fun aCancelledIngestKeepsTheFileEligible() = runBlocking {
        inboxFile("rokid_1_a.jpg")
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { _, _ -> entered.complete(Unit); awaitCancellation() }
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
            RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { _, _ -> entered.complete(Unit); awaitCancellation() }
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
        val n = RokidFrameInbox.ingestPending(ctx, nowMs = { now }) { _, _ ->
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
