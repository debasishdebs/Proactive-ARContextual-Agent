package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.ManifestChunk
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.core.model.TraceChunk
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The recorder's files. Layout (the frozen [TraceFiles] layout, one directory per trace):
 * - `imu_trace_tmp/<traceId>/chunk-NNNN.json`: a trace being written. A chunk file is written under a `.part` name and renamed, so a crash leaves
 *   no half file that looks complete; the whole directory is invisible to import until [publish].
 * - `traces/<traceId>/`: a closed trace (`manifest.json` last, then the directory is renamed here atomically). Only these are importable.
 * - `trace_epoch`: the last deletion epoch seen (two lines: installId, counter).
 *
 * Every method is called from the single writer thread, except [recover] and [readEpoch] (construction). [used] counts the bytes of both directories.
 */
internal class TraceStorage(private val root: File) {
    val tracesDir = File(root, "traces")
    val tmpDir = File(root, "imu_trace_tmp")
    private val epochFile = File(root, "trace_epoch")
    val used = AtomicLong(0)

    /** Start-up: an unfinished trace (no manifest, the process died) is not importable, so it is deleted; the used size is recounted. */
    fun recover() {
        tmpDir.deleteRecursively()
        used.set(sizeOf(tracesDir))
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
        part.writeText(text, Charsets.UTF_8)
        val dest = File(dir, TraceFiles.chunkFile(seq))
        if (!part.renameTo(dest)) throw java.io.IOException("rename")
        val n = dest.length()
        used.addAndGet(n)
        return n
    }

    /** Closes the trace: stamps the final chunk count into every chunk file, writes the manifest last and moves the directory to `traces/`. */
    fun publish(id: String, m: Manifest) {
        val dir = jobDir(id)
        for (i in 0 until m.chunkCount) {
            val f = File(dir, TraceFiles.chunkFile(i))
            val old = f.length()
            val c = TraceFiles.decodeFile(f.readText(Charsets.UTF_8)).getOrThrow()
            val part = File(dir, TraceFiles.chunkFile(i) + ".part")
            part.writeText(TraceFiles.encodeFile(c.copy(count = m.chunkCount)), Charsets.UTF_8)
            if (!part.renameTo(f)) throw java.io.IOException("rename")
            used.addAndGet(f.length() - old)
        }
        val mf = File(dir, TraceFiles.MANIFEST_FILE)
        mf.writeText(TraceFiles.encodeFile(ManifestChunk.encode(m)), Charsets.UTF_8)
        used.addAndGet(mf.length())
        tracesDir.mkdirs()
        if (!dir.renameTo(File(tracesDir, TraceFiles.dirName(id)))) throw java.io.IOException("rename")
    }

    fun discard(id: String) {
        val dir = jobDir(id)
        used.addAndGet(-sizeOf(dir))
        dir.deleteRecursively()
    }

    /** A new epoch: every local trace and the tmp directory go. */
    fun wipe() {
        tmpDir.deleteRecursively()
        tracesDir.deleteRecursively()
        used.set(0)
    }

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { sizeOf(it) }
}
