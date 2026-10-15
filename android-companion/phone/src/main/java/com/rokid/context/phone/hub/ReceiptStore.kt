package com.rokid.context.phone.hub

import com.rokid.context.data.frames.DirSync
import com.rokid.context.data.frames.Receipt
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
    fun exists(wireId: String): Boolean = find(wireId) != null

    /**
     * Final review I4: the inbox is listed (it only holds the undrained backlog), but the archive, which only grows, is listed
     * once and indexed by wire id; the index is reused while the directory's mtime is unchanged. An archive that changed within
     * [MTIME_SETTLE_MS] of the listing is not trusted yet (a coarse mtime could hide a second change), and a hit whose files have
     * since disappeared is dropped and rescanned. Per-frame cost therefore no longer grows with the archive.
     */
    private var archiveIndex: ArchiveIndex? = null

    private class ArchiveIndex(val dirMtime: Long, val takenAtMs: Long, val byWire: Map<String, List<Receipt>>)

    private fun archiveReceipts(wireId: String, rescan: Boolean = false): List<Receipt> {
        val mtime = archiveDir.lastModified()
        var idx = archiveIndex
        if (rescan || idx == null || idx.dirMtime != mtime || idx.takenAtMs - idx.dirMtime < MTIME_SETTLE_MS) {
            idx = ArchiveIndex(mtime, System.currentTimeMillis(), ReceiptScan.scan(archiveDir).values.groupBy { it.wireId })
            archiveIndex = idx
        }
        return idx.byWire[wireId].orEmpty()
    }

    /** One receipt for [wireId] (halves found in the inbox and the archive under one stem are merged; the archive's file wins). */
    private fun find(wireId: String, includeArchive: Boolean = true): Receipt? {
        var archived = if (includeArchive) archiveReceipts(wireId) else emptyList()
        if (archived.any { (it.jpeg?.exists() == false) || (it.sidecar?.exists() == false) }) archived = archiveReceipts(wireId, rescan = true)
        val inbox = ReceiptScan.scan(inboxDir).values.filter { it.wireId == wireId }
        val all = archived + inbox
        val first = all.firstOrNull() ?: return null
        val same = all.filter { it.stem == first.stem }
        return Receipt(first.stem, first.rxMs, first.wireId, same.firstNotNullOfOrNull { it.jpeg }, same.firstNotNullOfOrNull { it.sidecar })
    }

    /**
     * [includeArchive] false (a frame the ledger has never seen): only the inbox is looked at, so the archive, which only grows and
     * changes with every committed frame, is never listed on the arrival path. A receipt that crashed into the archive before its
     * ledger entry is then written again in the inbox; the commit's wire-id check (FrameIntake) keeps one row.
     *
     * Never trusts that the wire id is merely present: it acks as VERIFIED only if the JPEG and the sidecar both exist, the
     * sidecar parses and equals [expected] (which carries the JPEG size), and the re-run directory sync succeeds. Otherwise the
     * missing or mismatched file is rewritten from [disk] through the same temp, fsync, rename steps (sidecar first).
     */
    @Throws(IOException::class)
    fun ensureDurable(wireId: String, rxMs: Long, disk: ByteArray, expected: ReceiptMeta, stampMs: Long?, includeArchive: Boolean = true): Done {
        val found = find(wireId, includeArchive)
        val stem = found?.stem ?: ReceiptNames.stem(rxMs, wireId)
        val jpeg = found?.jpeg
        val sidecar = found?.sidecar
        val sidecarOk = sidecar != null && ReceiptMeta.read(sidecar) == expected
        val jpegOk = jpeg != null && jpeg.length() == disk.size.toLong()
        val touched = LinkedHashSet<File>()
        // A rewrite from a resend keeps the time the receipt already has (its other half's mtime = the capture time RokidFrameInbox reads), else its receipt time.
        val stamp = stampMs ?: (jpeg?.lastModified()?.takeIf { it > 0 } ?: sidecar?.lastModified()?.takeIf { it > 0 } ?: found?.rxMs ?: rxMs)
        try {
            if (!sidecarOk) {
                // Next to the JPEG when there is one (same archive prefix), else where the old sidecar sat, else the inbox.
                val target = sidecar ?: jpeg?.let(ReceiptNames::sidecarBeside) ?: File(inboxDir, stem + ReceiptNames.SIDECAR)
                writer.writeStamped(target, expected.toJson(), stamp)
                touched += target.parentFile!!
            }
            if (!jpegOk) {
                val target = jpeg ?: sidecar?.let(ReceiptNames::jpegBeside) ?: File(inboxDir, stem + ReceiptNames.JPG)
                writer.writeStamped(target, disk, stamp)
                touched += target.parentFile!!
            }
        } catch (e: IOException) {
            cleanParts(stem)
            throw e
        }
        // Every directory that holds a final file of this receipt, written now or found there.
        val sinks = LinkedHashSet<File>().apply {
            addAll(touched)
            found?.dirs?.let(::addAll) // no second listing: what was written now is in [touched]
        }
        for (dir in sinks) if (!dirSync.sync(dir)) { cleanParts(stem); throw IOException("directory sync failed") }
        return Done(if (sidecarOk && jpegOk) Outcome.VERIFIED else Outcome.WRITTEN, found?.rxMs ?: rxMs)
    }

    private fun cleanParts(stem: String) {
        for (dir in listOf(inboxDir, archiveDir)) dir.listFiles().orEmpty().forEach { f ->
            if (f.name.endsWith(".part") && f.name.contains(stem)) f.delete()
        }
    }

    private companion object { const val MTIME_SETTLE_MS = 2_000L }
}
