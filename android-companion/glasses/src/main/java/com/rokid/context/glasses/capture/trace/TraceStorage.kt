package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.ManifestChunk
import com.rokid.context.capture.trace.TraceBounds
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.core.model.TraceChunk
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The recorder's files. Layout (the frozen [TraceFiles] layout, one directory per trace):
 * - `imu_trace_tmp/<traceId>/chunk-NNNN.json`: a trace being written. A chunk file is written under a `.part` name and renamed, so a crash leaves
 *   no half file that looks complete; the whole directory is invisible to import until [publish].
 * - `imu_trace_tmp/<traceId>/start.json`: the manifest skeleton written with the first chunk (never in `traces/`): what a restart needs to SEAL a killed trace as PARTIAL.
 * - `traces/<traceId>/`: a closed trace (`manifest.json` last, then the directory is renamed here atomically). Only these are importable.
 * - `trace_epoch`: the last deletion epoch seen (two lines: installId, counter).
 *
 * Every method is called from the single writer thread (the recorder's io thread, which also runs [recover]), except [readEpoch] (construction: one tiny file). [used] counts the bytes of both directories.
 */
internal class TraceStorage(
    private val root: File, private val diskCapBytes: Long = Long.MAX_VALUE,
    /** Test seam: called at the named points of [publish] (`stamp:<i>` after chunk i was stamped, `manifest`, `beforeRename`); a test throws from it to simulate the process dying there. */
    private val checkpoint: (String) -> Unit = {},
    /** Test seams for an I/O error: the directory rename of a publication, and the recursive delete of a wipe. */
    private val renameDir: (File, File) -> Boolean = { a, b -> a.renameTo(b) },
    private val deleteTree: (File) -> Boolean = { it.deleteRecursively() },
    /** Test seams for a read and a write of a record file (the production ones are plain UTF-8 file I/O). */
    private val readFile: (File) -> String = { it.readText(Charsets.UTF_8) },
    private val writeFile: (File, String) -> Unit = { f, t -> f.writeText(t, Charsets.UTF_8) },
    /** LGC8-min: the received-cleanup record (`files/trace_cleanup_pending`; production passes `filesDir`, default next to the epoch) and its atomic writer (temp file, fsync, rename; false on failure). */
    private val pendingFile: File = File(root, "trace_cleanup_pending"),
    private val writeRecord: (File, String) -> Boolean = com.rokid.context.glasses.log.DiagStateFile::atomicWrite,
    /** Test seam: removes one marker file (true when it is gone). */
    private val deleteMarker: (File) -> Boolean = { it.delete() || !it.exists() },
) {
    val tracesDir = File(root, "traces")
    val tmpDir = File(root, "imu_trace_tmp")
    private val epochFile = File(root, "trace_epoch")
    /** Transfer markers (LGC9, plan 3.8): `<root>/transferred/<traceId>`, zero bytes, in their own directory: never inside `traces/<id>/` (the frozen layout) and never listed by the pull. */
    val transferredDir = File(root, "transferred")
    val used = AtomicLong(0)

    /**
     * Start-up: an unfinished trace (the process died: killed on a call, a crash) is SEALED as a PARTIAL trace and moved to `traces/` ([seal]) instead of deleted, when it
     * belongs to the persisted deletion epoch and fits the disk cap; anything else in tmp (another epoch, no start record, no readable chunk, over the cap) is deleted.
     * The used size is recounted.
     */
    fun recover(): RecoveryCounts {
        val sealedBefore = tracesDir.list()?.size ?: 0
        val found = tmpDir.listFiles()?.count { it.isDirectory } ?: 0
        used.set(sizeOf(tracesDir))
        // LGC8-min: while a received cleanup is still pending (its replay failed again) nothing in tmp is sealed or deleted here: a killed trace the cleanup targets must not
        // become an importable trace. The next start replays the record first and recovers afterwards.
        if (cleanupPending()) { pruneMarkers(); used.set(sizeOf(tracesDir) + sizeOf(tmpDir)); return RecoveryCounts(found, 0) }
        val epoch = readEpoch()
        for (dir in tmpDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            // An I/O error (a rename or a write that failed) says nothing about the trace: a directory that can still be finished stays in tmp for the next recovery.
            // Only a directory that was REJECTED (wrong epoch, no start record, no readable chunk, over the cap, a format error) is deleted.
            val retry = try { seal(dir, epoch); false } catch (e: Retryable) { true } catch (e: Exception) { false }
            if (!retry) deleteTree(dir)
        }
        tmpDir.listFiles()?.filter { !it.isDirectory }?.forEach { it.delete() }
        pruneMarkers()
        used.set(sizeOf(tracesDir) + sizeOf(tmpDir))
        return RecoveryCounts(found, ((tracesDir.list()?.size ?: 0) - sealedBefore).coerceAtLeast(0))
    }

    /** A publication that failed on I/O (a rename, a write): the directory can still be finished by a later recovery, so it is kept, not deleted. */
    private class Retryable(cause: Throwable) : Exception(cause)

    /** The text of a record file that EXISTS: a read I/O error is [Retryable] (it says nothing about the record), never a rejection. A missing file is the caller's check. */
    private fun readRecord(f: File): String = try { readFile(f) } catch (e: java.io.IOException) { throw Retryable(e) }

    /** Counts only (for the diagnostic log): unfinished traces found in tmp, and how many of them were sealed. */
    class RecoveryCounts(val found: Int, val sealed: Int)

    /** Writes the start record of a trace (the manifest skeleton: everything the end cannot tell). It stays in tmp only; [publish] and [seal] remove it. */
    fun writeStart(id: String, m: Manifest) {
        val dir = jobDir(id)
        dir.mkdirs()
        val part = File(dir, START_FILE + ".part")
        part.writeText(TraceFiles.encodeFile(ManifestChunk.encode(m)), Charsets.UTF_8)
        if (!part.renameTo(File(dir, START_FILE))) throw java.io.IOException("rename")
    }

    /**
     * A directory the process left after the manifest was written but before the rename (a second death during [publish]): the chunks are stamped and the manifest is
     * complete, so only the rename is missing. Finishes it. Returns true when the directory was handled here (published, or not of this epoch / over the cap and left for
     * the delete), false when it has no valid manifest (then it is sealed from its start record).
     */
    private fun finishPublication(dir: File, epoch: Pair<String, Long>): Boolean {
        val mf = File(dir, TraceFiles.MANIFEST_FILE)
        if (!mf.isFile) return false
        val text = readRecord(mf) // an I/O error here keeps the directory
        val m = try { ManifestChunk.decode(TraceFiles.decodeFile(text).getOrThrow()).getOrThrow() } catch (e: Exception) { return false }
        if (m.traceId != dir.name) return false
        if (m.installId != epoch.first || m.deletionCounter != epoch.second) return true
        for (i in 0 until m.chunkCount) if (!File(dir, TraceFiles.chunkFile(i)).isFile) return false
        for (extra in dir.listFiles() ?: emptyArray()) {
            val i = CHUNK_NAME.matchEntire(extra.name)?.groupValues?.get(1)?.toInt()
            if (extra.name != TraceFiles.MANIFEST_FILE && (i == null || i >= m.chunkCount)) extra.delete()
        }
        if (sizeOf(tracesDir) + sizeOf(dir) > diskCapBytes) return true
        tracesDir.mkdirs()
        if (!renameDir(dir, File(tracesDir, TraceFiles.dirName(m.traceId)))) throw Retryable(java.io.IOException("rename"))
        return true
    }

    private fun seal(dir: File, epoch: Pair<String, Long>) {
        if (finishPublication(dir, epoch)) return
        val sf = File(dir, START_FILE)
        if (!sf.isFile) throw IllegalStateException("no start record") // genuinely missing: rejected
        val m0 = ManifestChunk.decode(TraceFiles.decodeFile(readRecord(sf)).getOrThrow()).getOrThrow()
        if (m0.traceId != dir.name || m0.installId != epoch.first || m0.deletionCounter != epoch.second) return
        val shas = ArrayList<String>(); var events = 0L; var raw = 0L; var lastT = m0.startElapsedMs
        while (shas.size < com.rokid.context.capture.trace.TraceBounds.MAX_CHUNKS) {
            val f = File(dir, TraceFiles.chunkFile(shas.size))
            if (!f.isFile) break
            // the chunk count in the file is still the placeholder (publish stamps it), so the decode is by parts
            // a streaming scan (checksum, header, line count, ordering), not a decode of every event: this runs on a slow device over hundreds of chunks (the ANR of 2026-10-23)
            val text = readRecord(f) // a read error must not look like a torn tail: nothing is truncated or deleted on it
            val c = try { TraceFiles.decodeFile(text).getOrThrow().let { tc -> ChunkCodec.scan(tc.traceId, tc.seq, tc.sha256, tc.b64).getOrThrow() } } catch (e: Exception) { break }
            if (c.traceId != m0.traceId || c.seq != shas.size || c.eventCount < 1 || c.firstTMs < lastT) break
            shas += c.sha256; events += c.eventCount; raw += c.rawBytes; lastT = c.lastTMs
        }
        if (shas.isEmpty()) return
        for (extra in dir.listFiles() ?: emptyArray()) {
            val i = CHUNK_NAME.matchEntire(extra.name)?.groupValues?.get(1)?.toInt()
            if (extra.name != START_FILE && (i == null || i >= shas.size)) extra.delete()
        }
        val dt = lastT - m0.startElapsedMs
        val phone = m0.startPhoneWallMs != null
        val m = m0.copy(
            endElapsedMs = lastT, endWallMs = m0.startWallMs + dt,
            endPhoneWallMs = if (phone) m0.startPhoneWallMs else null, endPhoneAgeMs = if (phone) (m0.startPhoneAgeMs ?: 0) + dt else null,
            chunkCount = shas.size, eventCount = events, rawBytes = raw, traceSha256 = Manifest.traceSha(shas),
            recorded = TraceStatus.PARTIAL, closeReason = CloseReason.STOP,
        )
        if (sizeOf(tracesDir) + sizeOf(dir) + MANIFEST_ALLOWANCE > diskCapBytes) return
        try { publish(m.traceId, m) } catch (e: java.io.IOException) { throw Retryable(e) } // keeps the start record until the manifest exists (a death here is recovered again)
    }

    fun readEpoch(): Pair<String, Long> = try {
        val l = epochFile.readText().lines()
        (l.getOrNull(0) ?: "") to (l.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L)
    } catch (e: Exception) { "" to 0L }

    /**
     * Removes the persisted epoch (LGC4). Called FIRST in every epoch wipe task: from then until [writeEpoch] succeeds a restart reads NO epoch, so nothing can be recorded
     * under the forgotten one. True when no epoch file is left; false (a failed wipe, recording stays blocked) when it could not be removed.
     */
    fun clearEpoch(): Boolean = try { epochFile.delete() || !epochFile.exists() } catch (e: Exception) { false }

    /** Durable and checked (LGC4): temp file, fsync, rename; throws [java.io.IOException] when the new epoch is not what is on disk. */
    fun writeEpoch(installId: String, counter: Long) {
        root.mkdirs()
        val part = File(root, "trace_epoch.part")
        java.io.FileOutputStream(part).use { it.write("$installId\n$counter".toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if (!part.renameTo(epochFile)) {
            epochFile.delete()
            if (!part.renameTo(epochFile)) throw java.io.IOException("epoch not saved")
        }
    }

    fun jobDir(id: String) = File(tmpDir, TraceFiles.dirName(id))

    /** Writes one chunk (the count is a placeholder until [publish]). Returns the bytes written. */
    fun writeChunk(id: String, seq: Int, c: TraceChunk): Long {
        val dir = jobDir(id)
        dir.mkdirs()
        val text = TraceFiles.encodeFile(c)
        val part = File(dir, TraceFiles.chunkFile(seq) + ".part")
        try {
            writeFile(part, text)
            if (!part.renameTo(File(dir, TraceFiles.chunkFile(seq)))) throw java.io.IOException("rename")
        } catch (e: java.io.IOException) { part.delete(); recount(); throw e }
        val dest = File(dir, TraceFiles.chunkFile(seq))
        val n = dest.length()
        used.addAndGet(n)
        return n
    }

    /** Closes the trace: stamps the final chunk count into every chunk file, writes the manifest last and moves the directory to `traces/`. */
    fun publish(id: String, m: Manifest) {
        try { publishUnchecked(id, m) } catch (e: java.io.IOException) {
            // The directory stays (start record or manifest: a later recovery finishes it), but bytes of a half-written `.part` file are gone and the live accounting is the disk again.
            jobDir(id).listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            recount()
            throw e
        }
    }

    private fun recount() { used.set(sizeOf(tracesDir) + sizeOf(tmpDir)) }

    private fun publishUnchecked(id: String, m: Manifest) {
        val dir = jobDir(id)
        for (i in 0 until m.chunkCount) {
            val f = File(dir, TraceFiles.chunkFile(i))
            val old = f.length()
            val c = TraceFiles.decodeFile(readFile(f)).getOrThrow()
            val part = File(dir, TraceFiles.chunkFile(i) + ".part")
            writeFile(part, TraceFiles.encodeFile(c.copy(count = m.chunkCount)))
            if (!part.renameTo(f)) throw java.io.IOException("rename")
            used.addAndGet(f.length() - old)
            checkpoint("stamp:$i")
        }
        // The start record stays until the manifest is complete: a death while stamping is still sealable from it, and a death after the manifest is finished by [finishPublication].
        val mf = File(dir, TraceFiles.MANIFEST_FILE)
        val mpart = File(dir, TraceFiles.MANIFEST_FILE + ".part")
        writeFile(mpart, TraceFiles.encodeFile(ManifestChunk.encode(m)))
        if (!mpart.renameTo(mf)) throw java.io.IOException("rename")
        used.addAndGet(mf.length())
        checkpoint("manifest")
        File(dir, START_FILE).delete()
        checkpoint("beforeRename")
        tracesDir.mkdirs()
        if (!renameDir(dir, File(tracesDir, TraceFiles.dirName(id)))) throw java.io.IOException("rename")
    }

    fun discard(id: String) {
        val dir = jobDir(id)
        used.addAndGet(-sizeOf(dir))
        dir.deleteRecursively()
    }

    /** A new epoch: every local trace and the tmp directory go. True only when both are really gone (the caller then adopts the epoch; else it retries at the next control). */
    fun wipe(): Boolean {
        deleteTree(tmpDir)
        deleteTree(tracesDir)
        deleteTree(transferredDir)
        used.set(sizeOf(tracesDir) + sizeOf(tmpDir))
        return !tmpDir.exists() && !tracesDir.exists() && !transferredDir.exists()
    }

    // ---- transfer markers (LGC9) ----
    // The id is checked against the trace-id grammar ([a-z0-9-]{8,64}) BEFORE it becomes a path, so no separator, dot or empty name can reach a file operation.
    private fun markerOf(id: String): File? = if (TraceBounds.isTraceId(id)) File(transferredDir, id) else null

    /** True when [id] is a valid id with a marker file (a directory of that name is not a marker). The reclaim path (3.7(a)) reads this. */
    fun isTransferred(id: String): Boolean = markerOf(id)?.isFile == true

    /**
     * Records that the sealed trace [id] was imported: a zero-byte file written under a `.part` name, synced, renamed (a crash leaves no half marker). Idempotent.
     * False for an invalid id, a trace that is not sealed here, or an I/O error. The hand-over script writes the same file with `run-as`; this is the in-process twin.
     */
    fun markTransferred(id: String): Boolean {
        val marker = markerOf(id) ?: return false
        if (!File(tracesDir, id).isDirectory) return false
        if (marker.isFile) return true
        return try {
            transferredDir.mkdirs()
            val part = File(transferredDir, "$id$PART")
            java.io.FileOutputStream(part).use { it.fd.sync() }
            if (!part.renameTo(marker)) { part.delete(); false } else true
        } catch (e: java.io.IOException) { false }
    }

    /** Removes the marker of [id]. True when none is left; false for an invalid id or a marker that could not be removed. */
    fun clearMarker(id: String): Boolean {
        val marker = markerOf(id) ?: return false
        return try { deleteMarker(marker) } catch (e: Exception) { false }
    }

    /** The ids that carry a marker, sorted; temp files and any other name are ignored. */
    fun transferredIds(): List<String> =
        (transferredDir.listFiles() ?: emptyArray()).filter { it.isFile && TraceBounds.isTraceId(it.name) }.map { it.name }.sorted()

    /** Start-up sweep: a marker whose trace is gone (a crash between the two deletes) and any `.part` leftover are removed. */
    private fun pruneMarkers() {
        for (f in transferredDir.listFiles() ?: return) {
            if (f.isFile && (f.name.endsWith(PART) || (TraceBounds.isTraceId(f.name) && !File(tracesDir, f.name).isDirectory))) f.delete()
        }
    }

    // ---- forget requests (S58, P7): durable until carried out, so a failed delete is retried and a forgotten trace cannot be pulled later ----
    private val forgetFile = File(root, "trace_forget")

    /** Lines `R <fromWallMs> <toWallMs>` (a range on the GLASSES wall clock) and `T <traceId>`. */
    fun addForget(lines: List<String>) {
        root.mkdirs()
        val part = File(root, "trace_forget.part")
        part.writeText((pendingForget() + lines).distinct().joinToString("\n"))
        if (!part.renameTo(forgetFile)) { forgetFile.delete(); part.renameTo(forgetFile) }
    }

    fun pendingForget(): List<String> = try { forgetFile.readText().lines().filter { it.isNotBlank() } } catch (e: Exception) { emptyList() }
    fun hasPendingForget(): Boolean = forgetFile.exists()

    /**
     * Carries out every pending forget request: a trace in `traces/` whose id is listed, or whose wall-clock interval overlaps a listed range, or whose manifest cannot be
     * read (it cannot be shown not to overlap), is deleted. LGC8-min: the same for every directory in `imu_trace_tmp/` (an unfinished trace has no known end, so it is hit
     * when it STARTED at or before the end of a range; one with no readable start record cannot be shown not to overlap), except the ids in [keep] (the live job, which the
     * recorder aborts itself). The request file is removed only when everything matched is really gone. Returns that.
     */
    fun runForget(keep: Set<String> = emptySet()): Boolean {
        val lines = pendingForget()
        if (lines.isEmpty()) { forgetFile.delete(); return true }
        val ids = lines.filter { it.startsWith("T ") }.map { it.substring(2).trim() }.toSet()
        val ranges = lines.filter { it.startsWith("R ") }.mapNotNull { l -> l.split(' ').let { p -> p.getOrNull(1)?.toLongOrNull()?.let { a -> p.getOrNull(2)?.toLongOrNull()?.let { b -> a..b } } } }
        var ok = true
        for (dir in tracesDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val hit = dir.name in ids || run {
                val m = try { ManifestChunk.decode(TraceFiles.decodeFile(File(dir, TraceFiles.MANIFEST_FILE).readText(Charsets.UTF_8)).getOrThrow()).getOrThrow() } catch (e: Exception) { null }
                m == null || ranges.any { m.startWallMs <= it.last && m.endWallMs >= it.first }
            }
            if (hit) {
                // The marker goes FIRST: if it cannot be removed the trace stays (and the request with it), so a retry still finds the pair; a trace without its marker is harmless, a marker without its trace is not.
                if (TraceBounds.isTraceId(dir.name) && !clearMarker(dir.name)) { ok = false; continue }
                val n = sizeOf(dir); deleteTree(dir); if (dir.exists()) ok = false else used.addAndGet(-n)
            }
        }
        for (dir in tmpDir.listFiles()?.filter { it.isDirectory && it.name !in keep } ?: emptyList()) {
            val hit = dir.name in ids || run {
                val finished = File(dir, TraceFiles.MANIFEST_FILE).isFile
                val m = tmpManifest(dir)
                m == null || ranges.any { m.startWallMs <= it.last && (!finished || m.endWallMs >= it.first) }
            }
            if (hit) { deleteTree(dir); if (dir.exists()) ok = false }
        }
        recount()
        pruneMarkers() // a marker whose trace is gone never outlives the forget
        if (ok) forgetFile.delete()
        return ok && !forgetFile.exists()
    }

    /** The manifest of an unfinished trace (the complete one if the publication got that far, else the start record), or null when neither can be read. */
    private fun tmpManifest(dir: File): Manifest? {
        for (name in listOf(TraceFiles.MANIFEST_FILE, START_FILE)) {
            val f = File(dir, name)
            if (f.isFile) return try { ManifestChunk.decode(TraceFiles.decodeFile(f.readText(Charsets.UTF_8)).getOrThrow()).getOrThrow() } catch (e: Exception) { null }
        }
        return null
    }

    // ---- the received cleanup (LGC8-min, plan 3.9) ----
    // `trace_cleanup_pending` is the SINGLE source of truth for "a cleanup is pending": written before anything is deleted, rewritten as each part finishes, removed only when
    // every part really succeeded. `trace_forget` stays the working list [runForget] reads (it is refilled from this record at every replay).

    /** What a received control asked for: wipe everything under the target epoch ([wipe], [installId], [counter]) and/or forget [lines] (`T <id>` / `R <from> <to>`). */
    class PendingCleanup(val wipe: Boolean, val installId: String?, val counter: Long, val lines: List<String>) {
        val isEmpty: Boolean get() = !wipe && lines.isEmpty()
        fun encode(): String = buildString {
            append(RECORD_HEAD).append('\n')
            if (wipe) { append("W\n"); if (installId != null) append("I ").append(installId).append('\n').append("C ").append(counter).append('\n') }
            lines.forEach { append(it).append('\n') }
        }
    }

    /** True while the record exists. This is the whole answer; nothing else (no flag in memory) decides it. */
    fun cleanupPending(): Boolean = pendingFile.exists()

    /** The record, or null when none exists. A record that exists but cannot be read or understood means "wipe everything": the safe reading of an unknown request. */
    fun readPending(): PendingCleanup? {
        if (!pendingFile.exists()) return null
        val text = try { readFile(pendingFile) } catch (e: Exception) { return PendingCleanup(true, null, 0, emptyList()) }
        val rows = text.lines().filter { it.isNotBlank() }
        if (rows.firstOrNull() != RECORD_HEAD) return PendingCleanup(true, null, 0, emptyList())
        var wipe = false; var install: String? = null; var counter = 0L; val lines = ArrayList<String>()
        for (r in rows.drop(1)) when {
            r == "W" -> wipe = true
            r.startsWith("I ") -> install = r.substring(2)
            r.startsWith("C ") -> counter = r.substring(2).trim().toLongOrNull() ?: return PendingCleanup(true, null, 0, emptyList())
            r.startsWith("T ") || r.startsWith("R ") -> lines += r
            else -> return PendingCleanup(true, null, 0, emptyList())
        }
        return PendingCleanup(wipe, install, counter, lines.distinct())
    }

    /** Persists a received cleanup BEFORE it is acted on, merged with what is already pending (the newest target epoch wins, the lines accumulate). True when it is on disk. */
    fun persistCleanup(wipe: Boolean, installId: String, counter: Long, lines: List<String>): Boolean {
        val cur = readPending()
        val merged = PendingCleanup(
            wipe = wipe || cur?.wipe == true,
            installId = if (wipe) installId else cur?.installId,
            counter = if (wipe) counter else cur?.counter ?: 0L,
            lines = ((cur?.lines ?: emptyList()) + lines).distinct(),
        )
        return savePending(merged)
    }

    /** The epoch wipe of the target ([installId], [counter]) really succeeded and the epoch is saved: that part of the record is done (the record goes when nothing else is left). */
    fun finishWipe(installId: String, counter: Long) {
        val p = readPending() ?: return
        if (!p.wipe || (p.installId != null && (p.installId != installId || p.counter != counter))) return // a newer target is still to be wiped
        savePending(PendingCleanup(false, null, 0, p.lines))
    }

    /** Every forget line really took effect ([runForget] returned true): that part of the record is done. */
    fun finishForget() {
        val p = readPending() ?: return
        savePending(PendingCleanup(p.wipe, p.installId, p.counter, emptyList()))
    }

    private fun savePending(p: PendingCleanup): Boolean =
        if (p.isEmpty) (try { pendingFile.delete() || !pendingFile.exists() } catch (e: Exception) { false })
        else try { writeRecord(pendingFile, p.encode()) } catch (e: Exception) { false }

    /**
     * The sealed traces a hand-over may export: NONE while a cleanup is pending (LGC8-min, coarse refusal: no quarantine index, no per-trace list). The hand-over tool
     * (LGC10-min) refuses all traces on the same fact, the existence of `trace_cleanup_pending`.
     */
    fun exportableTraceIds(): List<String> =
        if (cleanupPending()) emptyList() else (tracesDir.listFiles() ?: emptyArray()).filter { it.isDirectory && TraceBounds.isTraceId(it.name) }.map { it.name }.sorted()

    private companion object {
        const val START_FILE = "start.json"
        const val RECORD_HEAD = "cleanup1"
        const val PART = ".part"
        const val MANIFEST_ALLOWANCE = 4_096L
        val CHUNK_NAME = Regex("chunk-(\\d{4})\\.json")
    }

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { sizeOf(it) }
}
