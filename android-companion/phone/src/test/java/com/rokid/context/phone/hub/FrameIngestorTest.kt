package com.rokid.context.phone.hub

import android.app.Application
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.PauseHistory
import com.rokid.context.link.ReceivedFrameLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FrameIngestorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_793_000_000_000L
    private val offset = ClockOffset(140) // glasses 140 ms ahead (phase 0)
    private val jpeg = ByteArray(3_000) { (it % 200).toByte() }
    private val noPauses = object : PauseHistory { override suspend fun pausedAt(phoneMs: Long) = false }
    private var stored = 0
    private val inbox get() = File(tmp.root, "rokid_inbox")

    private val ledger by lazy { ReceivedFrameLedger(File(tmp.root, "ledger.txt")) }
    private fun ingestor(writer: JpegWriter = AtomicJpegWriter, wipeAt: Long? = null, lock: Mutex = Mutex()): FrameIngestor =
        FrameIngestor(inbox, FrameReassembler(), FrameAdmission(ledger, noPauses), ledger, writer, wipeStartedAtMs = { wipeAt }, wipeLock = lock) { stored++ }
    private fun chunk(id: String = "g1") = FrameCodec.split(id, jpeg, capturedAtMs = now - 60_000 + 140).single()

    @Test fun completeFrameIsStoredAckedAndQueued() = runTest {
        val r = ingestor().onChunk(chunk(), offset, now)
        assertEquals(FrameAck(frameId = "g1", ok = true), r.ack)
        assertEquals(now - 60_000, r.ingestedAtMs)
        val f = File(inbox, "rokid_${now - 60_000}_g1.jpg")
        assertArrayEquals(jpeg, f.readBytes())
        assertTrue("capture time is the file time RokidFrameInbox reads", abs(f.lastModified() - (now - 60_000)) < 1_000)
        assertEquals(1, stored)
    }

    @Test fun resentFrameAfterALostAckIsNotStoredTwice() = runTest {
        val ing = ingestor()
        ing.onChunk(chunk(), offset, now)
        val again = ing.onChunk(chunk(), offset, now + 7_000)
        assertEquals(FrameAck(frameId = "g1", ok = true, reason = FrameAckReason.DUPLICATE), again.ack)
        assertEquals(1, inbox.listFiles()!!.size)
        assertEquals(1, stored)
    }

    /** Review Focus 2 (master 5): clean failure, no ack (the glasses keep it), nothing half-written, later resend works. */
    @Test fun storageFullLeavesNoFileSendsNoAckAndRecoversLater() = runTest {
        var full = true
        val writer = JpegWriter { f, b -> if (full) throw IOException("write failed: ENOSPC (No space left on device)") else AtomicJpegWriter.write(f, b) }
        val ing = ingestor(writer)
        val r = ing.onChunk(chunk(), offset, now)
        assertNull(r.ack)
        assertEquals(UserFacingErrors.StorageFull, r.error)
        assertTrue(inbox.listFiles().orEmpty().isEmpty())
        assertEquals(0, stored)
        full = false
        assertEquals(FrameAck(frameId = "g1", ok = true), ing.onChunk(chunk(), offset, now + 7_000).ack)
        assertEquals(1, stored)
    }

    @Test fun corruptFrameIsNackedAndAFrameBeforeHelloIsHeld() = runTest {
        val ing = ingestor()
        val c = chunk()
        val bad = c.copy(chunk = c.chunk.replaceRange(4, 5, if (c.chunk[4] == 'A') "B" else "A"))
        assertEquals(FrameAck(frameId = "g1", ok = false, reason = FrameAckReason.CRC), ing.onChunk(bad, offset, now).ack)
        assertNull(ing.onChunk(chunk("g2"), offset = null, nowMs = now).ack)
        assertEquals(0, stored)
    }

    @Test fun atomicWriterLeavesOnlyTheTarget() {
        val dir = File(tmp.root, "in")
        AtomicJpegWriter.write(File(dir, "a.jpg"), jpeg)
        assertEquals(listOf("a.jpg"), dir.list()!!.toList())
    }

    @Test fun noSpaceIsRecognisedThroughTheCauseChain() {
        assertTrue(isNoSpace(IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))))
        assertTrue(isNoSpace(IOException("No space left on device")))
        assertFalse(isNoSpace(IOException("Permission denied")))
    }
    /** Ruling (Task 4 review): a resend that arrives while the first copy is being written is not stored twice. */
    @Test fun aResendDuringTheWriteIsNotStoredTwice() = runBlocking {
        val writes = AtomicInteger()
        val slow = JpegWriter { f, b -> writes.incrementAndGet(); Thread.sleep(200); AtomicJpegWriter.write(f, b) }
        val ing = ingestor(slow)
        val acks = (1..2).map { async(Dispatchers.Default) { ing.onChunk(chunk(), offset, now).ack } }.awaitAll()
        assertEquals(1, writes.get())
        assertEquals(setOf(FrameAck(frameId = "g1", ok = true), FrameAck(frameId = "g1", ok = true, reason = FrameAckReason.DUPLICATE)), acks.toSet())
        assertEquals(1, stored)
    }

    /** Ruling (Task 4 review): reassembly staleness runs on the monotonic clock, so a wall-clock jump keeps a partial. */
    @Test fun aWallClockJumpMidFrameDoesNotDropThePartial() = runTest {
        var mono = 1_000L
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ing = FrameIngestor(inbox, FrameReassembler(), FrameAdmission(ledger, noPauses), ledger, monotonicMs = { mono }) { stored++ }
        val parts = FrameCodec.split("g7", ByteArray(40_000) { (it % 251).toByte() }, capturedAtMs = now - 60_000 + 140)
        assertEquals(2, parts.size)
        assertNull(ing.onChunk(parts[0], offset, now).ack)
        mono += 1_000
        assertEquals(FrameAck(frameId = "g7", ok = true), ing.onChunk(parts[1], offset, now + 60_000).ack)
    }

    /** Ruling (Task 4 review): a held frame is flagged so the hub can ask the glasses for a fresh hello. */
    @Test fun heldFramesAreFlagged() = runTest {
        val ing = ingestor()
        assertTrue("no offset yet", ing.onChunk(chunk("g3"), offset = null, nowMs = now).held)
        val future = FrameCodec.split("g4", jpeg, capturedAtMs = now + 60_000 + 140).single()
        val r = ing.onChunk(future, offset, now)
        assertNull(r.ack)
        assertTrue("stale offset", r.held)
        assertFalse(ing.onChunk(chunk("g5"), offset, now).held)
    }
    /** Fix round 1: the queue append / WorkManager enqueue failing on a full disk must not escape (the frame is durable). */
    @Test fun aFailingQueueEnqueueIsReportedNotThrown() = runTest {
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ing = FrameIngestor(inbox, FrameReassembler(), FrameAdmission(ledger, noPauses), ledger) {
            throw IOException("write failed: ENOSPC (No space left on device)")
        }
        val r = ing.onChunk(chunk(), offset, now)
        assertEquals("stored durably in inbox + ledger, so the glasses may delete it", FrameAck(frameId = "g1", ok = true), r.ack)
        assertEquals(UserFacingErrors.StorageFull, r.error)
        assertNotNull(File(inbox, "rokid_${now - 60_000}_g1.jpg").takeIf { it.isFile })
        assertTrue(ledger.contains("g1"))
        val other = FrameIngestor(inbox, FrameReassembler(), FrameAdmission(ledger, noPauses), ledger) { error("boom") }
        assertEquals(UserFacingErrors.Unknown, other.onChunk(chunk("g2"), offset, now).error)
    }

    /** Fix round 1: the capture time is on the file before it appears under its inbox name (the rename keeps it). */
    @Test fun atomicWriterStampsTheCaptureTimeBeforeTheRename() {
        val dir = File(tmp.root, "in2")
        val stamp = now - 3_600_000
        var seenAtRename: Long? = null
        AtomicJpegWriter.write(File(dir, "b.jpg"), jpeg, lastModifiedMs = stamp, beforeRename = { part -> seenAtRename = part.lastModified() })
        assertTrue(abs(seenAtRename!! - stamp) < 1_000)
        assertTrue(abs(File(dir, "b.jpg").lastModified() - stamp) < 1_000)
        assertEquals(listOf("b.jpg"), dir.list()!!.toList())
    }

    /** Review Focus 3, spec 6.1.1: Delete all covers frames still buffered on the glasses when it started. */
    @Test fun aFrameCapturedBeforeDeleteAllIsAckedAndNotStored() = runTest {
        val r = ingestor(wipeAt = now - 30_000).onChunk(chunk(), offset, now)
        assertEquals(FrameAck(frameId = "g1", ok = true, reason = FrameAckReason.WIPED), r.ack)
        assertEquals("wiped_drop", r.ack!!.reason)
        assertTrue(inbox.listFiles().orEmpty().isEmpty())
        assertTrue(ledger.contains("g1"))
        assertEquals(0, stored)
    }

    @Test fun aStoreWaitsForAWipeInProgress() = runTest {
        val lock = Mutex()
        lock.lock()
        val ing = ingestor(lock = lock)
        val pending = async { ing.onChunk(chunk(), offset, now) }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertTrue(inbox.listFiles().orEmpty().isEmpty())
        lock.unlock()
        assertEquals(FrameAck(frameId = "g1", ok = true), pending.await().ack)
    }

    @Test fun aFrameCapturedAfterTheWipeIsStored() = runTest {
        val r = ingestor(wipeAt = now - 90_000).onChunk(chunk(), offset, now)
        assertEquals(FrameAck(frameId = "g1", ok = true), r.ack)
        assertTrue(File(inbox, "rokid_${now - 60_000}_g1.jpg").isFile)
        assertEquals(1, stored)
    }
}
