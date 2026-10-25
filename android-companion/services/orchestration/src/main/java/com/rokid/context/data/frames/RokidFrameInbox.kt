package com.rokid.context.data.frames

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.memory.FrameRows
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.WipeFence
import com.rokid.context.orchestration.PipelineRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * One inbox file's ingest: [FAILED] (threw) goes back to the inbox for the next drain; a [DUPLICATE] stays archived.
 * [SYNC_FAILED]: a directory fsync failed before the commit, so nothing was committed and the files stay put; the next
 * run's reconciliation retries (spec §P2 receipt durability).
 */
internal enum class InboxResult { STORED, DUPLICATE, FAILED, SYNC_FAILED }

/** Ingest writes the frame row with its `frame_meta` and PENDING `frame_state` ([FrameRows]; null = the row only). */
typealias FrameAppender = suspend (Context, FrameEntity, FrameRows?) -> Boolean

object RokidFrameInbox {
    private const val TAG = "RokidFrameInbox"
    private val supportedExt = setOf("jpg", "jpeg", "png", "webp")
    private const val INBOX_DIR = "rokid_inbox"
    private const val ARCHIVE_DIR = "rokid_archive"

    /**
     * Every app-private folder (under filesDir) that holds frame JPEGs: the one list storage use and Delete all read.
     * Inbox first: an ingest moves inbox to archive, so a file moved during Delete all's walk is still seen in the archive.
     */
    val FRAME_DIRS: List<String> = listOf(INBOX_DIR, ARCHIVE_DIR)

    fun inboxDir(context: Context): File {
        return File(context.filesDir, INBOX_DIR)
    }

    fun archiveDir(context: Context): File {
        return File(context.filesDir, ARCHIVE_DIR)
    }

    /**
     * Deletes `.part` files (an atomic write the process died in) older than [minAgeMs]; a fresh one may be a write in
     * progress. Returns how many went.
     */
    fun sweepOrphanParts(context: Context, nowMs: Long = System.currentTimeMillis(), minAgeMs: Long = 60_000): Int =
        // The archive too: ReceiptStore and writeDurably temps (the sidecar rewrite) leak there after a crash.
        listOf(inboxDir(context), archiveDir(context)).sumOf { dir ->
            dir.listFiles().orEmpty().count { f ->
                f.isFile && f.name.endsWith(".part") && nowMs - f.lastModified() >= minAgeMs && f.delete()
            }
        }

    /**
     * One drain at a time, process-wide (review B C-1): `QueueDrainWorker` and `FrameIngestWorker` both drain, and two
     * interleaved drains could commit a pair under one another's feet and lose the photo.
     */
    private val drainLock = Mutex()

    /** [placeLabel]: a rough place for every frame of this run (Phase 7 C2; null unless Location is on and granted). */
    suspend fun ingestPending(
        context: Context,
        maxItems: Int = 8,
        placeLabel: String? = null,
        nowMs: () -> Long = System::currentTimeMillis,
        dirSync: DirSync = DirSync.Default,
        appendFrame: FrameAppender = PipelineRecords::appendFrame,
    ): Int = ingestPendingIds(context, maxItems, placeLabel, nowMs, dirSync, appendFrame).size

    /**
     * The ids of the frames this call appended (fix round 1: the summarizer job names exactly these). Every run starts with
     * [reconcile], so a receipt a crash left half-way is finished here (spec §P2 receipt durability, step 4).
     */
    suspend fun ingestPendingIds(
        context: Context,
        maxItems: Int = 8,
        placeLabel: String? = null,
        nowMs: () -> Long = System::currentTimeMillis,
        dirSync: DirSync = DirSync.Default,
        appendFrame: FrameAppender = PipelineRecords::appendFrame,
    ): List<String> = drainLock.withLock { drain(context, maxItems, placeLabel, nowMs, dirSync, appendFrame) }

    private suspend fun drain(
        context: Context,
        maxItems: Int,
        placeLabel: String?,
        nowMs: () -> Long,
        dirSync: DirSync,
        appendFrame: FrameAppender,
    ): List<String> {
        val swept = sweepOrphanParts(context, nowMs())
        if (swept > 0) PcLog.i(TAG, "swept orphan parts=$swept")
        val inbox = inboxDir(context)
        if (!inbox.exists()) inbox.mkdirs()
        val archive = archiveDir(context)
        if (!archive.exists()) archive.mkdirs()

        val imported = mutableListOf<String>()
        imported += reconcile(context, placeLabel, nowMs, appendFrame, dirSync)

        val candidates = inbox.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.filter { extOf(it.name) in supportedExt }
            // A v2 JPEG needs its parsed sidecar: without it the receipt is held (never defaulted) until reconcile gives up.
            ?.filter { !ReceiptNames.isV2Jpeg(it) || ReceiptMeta.read(ReceiptNames.sidecarBeside(it)) != null }
            ?.sortedBy { it.lastModified() }
            ?.take(maxItems.coerceAtLeast(1))
            ?.toList()
            .orEmpty()
        PcLog.i(TAG, "ingestPending files=${candidates.size}")

        for (file in candidates) {
            // Review Focus 3: a Delete all that starts while this file is ingested bumps the generation; its row and file go.
            val generation = MemoryWipe.generation.get()
            val stamp = nowMs()
            val archived = File(archive, "${stamp}_${file.name}")
            val sidecar = ReceiptNames.sidecarBeside(file).takeIf { ReceiptNames.isV2Jpeg(file) && it.isFile }
            val archivedSidecar = sidecar?.let { File(archive, "${stamp}_${it.name}") }
            // Under the fence lock (Gaps G1): an encryption swap of this inbox file never lands after the move.
            // Sidecar first, JPEG last: a death between the two renames leaves a split pair that reconcile reunites.
            val moved = WipeFence.mutex.withLock { runCatching { moveReceipt(sidecar, archivedSidecar, file, archived) }.getOrDefault(false) }
            val sourceFile = if (moved) archived else file
            val sourceSidecar = if (moved) archivedSidecar else sidecar
            val result = try {
                commitArchived(context, sourceFile, sourceSidecar, "rokid_glasses", placeLabel, nowMs, appendFrame, dirSync)
            } catch (e: CancellationException) {
                // Fix round 1/2 I4: a drain cancelled mid-insert keeps the file eligible unless its row landed. If a Delete
                // all started meanwhile, the file (and any row) goes instead: it must not come back after the wipe.
                withContext(NonCancellable) { afterCancelledIngest(context, generation, moved, sourceFile, sourceSidecar, file, sidecar) }
                throw e
            }
            when (result) {
                InboxResult.STORED -> if (MemoryWipe.generation.get() != generation) {
                    dropRows(context, listOf(frameIdOf(sourceFile)))
                    sourceFile.delete(); sourceSidecar?.delete()
                    PcLog.i(TAG, "frame dropped: Delete all ran during its ingest")
                } else {
                    imported += frameIdOf(sourceFile)
                }
                InboxResult.DUPLICATE -> Unit
                InboxResult.SYNC_FAILED -> if (MemoryWipe.generation.get() != generation) {
                    sourceFile.delete(); sourceSidecar?.delete()
                } // else: stays archived; the next run's reconciliation retries the syncs and the commit
                // Fix round 3: same rule as a cancelled ingest. A Delete all since it began deletes the file, else it goes back.
                InboxResult.FAILED -> if (MemoryWipe.generation.get() != generation) {
                    sourceFile.delete(); sourceSidecar?.delete()
                    PcLog.i(TAG, "failed frame dropped: Delete all ran during its ingest")
                } else if (moved && !WipeFence.mutex.withLock { returnToInbox(archived, file, archivedSidecar, sidecar) }) {
                    PcLog.w(TAG, "failed ingest not returned to the inbox")
                }
            }
        }
        return imported
    }

    /** Sidecar then JPEG; if the JPEG cannot move, the sidecar goes back. */
    private fun moveReceipt(sidecar: File?, archivedSidecar: File?, jpeg: File, archivedJpeg: File): Boolean {
        if (sidecar != null && archivedSidecar != null && !sidecar.renameTo(archivedSidecar)) return false
        if (jpeg.renameTo(archivedJpeg)) return true
        if (sidecar != null && archivedSidecar != null) archivedSidecar.renameTo(sidecar)
        return false
    }

    private fun returnToInbox(archived: File, file: File, archivedSidecar: File?, sidecar: File?): Boolean {
        if (archivedSidecar != null && sidecar != null) archivedSidecar.renameTo(sidecar)
        return archived.renameTo(file)
    }

    /** A frame row, its meta and its state all go together (the wipe and cancel paths). */
    private suspend fun dropRows(context: Context, ids: List<String>) {
        val g = MemoryGraph.get(context)
        g.frames.deleteByIds(ids)
        g.db.frameMetaDao().deleteFor(ids)
        g.db.frameStateDao().deleteFor(ids)
    }

    private suspend fun afterCancelledIngest(
        context: Context, generation: Long, moved: Boolean, sourceFile: File, sourceSidecar: File?, original: File, originalSidecar: File?,
    ) {
        val id = frameIdOf(sourceFile)
        if (MemoryWipe.generation.get() != generation) {
            try { dropRows(context, listOf(id)) } catch (e: Exception) { PcLog.w(TAG, "row not removed: ${e.javaClass.simpleName}") }
            sourceFile.delete(); sourceSidecar?.delete()
            return
        }
        if (!moved) return
        // A throwing check keeps the file (worst case: one duplicate ingest), never loses it.
        val hasRow = try { MemoryGraph.get(context).frames.byIds(listOf(id)).isNotEmpty() } catch (e: Exception) { false }
        if (!hasRow) WipeFence.mutex.withLock { returnToInbox(sourceFile, original, sourceSidecar, originalSidecar) }
    }

    /** The appended frame's id, or null when nothing was appended. */
    suspend fun ingestSinglePath(
        context: Context,
        absolutePath: String,
        source: String = "rokid_glasses",
        placeLabel: String? = null,
        appendFrame: FrameAppender = PipelineRecords::appendFrame,
    ): String? {
        val file = File(absolutePath)
        if (!file.exists() || !file.isFile) return null
        if (extOf(file.name) !in supportedExt) return null
        return drainLock.withLock { ingestSingleLocked(context, file, source, placeLabel, appendFrame) }
    }

    private suspend fun ingestSingleLocked(context: Context, file: File, source: String, placeLabel: String?, appendFrame: FrameAppender): String? {
        // P7xP8 I3: same rule as the inbox drain. A Delete all that ran during this ingest takes its row and file.
        val generation = MemoryWipe.generation.get()
        val id = frameIdOf(file)
        if (ingestFile(context, file, source, placeLabel, appendFrame) != InboxResult.STORED) return null
        if (MemoryWipe.generation.get() != generation) {
            withContext(NonCancellable) {
                try { dropRows(context, listOf(id)) } catch (e: Exception) { PcLog.w(TAG, "row not removed: ${e.javaClass.simpleName}") }
                file.delete()
            }
            PcLog.i(TAG, "frame dropped: Delete all ran during its ingest")
            return null
        }
        return id
    }

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    // Full archived file name (<archiveMs>_rokid_<rxMs>_<id>): unique per file. Never truncate, ids only dedupe via insertIgnore.
    internal fun frameIdOf(file: File): String = file.nameWithoutExtension.ifBlank { "rokid_frame" }

    /**
     * Step 3 of the receipt (spec P2): [jpeg] (and its [sidecar]) are already in the archive. Fsync the archive AND the
     * inbox directory so a power loss cannot bring the inbox entries back, then ONE Room transaction (frame row,
     * frame_meta from the sidecar, PENDING frame_state). Only after the commit is the sidecar deleted, and the archive
     * directory synced again. A failed sync stops before the commit.
     */
    internal suspend fun commitArchived(
        context: Context,
        jpeg: File,
        sidecar: File?,
        source: String,
        placeLabel: String?,
        nowMs: () -> Long,
        appendFrame: FrameAppender,
        dirSync: DirSync,
    ): InboxResult {
        val archive = archiveDir(context)
        if (!dirSync.sync(archive) || !dirSync.sync(inboxDir(context))) {
            PcLog.w(TAG, "directory sync failed; commit deferred")
            return InboxResult.SYNC_FAILED
        }
        // A legacy receipt (no .v2 marker) has no sidecar: UNKNOWN/false defaults. A v2 one is only ever committed with its meta.
        val meta = if (ReceiptNames.isV2Jpeg(jpeg)) {
            sidecar?.let(ReceiptMeta::read) ?: return if (hasRow(context, frameIdOf(jpeg))) InboxResult.DUPLICATE else InboxResult.FAILED
        } else null
        val result = ingestFile(context, jpeg, source, placeLabel, appendFrame, meta, nowMs)
        if (result != InboxResult.FAILED && sidecar != null) {
            sidecar.delete()
            if (!dirSync.sync(archive)) PcLog.w(TAG, "archive directory not synced after the commit")
        }
        return result
    }

    internal suspend fun ingestFile(
        context: Context,
        file: File,
        source: String,
        placeLabel: String? = null,
        appendFrame: FrameAppender = PipelineRecords::appendFrame,
        receipt: ReceiptMeta? = null,
        nowMs: () -> Long = System::currentTimeMillis,
        sceneActive: suspend () -> Boolean = { com.rokid.context.work.frames.KeyframeGraph.sceneActive(context) },
    ): InboxResult {
        val frameId = frameIdOf(file)
        val payload = "rokid_file=${file.name} bytes=${file.length()}"
        PcLog.i(TAG, "ingestFile exists=${file.exists()} size=${file.length()}")
        val now = nowMs()
        val routeFirst = try { sceneActive() } catch (c: CancellationException) { throw c } catch (_: Exception) { false }
        val rows = FrameRows(
            // routedAt stays NULL only when the scene indexer is active (an IMAGE-capable embedder is bound, vectors available, Ruling 2):
            // the router, not the per-photo path, sees the frame first. Otherwise routedAt = now: wave-1 behaviour (claimable at once).
            FrameMetaEntity(
                frameId = frameId, wireFrameId = receipt?.wireFrameId, sessionId = receipt?.sessionId,
                motion = receipt?.motion ?: "UNKNOWN", dwell = receipt?.dwell ?: false, blur = receipt?.blur,
                dhash = receipt?.dhash, phash = receipt?.phash, width = receipt?.width, salience = null, salienceTerms = null,
                clusterId = null, nearDupOf = null, imageVectorSpace = null, createdAt = now, routedAt = if (routeFirst) null else now,
                // The glasses' capture time on the phone clock: a receipt's file mtime is set from the wire capturedAtMs (offset-adjusted) by
                // ReceiptStore. Without a receipt the mtime is not a capture time, so it stays unknown.
                capturedAtMs = if (receipt != null) file.lastModified().takeIf { it in 1..now } else null,   // a time after the receive time is a clock error
            ),
            FrameStateEntity(frameId = frameId, state = "PENDING", attempts = 0, lastError = null, claimedAt = null, claimId = null, updatedAt = now),
        )
        val appended = try {
            appendFrame(
                context,
                FrameEntity(
                    id = frameId,
                    ts = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                    path = file.absolutePath,
                    source = source,
                    payload = payload,
                    placeLabel = placeLabel,
                ),
                rows,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PcLog.w(TAG, "ingest failed: ${e.javaClass.simpleName}")
            return InboxResult.FAILED
        }
        PcLog.i(TAG, "append result=$appended")
        if (appended) PipelineStatsStore.incFrameCaptured(context)
        else if (receipt != null) dropRedundantCopy(context, file, receipt.wireFrameId, frameId)
        // appendFrame's false is a duplicate id or a file that vanished: neither is worth a retry.
        return if (appended) InboxResult.STORED else InboxResult.DUPLICATE
    }

    /** A sidecar that is gone with a row present means another path already committed this receipt. */
    private suspend fun hasRow(context: Context, id: String): Boolean = try {
        MemoryGraph.get(context).frames.byIds(listOf(id)).isNotEmpty()
    } catch (e: Exception) { false }

    /**
     * The same glasses frame is already stored under another database id. The new copy is redundant only while that row's
     * photo still exists; if its file is gone (review B C-1) the row is repointed at this copy instead, so no photo is lost.
     */
    private suspend fun dropRedundantCopy(context: Context, file: File, wireId: String, frameId: String) {
        try {
            val g = MemoryGraph.get(context)
            val other = g.db.frameMetaDao().byWireId(wireId)?.takeIf { it.frameId != frameId } ?: return
            val row = g.frames.byIds(listOf(other.frameId)).firstOrNull()
            when {
                row == null -> Unit // meta without a frame row: nothing to keep this copy for
                row.path == file.absolutePath -> return // the row already points at this very file
                row.path.isNotBlank() && File(row.path).exists() -> {
                    file.delete()
                    PcLog.i(TAG, "redundant receipt dropped (wire id already stored)")
                    return
                }
                else -> {
                    g.db.frameDao().updatePath(row.id, file.absolutePath)
                    PcLog.i(TAG, "stored frame repointed at the surviving copy")
                    return
                }
            }
            file.delete()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            PcLog.w(TAG, "redundant copy kept: ${e.javaClass.simpleName}") // a throwing check keeps the file
        }
    }

    // --- reconciliation ------------------------------------------------------------------------------------------

    /** A `.v2` JPEG whose sidecar stays lost this long is ingested with defaults (count-only Diagnostics entry). */
    internal const val SIDECAR_LOST_AFTER_MS = 24 * 3_600_000L

    /** Archived JPEGs are re-checked for a lost sidecar only this long after receipt (the 24 h hold plus an hour of drains). */
    internal const val SIDECAR_SCAN_WINDOW_MS = SIDECAR_LOST_AFTER_MS + 3_600_000L

    /**
     * Startup and every drain run (spec P2 receipt durability, step 4). Scans the inbox AND the archive for v2 receipts with
     * no frame row:
     * 1. a split pair (sidecar in one directory, JPEG in the other: a death between the two renames) is reunited in the
     *    archive and both directories are synced before anything is ingested;
     * 2. a `.v2` JPEG whose sidecar is nowhere is HELD, never defaulted; after [SIDECAR_LOST_AFTER_MS] it gets a defaults
     *    sidecar and `v2_sidecar_lost` is counted;
     * 3. an archived JPEG + sidecar with no frame row (a death right after the archive rename) is committed.
     * Returns the ids it committed. Unmarked legacy JPEGs are the plain drain's: they get defaults at once.
     */
    internal suspend fun reconcile(
        context: Context,
        placeLabel: String?,
        nowMs: () -> Long,
        appendFrame: FrameAppender,
        dirSync: DirSync,
    ): List<String> {
        val inbox = inboxDir(context)
        val archive = archiveDir(context)
        if (!inbox.isDirectory || !archive.isDirectory) return emptyList()
        val g = MemoryGraph.get(context)

        for (r in ReceiptScan.scan(inbox, archive).values) reunite(r, inbox, archive, dirSync)

        // Lost sidecars. An archived JPEG without a sidecar is normally a committed frame (its sidecar goes after the commit),
        // so only the inbox and archived JPEGs received within [SIDECAR_SCAN_WINDOW_MS] are looked at (review B I-2): a v2
        // JPEG older than that was either committed or defaulted long ago, and checking the whole archive on every drain is
        // O(archive) I/O per stored frame.
        val scanned = ReceiptScan.scan(inbox, archive).values
        val bare = scanned.filter { r ->
            r.jpeg != null && (r.sidecar == null || ReceiptMeta.read(r.sidecar) == null) &&
                (r.jpeg.parentFile != archive || nowMs() - r.rxMs < SIDECAR_SCAN_WINDOW_MS)
        }
        val archivedIds = bare.filter { it.jpeg!!.parentFile == archive }.map { frameIdOf(it.jpeg!!) }
        val withRow = archivedIds.chunked(MAX_IN).flatMap { g.frames.byIds(it) }.map { it.id }.toSet()
        val tombs = archivedIds.chunked(MAX_IN).flatMap { g.db.forgetDao().tombstoned(it) }.toSet()
        var wroteSidecar = false
        for (r in bare) {
            val jpeg = r.jpeg!!
            val id = frameIdOf(jpeg)
            if (id in withRow || id in tombs) continue
            // Held 24 h from RECEIPT (the stem's rx time), not from the file's mtime, which is the capture time (review B M-2).
            if (nowMs() - r.rxMs < SIDECAR_LOST_AFTER_MS) continue // held, not defaulted
            try {
                writeDurably(ReceiptNames.sidecarBeside(jpeg), ReceiptMeta(wireFrameId = r.wireId, capturedAtMs = jpeg.lastModified().takeIf { it > 0 } ?: r.rxMs, size = jpeg.length()).toJson())
                if (!dirSync.sync(jpeg.parentFile!!)) { PcLog.w(TAG, "defaults sidecar not synced"); continue }
                wroteSidecar = true
                PipelineStatsStore.incSidecarLost(context)
                PcLog.i(TAG, "${PipelineStatsStore.K_V2_SIDECAR_LOST} count=1")
            } catch (e: java.io.IOException) {
                PcLog.w(TAG, "defaults sidecar not written: ${e.javaClass.simpleName}")
            }
        }

        // Archived receipts with no row.
        val committed = mutableListOf<String>()
        val inArchive = (if (wroteSidecar) ReceiptScan.scan(inbox, archive).values else scanned).filter { r ->
            r.jpeg?.parentFile == archive && r.sidecar?.parentFile == archive && ReceiptMeta.read(r.sidecar) != null
        }
        val ids = inArchive.map { frameIdOf(it.jpeg!!) }
        val rowIds = ids.chunked(MAX_IN).flatMap { g.frames.byIds(it) }.map { it.id }.toSet()
        val tombstoned = ids.chunked(MAX_IN).flatMap { g.db.forgetDao().tombstoned(it) }.toSet()
        for (r in inArchive) {
            val jpeg = r.jpeg!!
            val sidecar = r.sidecar!!
            val id = frameIdOf(jpeg)
            if (id in tombstoned) { // forgotten: the sidecar (hashes, wire id) must not outlive the photo, even after a crash between commit and cleanup
                sidecar.delete()
                continue
            }
            if (id in rowIds) { // committed; only the post-commit cleanup died
                sidecar.delete()
                if (!dirSync.sync(archive)) PcLog.w(TAG, "archive directory not synced after the cleanup")
                continue
            }
            val generation = MemoryWipe.generation.get()
            val result = commitArchived(context, jpeg, sidecar, "rokid_glasses", placeLabel, nowMs, appendFrame, dirSync)
            if (result == InboxResult.STORED) {
                if (MemoryWipe.generation.get() != generation) {
                    dropRows(context, listOf(id)); jpeg.delete(); sidecar.delete()
                } else committed += id
            }
        }
        if (committed.isNotEmpty()) PcLog.i(TAG, "reconciled receipts=${committed.size}")
        return committed
    }

    /** Moves the missing half of a split pair next to the other one (into the archive) and syncs both directories. */
    private suspend fun reunite(r: Receipt, inbox: File, archive: File, dirSync: DirSync) {
        val jpeg = r.jpeg ?: return
        val sidecar = r.sidecar ?: return
        if (jpeg.parentFile == sidecar.parentFile) return
        val ok = WipeFence.mutex.withLock {
            if (jpeg.parentFile == inbox) jpeg.renameTo(File(archive, sidecar.name.removeSuffix(ReceiptNames.SIDECAR) + ReceiptNames.JPG))
            else sidecar.renameTo(File(archive, jpeg.name.removeSuffix(ReceiptNames.JPG) + ReceiptNames.SIDECAR))
        }
        if (!ok) { PcLog.w(TAG, "split receipt not reunited"); return }
        if (!dirSync.sync(archive) || !dirSync.sync(inbox)) PcLog.w(TAG, "split receipt reunited but directories not synced")
    }

    private const val MAX_IN = 500
}
