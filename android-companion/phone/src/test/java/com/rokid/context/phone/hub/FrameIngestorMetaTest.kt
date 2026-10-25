package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.MotionState
import com.rokid.context.data.frames.DirSync
import com.rokid.context.data.frames.ReceiptMeta
import com.rokid.context.data.frames.ReceiptScan
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.PauseHistory
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.WipeFence
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Keyframe capture P1: the durable v2 receipt, its drain, and the reconciliation after a death. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FrameIngestorMetaTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_793_000_000_000L
    private val offset = ClockOffset(140)
    private val rx = now - 60_000
    private val jpeg = ByteArray(3_000) { (it % 200).toByte() }
    private val noPauses = object : PauseHistory { override suspend fun pausedAt(phoneMs: Long) = false }
    private var stored = 0
    private val inbox get() = RokidFrameInbox.inboxDir(ctx)
    private val archive get() = RokidFrameInbox.archiveDir(ctx)
    private var ledgerOrNull: ReceivedFrameLedger? = null
    private var ledger: ReceivedFrameLedger
        get() = ledgerOrNull ?: ReceivedFrameLedger(File(tmp.root, "ledger.txt")).also { ledgerOrNull = it }
        set(v) { ledgerOrNull = v }
    private val db get() = MemoryGraph.get(ctx).db

    /** A process death: an Error nothing in the ingest path catches, so no cleanup runs. */
    private class Death : Error()

    private class RecordingSync(var failing: Set<File> = emptySet()) : DirSync {
        val calls = mutableListOf<String>()
        override fun sync(dir: File): Boolean { calls += dir.name; return dir.absoluteFile !in failing.map { it.absoluteFile } }
    }

    private class OrderWriter(var failOn: (File) -> Boolean = { false }) : JpegWriter {
        val order = mutableListOf<String>()
        override fun write(target: File, bytes: ByteArray) {
            if (failOn(target)) throw IOException("write failed")
            AtomicJpegWriter.write(target, bytes)
            order += target.name
        }
    }

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        inbox.deleteRecursively(); archive.deleteRecursively()
        inbox.mkdirs(); archive.mkdirs()
    }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    private fun ingestor(writer: JpegWriter = AtomicJpegWriter, sync: DirSync = RecordingSync()) = FrameIngestor(
        inbox, FrameReassembler(), FrameAdmission(ledger, noPauses), ledger, writer,
        wipeFence = { WipeFence.Fence.None }, wipeLock = Mutex(), archiveDir = archive, dirSync = sync,
        committed = { db.frameMetaDao().byWireId(it) != null },
    ) { stored++ }

    private fun chunk(id: String = "g1"): FrameChunk = FrameCodec.split(
        id, jpeg, capturedAtMs = rx + 140, phash = "ff00ff00ff00ff00", motion = MotionState.HEAD_MOVING, dwell = true, blur = 12.5f,
        dhash = "00ff00ff00ff00ff", width = 640, sampleSeq = 7, sessionId = "s1",
    ).single()
    private fun oldChunk(id: String = "g1"): FrameChunk = FrameCodec.split(id, jpeg, capturedAtMs = rx + 140).single()

    private suspend fun ingest(id: String = "g1", ing: FrameIngestor = ingestor()) = ing.onChunk(chunk(id), offset, now)
    /** The stem carries the RECEIPT time (the ingest's nowMs), the file mtime the capture time. */
    private fun stem(id: String = "g1") = "rokid_${now}_$id.v2"
    private fun finals(dir: File) = dir.list().orEmpty().filter { !it.endsWith(".part") }.sorted()
    private suspend fun drain(sync: DirSync = DirSync { true }, nowMs: Long = now, appender: com.rokid.context.data.frames.FrameAppender = PipelineRecords::appendFrame) =
        RokidFrameInbox.ingestPendingIds(ctx, nowMs = { nowMs }, dirSync = sync, appendFrame = appender)
    private suspend fun rows() = db.frameDao().count()

    /** v0.9.6 slice C: a photo taken for a mark passes a pause, carries its mark through the sidecar into `frame_meta` and links the mark; an ordinary frame of the same moment is dropped. */
    @Test fun aMarkPhotoPassesAPauseCarriesItsMarkToTheRowAndLinksIt() = runBlocking {
        val paused = object : PauseHistory { override suspend fun pausedAt(phoneMs: Long) = true }
        val ing = FrameIngestor(inbox, FrameReassembler(), FrameAdmission(ledger, paused), ledger, AtomicJpegWriter, wipeFence = { WipeFence.Fence.None }, wipeLock = Mutex(), archiveDir = archive, dirSync = RecordingSync(),
            committed = { db.frameMetaDao().byWireId(it) != null }) { stored++ }
        val e = com.rokid.context.memory.db.DeletionEpoch("i", 1, 0)
        db.canvasSlice3Dao().insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("mk:ep-1:4", "MOMENT", "tap", "MOMENT", rx, createdAt = 1, photo = "pending"), com.rokid.context.memory.db.DaoFence(0, e, { 0 }, { e }))
        val plain = ing.onChunk(FrameCodec.split("g1", jpeg, capturedAtMs = rx + 140).single(), offset, now)
        assertEquals(FrameAckReason.PAUSED_DROP, plain.ack!!.reason)
        val photo = ing.onChunk(FrameCodec.split("g2", jpeg, capturedAtMs = rx + 140, markRef = "ep-1:4").single(), offset, now)
        assertTrue(photo.ack!!.ok); assertNull(photo.ack!!.reason)
        assertEquals(1, stored)
        drain()
        val meta = db.frameMetaDao().byWireId("g2")!!
        assertEquals("ep-1:4", meta.markRef)
        assertEquals(meta.frameId, db.canvasSlice3Dao().label("mk:ep-1:4")!!.frameId)
        assertEquals(1, rows())
    }

    @Test fun sidecarBeforeJpegThenAck() = runBlocking {
        val w = OrderWriter()
        val r = ingest(ing = ingestor(w))
        assertEquals(listOf("${stem()}.meta.json", "${stem()}.jpg"), w.order)
        assertEquals(FrameAck(frameId = "g1", ok = true), r.ack)
        assertEquals(listOf("${stem()}.jpg", "${stem()}.meta.json"), finals(inbox))
    }

    @Test fun ackOnlyAfterDurableWrite() = runBlocking {
        val w = OrderWriter(failOn = { it.name.endsWith(".jpg") })
        val r = ingest(ing = ingestor(w))
        assertNull("a failed JPEG write must not be acked", r.ack)
        assertEquals(0, stored)
        assertFalse(inbox.list().orEmpty().any { it.endsWith(".part") })
        assertFalse(ledger.contains("g1"))
    }

    @Test fun dirSyncFailureMeansNoAck() = runBlocking {
        val sync = RecordingSync(failing = setOf(inbox))
        val r = ingest(ing = ingestor(sync = sync))
        assertNull(r.ack)
        assertEquals(0, stored)
        assertFalse("temp files are gone", inbox.list().orEmpty().any { it.endsWith(".part") })
        assertFalse(ledger.contains("g1"))
    }

    @Test fun archiveDirSyncFailureKeepsSidecar() = runBlocking {
        ingest()
        assertTrue(drain(sync = RecordingSync(failing = setOf(archive))).isEmpty())
        assertEquals(0, rows())
        assertEquals(2, archive.list()!!.size)
        assertTrue(archive.list()!!.any { it.endsWith(".meta.json") })
        assertEquals(1, drain().size)
        assertEquals(1, rows())
        assertTrue("sidecar goes after the commit", archive.list()!!.none { it.endsWith(".meta.json") })
    }

    @Test fun deathAfterArchiveRenameReconciled() = runBlocking {
        ingest()
        try { drain(appender = { _, _, _ -> throw Death() }); error("no death") } catch (_: Death) { }
        assertEquals(0, rows())
        assertTrue(inbox.list().orEmpty().isEmpty())
        assertEquals(2, archive.list()!!.size)
        val ids = drain()
        assertEquals(1, ids.size)
        assertEquals(1, rows())
        val m = db.frameMetaDao().get(ids.single())!!
        assertEquals("HEAD_MOVING", m.motion); assertEquals(true, m.dwell); assertEquals("g1", m.wireFrameId)
        assertEquals(1, drain().let { rows() })
    }

    @Test fun deathBetweenTheTwoRenamesReunitesPair() = runBlocking {
        ingest()
        File(inbox, "${stem()}.meta.json").renameTo(File(archive, "${now}_${stem()}.meta.json")) // died after the first rename
        assertEquals(listOf("${stem()}.jpg"), finals(inbox))
        val ids = drain()
        assertEquals(1, ids.size)
        assertEquals(1, rows())
        val m = db.frameMetaDao().get(ids.single())!!
        assertEquals("g1", m.wireFrameId); assertEquals("s1", m.sessionId); assertEquals("HEAD_MOVING", m.motion)
        assertEquals(true, m.dwell); assertEquals(12.5f, m.blur); assertEquals("00ff00ff00ff00ff", m.dhash)
        assertEquals("ff00ff00ff00ff00", m.phash); assertEquals(640, m.width)
        assertTrue(inbox.list().orEmpty().isEmpty())
    }

    @Test fun v2JpegWithoutSidecarHeldNotDefaulted() = runBlocking {
        ingest()
        File(inbox, "${stem()}.meta.json").delete()
        assertTrue(drain().isEmpty())
        assertEquals(0, rows())
        assertTrue(File(inbox, "${stem()}.jpg").isFile)
        assertEquals(0, PipelineStatsStore.sidecarLost(ctx))
        val later = drain(nowMs = now + 25 * 3_600_000L)
        assertEquals(1, later.size)
        val m = db.frameMetaDao().get(later.single())!!
        assertEquals("UNKNOWN", m.motion); assertEquals(false, m.dwell); assertEquals("g1", m.wireFrameId)
        assertEquals(1, PipelineStatsStore.sidecarLost(ctx))
    }

    @Test fun legacyJpegGetsDefaults() = runBlocking {
        File(inbox, "rokid_1_a.jpg").apply { writeBytes(byteArrayOf(1, 2)); setLastModified(now) }
        val ids = drain()
        assertEquals(1, ids.size)
        val m = db.frameMetaDao().get(ids.single())!!
        assertEquals("UNKNOWN", m.motion); assertEquals(false, m.dwell); assertNull(m.wireFrameId)
        assertEquals("PENDING", db.frameStateDao().get(ids.single())!!.state)
    }

    @Test fun sidecarOnlyAfterCrashResendCompletesBeforeAck() = runBlocking {
        val w = OrderWriter(failOn = { it.name.endsWith(".jpg") })
        assertNull(ingest(ing = ingestor(w)).ack) // the sidecar was published, the JPEG was not
        assertEquals(listOf("${stem()}.meta.json"), finals(inbox))
        val sync = RecordingSync()
        val r = ingest(ing = ingestor(sync = sync))
        assertEquals(FrameAck(frameId = "g1", ok = true), r.ack)
        assertEquals(listOf("${stem()}.jpg", "${stem()}.meta.json"), finals(inbox))
        assertTrue(sync.calls.contains(inbox.name))
        assertEquals(1, ReceiptScan.scan(inbox, archive).size)
        assertEquals(jpeg.size.toLong(), ReceiptMeta.read(File(inbox, "${stem()}.meta.json"))!!.size)
    }

    @Test fun mismatchedSidecarRewrittenFromResend() = runBlocking {
        ingest()
        val side = File(inbox, "${stem()}.meta.json")
        val good = ReceiptMeta.read(side)!!
        side.writeBytes(good.copy(motion = "STILL", dwell = false).toJson())
        val r = ingest()
        assertEquals(FrameAck(frameId = "g1", ok = true), r.ack)
        assertEquals(good, ReceiptMeta.read(side))
        assertEquals(2, finals(inbox).size)
    }

    @Test fun resendWhileDirSyncFailsNoAckThenOneReceipt() = runBlocking {
        val sync = RecordingSync(failing = setOf(inbox))
        assertNull(ingest(ing = ingestor(sync = sync)).ack)
        assertNull(ingest(ing = ingestor(sync = sync)).ack)
        sync.failing = emptySet()
        assertEquals(FrameAck(frameId = "g1", ok = true, reason = FrameAckReason.DUPLICATE), ingest(ing = ingestor(sync = sync)).ack)
        assertEquals(1, ReceiptScan.scan(inbox, archive).size)
        assertEquals(2, finals(inbox).size)
    }

    @Test fun drainSyncsInboxAndArchive() = runBlocking {
        ingest()
        val sync = RecordingSync(failing = setOf(inbox))
        assertTrue(drain(sync = sync).isEmpty())
        assertTrue(sync.calls.containsAll(listOf(archive.name, inbox.name)))
        assertEquals("a failed inbox sync stops before the commit", 0, rows())
        assertEquals(1, drain().size)
        assertEquals(1, rows())
    }

    @Test fun reconcileScansInboxAndArchive() = runBlocking {
        ingest("g1"); ingest("g2"); ingest("g3")
        for (n in listOf("${stem("g1")}.jpg", "${stem("g1")}.meta.json")) File(inbox, n).renameTo(File(archive, "${now}_$n"))
        File(inbox, "${stem("g2")}.meta.json").renameTo(File(archive, "${now}_${stem("g2")}.meta.json"))
        val ids = drain()
        assertEquals(3, ids.size)
        assertEquals(3, rows())
        assertEquals(setOf("g1", "g2", "g3"), ids.map { db.frameMetaDao().get(it)!!.wireFrameId }.toSet())
    }

    @Test fun metaAndStateInOneTransaction() = runBlocking {
        ingest()
        db.openHelper.writableDatabase.execSQL("DROP TABLE frame_state") // the third insert of the transaction fails
        val ids = drain()
        assertTrue(ids.isEmpty())
        assertEquals("the frame row rolled back with it", 0, rows())
        assertNull(db.frameMetaDao().byWireId("g1"))
        assertTrue("a failed commit returns the receipt to the inbox", File(inbox, "${stem()}.jpg").isFile && File(inbox, "${stem()}.meta.json").isFile)
        assertTrue(archive.list().orEmpty().isEmpty())
    }

    @Test fun resendAfterCrashDedupesByWireId() = runBlocking {
        ingest("g1")
        val id = drain().single()
        ledger = ReceivedFrameLedger(File(tmp.root, "ledger2.txt")) // the ledger was lost: only Room knows g1
        assertEquals(FrameAck(frameId = "g1", ok = true, reason = FrameAckReason.DUPLICATE), ingest("g1").ack)
        assertTrue("nothing new in the inbox", inbox.list().orEmpty().isEmpty())
        assertEquals(1, rows())
        assertNotNull(db.frameMetaDao().get(id))
        // crash before the ledger and before any commit: the resend is verified against the files, never duplicated
        ingest("g2")
        ledger = ReceivedFrameLedger(File(tmp.root, "ledger3.txt"))
        assertEquals(FrameAck(frameId = "g2", ok = true, reason = FrameAckReason.DUPLICATE), ingest("g2").ack)
        assertEquals(1, ReceiptScan.scan(inbox, archive).values.count { it.wireId == "g2" })
        assertEquals(2, finals(inbox).size)
    }

    @Test fun oldGlassesFrameGetsDefaults() = runBlocking {
        ingestor().onChunk(oldChunk(), offset, now)
        val m = db.frameMetaDao().get(drain().single())!!
        assertEquals("UNKNOWN", m.motion); assertEquals(false, m.dwell); assertNull(m.blur); assertNull(m.dhash); assertNull(m.sessionId)
        assertEquals("g1", m.wireFrameId)
    }

    @Test fun dbIdMapsToWireId() = runBlocking {
        ingest()
        val id = drain().single()
        assertEquals("${now}_${stem()}", id)
        assertEquals("g1", db.frameMetaDao().get(id)!!.wireFrameId)
        assertEquals(id, db.frameMetaDao().byWireId("g1")!!.frameId)
        assertEquals(id, db.frameStateDao().get(id)!!.frameId)
        assertEquals(id, db.frameDao().byIds(listOf(id)).single().id)
    }

    /** Counts directory listings (what ReceiptScan does), whatever the archive's size. */
    private class CountingDir(path: File) : File(path.path) {
        var listings = 0
        override fun listFiles(): Array<File>? { listings++; return super.listFiles() }
    }

    private fun bigArchive(n: Int): CountingDir {
        for (i in 0 until n) {
            val base = "${1_000 + i}_rokid_${1_000 + i}_old$i.v2"
            File(archive, "$base.jpg").writeBytes(byteArrayOf(1)); File(archive, "$base.meta.json").writeBytes(byteArrayOf(2))
        }
        archive.setLastModified(System.currentTimeMillis() - 3_600_000) // quiet for a while: its listing may be trusted
        return CountingDir(archive)
    }

    private fun countingIngestor(arch: File, inb: File) = FrameIngestor(
        inb, FrameReassembler(), FrameAdmission(ledger, noPauses), ledger, AtomicJpegWriter,
        wipeFence = { WipeFence.Fence.None }, wipeLock = Mutex(), archiveDir = arch, dirSync = RecordingSync(),
        committed = { false },
    ) { stored++ }

    /** Final review I4: per-frame cost must not grow with the archive. New frames never list it; resends list it once. */
    @Test fun aLargeArchiveIsNotListedPerFrame() = runBlocking {
        val arch = bigArchive(1_500)
        val inb = CountingDir(inbox)
        val ing = countingIngestor(arch, inb)
        for (i in 1..6) assertEquals(true, ing.onChunk(chunk("n$i"), offset, now).ack!!.ok)
        assertEquals("new frames: no archive listing", 0, arch.listings)
        assertTrue("one inbox listing per frame at most (${inb.listings})", inb.listings <= 6)
        // glasses resend everything (acks lost): the archive is listed once for all of them while it stays quiet
        for (i in 1..6) assertEquals(FrameAckReason.DUPLICATE, ing.onChunk(chunk("n$i"), offset, now + 1).ack!!.reason)
        assertTrue("archive listings for 6 resends: ${arch.listings}", arch.listings <= 1)
    }

    /** Final review minor 1: the stem is the receipt time; a duplicate rewrite keeps the mtime (capture time) its receipt has. */
    @Test fun duplicateRewriteKeepsTheReceiptsCaptureTime() = runBlocking {
        val ing = ingestor()
        ingest(ing = ing)
        val captured = File(inbox, "${stem()}.jpg").lastModified()
        assertTrue("capture time, not receipt time", captured != now)
        File(inbox, "${stem()}.meta.json").delete() // a missing half is rewritten from the resend
        ing.onChunk(chunk(), offset, now + 5_000)
        assertEquals(captured, File(inbox, "${stem()}.jpg").lastModified())
        assertEquals(captured, File(inbox, "${stem()}.meta.json").lastModified())
    }
}
