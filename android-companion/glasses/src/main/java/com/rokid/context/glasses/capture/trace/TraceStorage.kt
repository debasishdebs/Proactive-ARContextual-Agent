package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.ManifestChunk
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
) {
    val tracesDir = File(root, "traces")
    val tmpDir = File(root, "imu_trace_tmp")
    private val epochFile = File(root, "trace_epoch")
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
        val epoch = readEpoch()
        for (dir in tmpDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            // An I/O error (a rename or a write that failed) says nothing about the trace: a directory that can still be finished stays in tmp for the next recovery.
            // Only a directory that was REJECTED (wrong epoch, no start record, no readable chunk, over the cap, a format error) is deleted.
            val retry = try { seal(dir, epoch); false } catch (e: Retryable) { true } catch (e: Exception) { false }
            if (!retry) deleteTree(dir)
        }
        tmpDir.listFiles()?.filter { !it.isDirectory }?.forEach { it.delete() }
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

    fun writeEpoch(installId: String, counter: Long) {
        root.mkdirs()
        val part = File(root, "trace_epoch.part")
        part.writeText("$installId\n$counter")
        if (!part.renameTo(epochFile)) { epochFile.delete(); part.renameTo(epochFile) }
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
        used.set(sizeOf(tracesDir) + sizeOf(tmpDir))
        return !tmpDir.exists() && !tracesDir.exists()
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
     * read (it cannot be shown not to overlap), is deleted. The request file is removed only when everything matched is really gone. Returns that.
     */
    fun runForget(): Boolean {
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
            if (hit) { val n = sizeOf(dir); dir.deleteRecursively(); if (dir.exists()) ok = false else used.addAndGet(-n) }
        }
        if (ok) forgetFile.delete()
        return ok && !forgetFile.exists()
    }

    private companion object {
        const val START_FILE = "start.json"
        const val MANIFEST_ALLOWANCE = 4_096L
        val CHUNK_NAME = Regex("chunk-(\\d{4})\\.json")
    }

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { sizeOf(it) }
}
