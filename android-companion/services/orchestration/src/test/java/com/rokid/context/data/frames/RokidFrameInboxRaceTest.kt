package com.rokid.context.data.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicBoolean

/** Review B: concurrent drains (C-1), the wire-duplicate rule, the lost-sidecar window (I-2, M-2) and archive temp sweeps (M-3). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RokidFrameInboxRaceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_793_000_000_000L
    private val hour = 3_600_000L
    private val inbox get() = RokidFrameInbox.inboxDir(ctx)
    private val archive get() = RokidFrameInbox.archiveDir(ctx)
    private val db get() = MemoryGraph.get(ctx).db

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        inbox.deleteRecursively(); archive.deleteRecursively()
        inbox.mkdirs(); archive.mkdirs()
    }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    private fun stem(wire: String, rx: Long) = ReceiptNames.stem(rx, wire)

    /** A whole v2 receipt in [dir]; [prefix] is the archive's `<ms>_`. */
    private fun receipt(dir: File, wire: String, rx: Long, prefix: String = "", sidecar: Boolean = true): File {
        val jpeg = File(dir, "$prefix${stem(wire, rx)}.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        if (sidecar) ReceiptNames.sidecarBeside(jpeg).writeBytes(ReceiptMeta(wire, rx, size = 3).toJson())
        return jpeg
    }
    private suspend fun rows() = db.frameDao().count()
    private suspend fun drain(nowMs: Long = now, sync: DirSync = DirSync { true }) = RokidFrameInbox.ingestPendingIds(ctx, nowMs = { nowMs }, dirSync = sync)

    /** C-1: drain A is held inside its first directory sync while drain B runs; the archived pair ends as one row with its photo. */
    @Test fun concurrentDrainsOverOneArchivedPairKeepOneRowAndThePhoto() = runBlocking {
        DirSync.testOverride = DirSync { true }
        val rx = now - hour
        val jpeg = receipt(archive, "g1", rx, prefix = "${now - 10}_")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        val blocking = DirSync { _ ->
            if (first.getAndSet(false)) { entered.complete(Unit); runBlocking { release.await() } }
            true
        }
        val a = launch(Dispatchers.IO) { drain(sync = blocking) }
        kotlinx.coroutines.withTimeout(30_000) { entered.await() }
        val b = launch(Dispatchers.IO) { drain() }
        delay(300) // B has had every chance to interleave; with the drain lock it waits for A
        assertEquals("B must not have committed under A's feet", 0, rows())
        release.complete(Unit)
        kotlinx.coroutines.withTimeout(30_000) { a.join(); b.join() }
        assertEquals(1, rows())
        val row = db.frameDao().all().single()
        assertTrue("the row's photo exists", File(row.path).isFile)
        assertTrue(jpeg.isFile)
        assertFalse(ReceiptNames.sidecarBeside(jpeg).exists())
        DirSync.testOverride = null
    }

    /** C-1: a missing sidecar with a row present is a DUPLICATE (another path committed it), not a FAILED that bounces the JPEG. */
    @Test fun missingSidecarWithARowIsADuplicate() = runBlocking {
        val jpeg = receipt(archive, "g1", now - 24 * hour - hour / 2, prefix = "${now}_", sidecar = false)
        assertEquals(1, drain().size) // held 24 h from receipt, then defaulted: one row
        val r = RokidFrameInbox.commitArchived(ctx, jpeg, null, "rokid_glasses", null, { now }, com.rokid.context.orchestration.PipelineRecords::appendFrame, DirSync { true })
        assertEquals(InboxResult.DUPLICATE, r)
        assertEquals(1, rows())
        assertTrue(jpeg.isFile)
    }

    /** C-1: a wire duplicate whose first photo still exists drops the new copy. */
    @Test fun wireDuplicateDropsTheNewCopyWhileTheFirstPhotoExists() = runBlocking {
        val first = receipt(inbox, "g1", now - 2 * hour)
        drain()
        val row = db.frameDao().all().single()
        assertTrue(File(row.path).isFile)
        val resend = receipt(inbox, "g1", now - hour)
        drain()
        assertEquals(1, rows())
        assertFalse(resend.exists())
        assertTrue(File(db.frameDao().all().single().path).isFile)
        assertFalse(first.exists()) // it was archived, not left behind
    }

    /** C-1: the first photo is gone: the row is repointed at the new copy instead of losing both. */
    @Test fun wireDuplicateRepointsTheRowWhenTheFirstPhotoIsGone() = runBlocking {
        receipt(inbox, "g1", now - 2 * hour)
        drain()
        val row = db.frameDao().all().single()
        File(row.path).delete()
        receipt(inbox, "g1", now - hour)
        drain()
        assertEquals(1, rows())
        val after = db.frameDao().all().single()
        assertEquals(row.id, after.id)
        assertTrue("the surviving copy is kept and referenced", File(after.path).isFile)
        // and a later drain (even after the lost-sidecar window) never deletes it as a "duplicate of itself"
        drain(nowMs = now + 23 * hour + hour / 2)
        assertTrue(File(db.frameDao().all().single().path).isFile)
    }

    /** I-2: an old archived v2 JPEG (committed long ago, no sidecar) is not re-examined; a young one inside the window still is. */
    @Test fun oldArchivedBareJpegsAreNotRechecked() = runBlocking {
        val old = receipt(archive, "old", now - 48 * hour, prefix = "${now - 47 * hour}_", sidecar = false)
        val young = receipt(archive, "young", now - 24 * hour - hour / 2, prefix = "${now - 23 * hour}_", sidecar = false)
        val ids = drain()
        assertEquals(1, ids.size)
        assertEquals(1, PipelineStatsStore.sidecarLost(ctx))
        assertFalse("the old one got no defaults sidecar", ReceiptNames.sidecarBeside(old).exists())
        assertTrue(old.isFile)
        assertTrue(young.isFile)
        assertEquals(1, rows())
    }

    /** M-2: the 24 h hold counts from the receipt time (the stem's rx), not the JPEG's mtime (capture time). */
    @Test fun lostSidecarHoldCountsFromReceiptNotCaptureTime() = runBlocking {
        val jpeg = receipt(inbox, "late", now - hour, sidecar = false)
        jpeg.setLastModified(now - 3 * 24 * hour) // captured days ago, received an hour ago
        assertTrue(drain().isEmpty())
        assertEquals(0, PipelineStatsStore.sidecarLost(ctx))
        assertTrue(jpeg.isFile)
        assertEquals(1, drain(nowMs = now + 24 * hour).size)
    }

    /** M-3: orphan temps in the archive are swept too. */
    @Test fun archivePartsAreSwept() {
        val part = File(archive, "${now}_rokid_1_a.v2.meta.json.part").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 120_000) }
        val fresh = File(archive, "x.part").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 1_000) }
        assertEquals(1, RokidFrameInbox.sweepOrphanParts(ctx, nowMs = now))
        assertFalse(part.exists()); assertTrue(fresh.exists())
    }
}
