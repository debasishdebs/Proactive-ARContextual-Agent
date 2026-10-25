package com.rokid.context.phone.hub

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FileDurability
import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.FrameMeta
import com.rokid.context.core.model.UserFacingError
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.data.frames.DirSync
import com.rokid.context.data.frames.ReceiptMeta
import com.rokid.context.data.runtime.AppConfigReader
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.link.Admission
import com.rokid.context.link.Assembly
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.orchestration.WipeFence
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Writes [bytes] to [target] so a reader never sees a partial file. */
fun interface JpegWriter {
    @Throws(IOException::class)
    fun write(target: File, bytes: ByteArray)

    /** Writes and stamps the file time (RokidFrameInbox reads the capture time from it). */
    @Throws(IOException::class)
    fun writeStamped(target: File, bytes: ByteArray, lastModifiedMs: Long) {
        write(target, bytes)
        target.setLastModified(lastModifiedMs)
    }
}

object AtomicJpegWriter : JpegWriter {
    override fun write(target: File, bytes: ByteArray) = write(target, bytes, lastModifiedMs = null)

    /** The time is stamped on the `.part` before the rename (which keeps it), so no reader sees the receipt time. */
    override fun writeStamped(target: File, bytes: ByteArray, lastModifiedMs: Long) = write(target, bytes, lastModifiedMs)

    internal fun write(
        target: File,
        bytes: ByteArray,
        lastModifiedMs: Long?,
        beforeRename: (File) -> Unit = {},
        fsyncDir: (File) -> Boolean = FileDurability::fsyncDir,
    ) {
        val dir = target.parentFile ?: throw IOException("no parent directory")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        // ".part" is not an inbox extension, so RokidFrameInbox never picks up a half-written file.
        val tmp = File(dir, "${target.name}.part")
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            lastModifiedMs?.let { tmp.setLastModified(it) }
            beforeRename(tmp)
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        // The rename is durable only once the directory entry is; a failure here still leaves a whole file.
        if (!fsyncDir(dir)) PcLog.w(FrameIngestor.TAG, "frame dir not fsynced")
    }
}

/** ENOSPC anywhere in the cause chain (Review Focus 2). */
fun isNoSpace(t: Throwable): Boolean = generateSequence(t) { it.cause }.take(8).any { c ->
    (c is ErrnoException && c.errno == OsConstants.ENOSPC) ||
        c.message?.let { "ENOSPC" in it || it.contains("No space left", ignoreCase = true) } == true
}

/** Disk or database full anywhere in the cause chain: ENOSPC or SQLite's "database or disk is full". */
fun isStorageFull(t: Throwable): Boolean =
    isNoSpace(t) || generateSequence(t) { it.cause }.take(8).any { it is SQLiteFullException }

/** The only text a storage-side failure may show: "Phone storage full", otherwise the generic preset. */
fun storageErrorFor(t: Throwable): UserFacingError = if (isStorageFull(t)) UserFacingErrors.StorageFull else UserFacingErrors.Unknown

/**
 * One handled chunk: [ack] to send (null = none), [ingestedAtMs] = phone capture time of a newly stored frame.
 * [held]: admission held a complete frame (no offset yet, or a stale one); the hub asks the glasses for a fresh hello.
 */
data class IngestResult(
    val ack: FrameAck?,
    val ingestedAtMs: Long? = null,
    val error: UserFacingError? = null,
    val held: Boolean = false,
)

/**
 * `pc_frame` chunk -> reassembly -> admission (dedupe, clock offset, pause filter) -> durable v2 receipt -> ledger -> queue
 * (spec §1.8, keyframe capture P2). The receipt is `rokid_<rxMs>_<id>.v2` with a `.jpg` and a `.meta.json` sidecar: both are
 * written temp -> fsync -> rename (sidecar first, JPEG last), then the inbox directory is fsynced ([ReceiptStore]). Ack ok=true
 * only after ALL of that succeeded, so a crash never loses a frame the glasses already deleted; any failure sends no ack and
 * the glasses keep the frame and resend. A resend is never acked merely because its id is known: both files must exist and
 * match, else the receipt is completed from the resend first. A receipt already committed to Room is acked at once.
 * Calls are serialized, so a resend arriving while the first copy is being written is not stored twice. Reassembly
 * staleness runs on [monotonicMs]; only admission uses the wall-clock `nowMs`.
 */
class FrameIngestor(
    private val inboxDir: File,
    private val reassembler: FrameReassembler,
    private val admission: FrameAdmission,
    private val ledger: ReceivedFrameLedger,
    private val writer: JpegWriter = AtomicJpegWriter,
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    private val wipeFence: () -> WipeFence.Fence = { WipeFence.fence(inboxDir.absoluteFile.parentFile) },
    private val wipeLock: Mutex = WipeFence.mutex,
    private val archiveDir: File = File(inboxDir.absoluteFile.parentFile, "rokid_archive"),
    private val dirSync: DirSync = DirSync.Default,
    /** True when this glasses frame id already has a `frame_meta` row (the receipt is committed to Room). */
    private val committed: suspend (wireFrameId: String) -> Boolean = { false },
    private val onStored: () -> Unit,
) {
    private val receipts = ReceiptStore(inboxDir, archiveDir, writer, dirSync)
    private val mutex = Mutex()

    /** [nowMs] is phone wall clock (admission); reassembly uses [monotonicMs]. */
    suspend fun onChunk(chunk: FrameChunk, offset: ClockOffset?, nowMs: Long): IngestResult = mutex.withLock {
        when (val a = reassembler.accept(chunk, monotonicMs())) {
            Assembly.Pending, Assembly.Ignored -> IngestResult(ack = null)
            is Assembly.Rejected -> IngestResult(FrameAck(frameId = a.frameId, ok = false, reason = a.reason))
            is Assembly.Complete -> admit(a, offset, nowMs)
        }
    }

    private suspend fun admit(c: Assembly.Complete, offset: ClockOffset?, nowMs: Long): IngestResult {
        val id = c.meta.frameId
        // Committed to Room: nothing left to make durable, ack at once.
        if (FrameCodec.isValidFrameId(id) && committed(id)) return IngestResult(FrameAck(frameId = id, ok = true, reason = FrameAckReason.DUPLICATE))
        return when (val d = admission.decide(c.meta, offset, nowMs)) {
            Admission.Hold -> IngestResult(ack = null, held = true)
            is Admission.Reject -> IngestResult(FrameAck(frameId = id, ok = false, reason = d.reason))
            // A known id is not enough (review fix 2): when a receipt is on disk it is verified or completed from this resend.
            is Admission.AckOnly ->
                if (d.reason == FrameAckReason.DUPLICATE && receipts.exists(id)) store(c.meta, c.jpeg, capturedAtMs = null, nowMs = nowMs)
                else IngestResult(FrameAck(frameId = id, ok = true, reason = d.reason))
            is Admission.Ingest -> store(c.meta, c.jpeg, d.capturedAtPhoneMs, nowMs)
        }
    }

    /**
     * Under [wipeLock], so a Delete all deleting frame files never races this write. A frame captured before the last
     * Delete all started is acked `ok=true` ([FrameAckReason.WIPED]) and recorded, never written (spec 6.1.1). While
     * the fence is unknown (it could not be recorded) nothing is stored and no ack is sent. [capturedAtMs] is null for a
     * ledger duplicate: the existing receipt's name already carries the time.
     */
    private suspend fun store(meta: FrameMeta, jpeg: ByteArray, capturedAtMs: Long?, nowMs: Long): IngestResult = wipeLock.withLock {
        val id = meta.frameId
        val fence = wipeFence()
        if (fence is WipeFence.Fence.Unknown) {
            // Fail closed (fix round 1 I3): no ack, so the glasses keep the frame and resend it once the fence is known.
            PcLog.w(TAG, "frame not stored: delete_all_fence_unknown")
            return@withLock IngestResult(ack = null, error = storageErrorFor(fence.cause ?: IOException("wipe fence unknown")))
        }
        val wipedAt = (fence as? WipeFence.Fence.At)?.ms
        val done = try {
            if (wipedAt != null && capturedAtMs != null && capturedAtMs < wipedAt) {
                ledger.add(id)
                PcLog.i(TAG, "frame dropped: captured_before_delete_all")
                return@withLock IngestResult(FrameAck(frameId = id, ok = true, reason = FrameAckReason.WIPED))
            }
            // Gaps G1: sealed while "Encrypt memory" is on; a seal failure is an IOException (no ack, the glasses resend).
            val disk = FrameImages.forDisk(jpeg)
            // The stem's time is the RECEIPT time (RokidFrameInbox's sidecar hold counts from it); the capture time is the file's
            // mtime, which RokidFrameInbox reads. A duplicate rewrite (null) keeps the mtime its receipt already has.
            val d = receipts.ensureDurable(id, nowMs, disk, receiptMeta(meta, disk.size.toLong()), capturedAtMs, includeArchive = capturedAtMs == null)
            ledger.add(id)
            d
        } catch (e: IOException) {
            PcLog.w(TAG, "frame not stored: ${e.javaClass.simpleName}")
            return@withLock IngestResult(ack = null, error = storageErrorFor(e))
        }
        if (done.outcome == ReceiptStore.Outcome.VERIFIED) {
            PcLog.i(TAG, "frame resend verified")
            return@withLock IngestResult(FrameAck(frameId = id, ok = true, reason = FrameAckReason.DUPLICATE))
        }
        // The frame is durable in the inbox and the ledger, so it is acked even if queueing fails: the next queue drain
        // scans the whole inbox and picks it up. A full disk here (queue file, WorkManager DB) must never crash the app.
        val queueError = try {
            onStored()
            null
        } catch (e: Exception) {
            PcLog.w(TAG, "frame stored but not queued: ${e.javaClass.simpleName}")
            storageErrorFor(e)
        }
        PcLog.i(TAG, "frame ingested")
        IngestResult(FrameAck(frameId = id, ok = true), ingestedAtMs = capturedAtMs ?: done.rxMs, error = queueError)
    }

    private fun receiptMeta(m: FrameMeta, size: Long) = ReceiptMeta(
        wireFrameId = m.frameId, capturedAtMs = m.capturedAtMs, motion = m.motion.name, dwell = m.dwell, blur = m.blur,
        dhash = m.dhash, phash = m.phash, luma = m.luma, batteryPct = m.batteryPct, width = m.width,
        sampleSeq = m.sampleSeq, sessionId = m.sessionId, size = size,
    )

    companion object { const val TAG = "ContextHub" }
}

/**
 * Production `onStored`: queue the inbox ingest exactly as the RFCOMM bridge did. While the phone pipeline is paused the
 * frame only waits in `rokid_inbox` (it is stored and acked); resuming queues the ingest (`resumePipeline`).
 */
fun enqueueInboxIngest(context: Context, config: AppConfigReader = AppConfigStore) {
    if (!config.isPipelineEnabled(context)) return
    QueueRuntimeStore.enqueue(
        context = context,
        queue = QueuePriority.FRAME_INGEST,
        payload = QueuePayloads.frameIngest(source = "rokid_cxr"),
        personaSnapshot = config.getWithoutSecret(context).persona,
        ttlHours = 24,
    )
    OrchestrationScheduler(context, config).enqueueQueueDrainNow()
}
