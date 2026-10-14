package com.rokid.context.phone.hub

import com.rokid.context.data.frames.DirSync
import com.rokid.context.data.frames.ReceiptMeta
import com.rokid.context.data.frames.ReceiptNames
import com.rokid.context.data.frames.ReceiptScan
import java.io.File
import java.io.IOException

/**
 * Step 1-2 of the v2 receipt (spec P2 "Receipt durability"): the JPEG and its `.meta.json` sidecar become durable in the
 * inbox, in the order sidecar, then JPEG, then every directory holding them is synced. [ensureDurable] returns only when all
 * of that succeeded; any failure throws [IOException] (the caller sends no ack, the glasses resend).
 */
class ReceiptStore(
    private val inboxDir: File,
    private val archiveDir: File,
    private val writer: JpegWriter,
    private val dirSync: DirSync,
) {
    enum class Outcome {
        /** Both files were already there and matched the resend; only the directory sync was re-run. */
        VERIFIED,
        /** A missing or mismatched half (or the whole receipt) was written from the resend. */
        WRITTEN,
    }

    class Done(val outcome: Outcome, val rxMs: Long)

    /** True when any v2 receipt for [wireId] is on disk, whole or partial. */
    fun exists(wireId: String): Boolean = ReceiptScan.find(wireId, inboxDir, archiveDir) != null

    /**
     * Never trusts that the wire id is merely present: it acks as VERIFIED only if the JPEG and the sidecar both exist, the
     * sidecar parses and equals [expected] (which carries the JPEG size), and the re-run directory sync succeeds. Otherwise the
     * missing or mismatched file is rewritten from [disk] through the same temp, fsync, rename steps (sidecar first).
     */
    @Throws(IOException::class)
    fun ensureDurable(wireId: String, rxMs: Long, disk: ByteArray, expected: ReceiptMeta, stampMs: Long): Done {
        val found = ReceiptScan.find(wireId, inboxDir, archiveDir)
        val stem = found?.stem ?: ReceiptNames.stem(rxMs, wireId)
        val jpeg = found?.jpeg
        val sidecar = found?.sidecar
        val sidecarOk = sidecar != null && ReceiptMeta.read(sidecar) == expected
        val jpegOk = jpeg != null && jpeg.length() == disk.size.toLong()
        val touched = LinkedHashSet<File>()
        try {
            if (!sidecarOk) {
                // Next to the JPEG when there is one (same archive prefix), else where the old sidecar sat, else the inbox.
                val target = sidecar ?: jpeg?.let(ReceiptNames::sidecarBeside) ?: File(inboxDir, stem + ReceiptNames.SIDECAR)
                writer.writeStamped(target, expected.toJson(), stampMs)
                touched += target.parentFile!!
            }
            if (!jpegOk) {
                val target = jpeg ?: sidecar?.let(ReceiptNames::jpegBeside) ?: File(inboxDir, stem + ReceiptNames.JPG)
                writer.writeStamped(target, disk, stampMs)
                touched += target.parentFile!!
            }
        } catch (e: IOException) {
            cleanParts(stem)
            throw e
        }
        // Every directory that holds a final file of this receipt, written now or found there.
        val sinks = LinkedHashSet<File>().apply {
            addAll(touched)
            ReceiptScan.find(wireId, inboxDir, archiveDir)?.dirs?.let(::addAll)
        }
        for (dir in sinks) if (!dirSync.sync(dir)) { cleanParts(stem); throw IOException("directory sync failed") }
        return Done(if (sidecarOk && jpegOk) Outcome.VERIFIED else Outcome.WRITTEN, found?.rxMs ?: rxMs)
    }

    private fun cleanParts(stem: String) {
        for (dir in listOf(inboxDir, archiveDir)) dir.listFiles().orEmpty().forEach { f ->
            if (f.name.endsWith(".part") && f.name.contains(stem)) f.delete()
        }
    }
}
