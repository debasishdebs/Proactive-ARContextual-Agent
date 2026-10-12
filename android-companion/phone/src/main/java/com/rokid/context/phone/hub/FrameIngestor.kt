package com.rokid.context.phone.hub

import android.content.Context
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.UserFacingError
import com.rokid.context.core.model.UserFacingErrors
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
}

object AtomicJpegWriter : JpegWriter {
    override fun write(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: throw IOException("no parent directory")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        // ".part" is not an inbox extension, so RokidFrameInbox never picks up a half-written file.
        val tmp = File(dir, "${target.name}.part")
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }
}

/** ENOSPC anywhere in the cause chain (Review Focus 2). */
fun isNoSpace(t: Throwable): Boolean = generateSequence(t) { it.cause }.take(8).any { c ->
    (c is ErrnoException && c.errno == OsConstants.ENOSPC) ||
        c.message?.let { "ENOSPC" in it || it.contains("No space left", ignoreCase = true) } == true
}

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
 * `pc_frame` chunk → reassembly → admission (dedupe, clock offset, pause filter) → atomic inbox write → ledger → queue
 * (spec §1.8). Ack ok=true only after the JPEG is durable and its id is in the ledger, so a crash never loses a frame
 * the glasses already deleted. A failed write sends no ack: the glasses keep the frame and retry on a later flush.
 * Calls are serialized, so a resend arriving while the first copy is being written sees the ledger entry and is not
 * stored twice. Reassembly staleness runs on [monotonicMs]; only admission uses the wall-clock `nowMs`.
 */
class FrameIngestor(
    private val inboxDir: File,
    private val reassembler: FrameReassembler,
    private val admission: FrameAdmission,
    private val ledger: ReceivedFrameLedger,
    private val writer: JpegWriter = AtomicJpegWriter,
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    private val onStored: () -> Unit,
) {
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
        return when (val d = admission.decide(c.meta, offset, nowMs)) {
            Admission.Hold -> IngestResult(ack = null, held = true)
            is Admission.Reject -> IngestResult(FrameAck(frameId = id, ok = false, reason = d.reason))
            is Admission.AckOnly -> IngestResult(FrameAck(frameId = id, ok = true, reason = d.reason))
            is Admission.Ingest -> store(id, c.jpeg, d.capturedAtPhoneMs)
        }
    }

    private fun store(id: String, jpeg: ByteArray, capturedAtMs: Long): IngestResult {
        val target = File(inboxDir, "rokid_${capturedAtMs}_$id.jpg")
        try {
            writer.write(target, jpeg)
            target.setLastModified(capturedAtMs) // RokidFrameInbox takes the capture time from lastModified
            ledger.add(id)
        } catch (e: IOException) {
            target.delete()
            Log.w(TAG, "frame $id not stored: ${e.javaClass.simpleName}")
            return IngestResult(ack = null, error = if (isNoSpace(e)) UserFacingErrors.StorageFull else UserFacingErrors.Unknown)
        }
        onStored()
        Log.i(TAG, "frame ingested $id")
        return IngestResult(FrameAck(frameId = id, ok = true), ingestedAtMs = capturedAtMs)
    }

    companion object { const val TAG = "ContextHub" }
}

/** Production `onStored`: queue the inbox ingest exactly as the RFCOMM bridge did. */
fun enqueueInboxIngest(context: Context) {
    QueueRuntimeStore.enqueue(
        context = context,
        queue = QueuePriority.FRAME_INGEST,
        payload = QueuePayloads.frameIngest(source = "rokid_cxr"),
        personaSnapshot = AppConfigStore.get(context).persona,
        ttlHours = 24,
    )
    OrchestrationScheduler(context).enqueueQueueDrainNow()
}
