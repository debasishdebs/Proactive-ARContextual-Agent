package com.rokid.context.canvas.trace

import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.TraceBounds
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** The outcome of importing one pulled trace directory. [code] is a short stable token (never raw data). */
sealed class TraceImportResult {
    /** Stored. [status] is the trace's verdict (a PARTIAL trace is kept, viewable and replayable, never publish evidence), [reason] the first failed check or `ok`. */
    data class Imported(val traceId: String, val status: TraceStatus, val reason: String, val revision: Int) : TraceImportResult()
    /** The same trace, with the same content, is already stored: nothing changed. */
    data class Unchanged(val traceId: String, val revision: Int) : TraceImportResult()
    data class Rejected(val dir: String, val code: String) : TraceImportResult()
}

/**
 * Imports traces that were pulled off the glasses (`tools/trace-pull.sh`, controller-run) into the phone, with the checks of a live transfer: the on-disk
 * layout and every checksum are verified by the frozen [TraceFiles.verify] (so a flipped byte, a missing or foreign file is refused, never half-stored), the
 * trace's epoch must be the phone's CURRENT persisted epoch (older counter or another install id is refused), a tombstoned id is refused, the whole trace
 * must fit [PHONE_CAP_BYTES]. The files are copied to `files/canvas_tmp` and published to `files/canvas_traces/<traceId>` only through [FencedFiles.publish],
 * whose fence also writes the `canvas_trace` row INSIDE the same transaction, so a Delete all that committed its epoch first leaves neither a row nor a file.
 * Re-importing a trace id with different content bumps the revision and invalidates the results that read it. No pulled file is trusted before it is verified,
 * and nothing is read in full before its size is bounded.
 */
class TraceImport(
    private val dao: CanvasSlice3Dao,
    private val files: FencedFiles,
    /** Captured BEFORE anything is read and re-checked inside the publish transaction (S57, S58). */
    private val fence: () -> DaoFence,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root: File get() = File(files.tmpDir.parentFile, FencedFiles.TRACES_DIR)

    /** Imports every sub-directory of [pulled] (the folder `trace-pull.sh` fills), in name order. */
    suspend fun importAll(pulled: File): List<TraceImportResult> =
        (pulled.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()).map { import(it) }

    /** [allowPartial] false refuses a PARTIAL trace (code `partial_trace`) before anything is stored. */
    suspend fun import(dir: File, allowPartial: Boolean = true): TraceImportResult {
        val f = fence()
        fun no(code: String) = TraceImportResult.Rejected(dir.name, code)
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return no("not_a_directory")
        val listed = dir.listFiles() ?: return no("unreadable")
        if (listed.size > TraceBounds.MAX_CHUNKS + 1) return no("bad_file_set")
        if (listed.any { !Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) || it.length() > MAX_FILE_BYTES }) return no("bad_file")
        val manifest: Manifest
        val verdict: com.rokid.context.capture.trace.Verification
        try {
            val r = TraceFiles.verify(listed.map { it.name }.toSet()) { name -> File(dir, name).readText(Charsets.UTF_8) }
            val ok = r.getOrNull() ?: return no((r.exceptionOrNull() as? com.rokid.context.capture.trace.TraceFormatException)?.code ?: "bad_file")
            manifest = ok.first; verdict = ok.second
        } catch (e: CancellationException) { throw e } catch (e: Exception) { return no("bad_file") }
        if (dir.name != TraceFiles.dirName(manifest.traceId)) return no("dir_mismatch")
        if (!allowPartial && verdict.status != TraceStatus.COMPLETE) return no("partial_trace")
        val epoch = f.now()
        if (manifest.installId != epoch.installId) return no("other_install")
        if (manifest.deletionCounter != epoch.counter) return no("old_epoch")
        val existing = dao.trace(manifest.traceId)
        if (existing?.tombstoned == true) return no("tombstoned")
        if (existing != null && !existing.tombstoned && existing.status == verdict.status.name && existing.manifestJson == manifest.encode()) {
            return TraceImportResult.Unchanged(manifest.traceId, existing.revision)
        }
        val incoming = listed.sumOf { it.length() }
        if (folderBytes(root, except = manifest.traceId) + incoming > PHONE_CAP_BYTES) return no("phone_cap")

        // Copy to tmp, then publish under the fence. The tmp "file" is a directory; FencedFiles removes a plain file on refusal, so a directory is cleaned here.
        val tmp = files.newTmp("imp").also { it.delete(); it.mkdirs() }
        val finalDir = File(root, TraceFiles.dirName(manifest.traceId))
        val aside = File(tmp.parentFile, tmp.name + ".old")
        val revision = (existing?.revision ?: 0) + 1
        try {
            for (src in listed) src.copyTo(File(tmp, src.name), overwrite = false)
            var movedAside = false
            val published = files.publish(tmp, finalDir) {
                if (!f.holds()) return@publish false
                val row = CanvasTraceEntity(
                    traceId = manifest.traceId, source = "IMU", status = verdict.status.name, manifestJson = manifest.encode(), wipeGen = f.generation,
                    revision = revision, parentTraceId = manifest.parentTraceId, installId = manifest.installId, deletionCounter = manifest.deletionCounter,
                    exact = manifest.exact, startedAtMs = manifest.startWallMs, endedAtMs = manifest.endWallMs, createdAt = clock(),
                )
                if (!dao.acceptTraceFenced(row, f)) return@publish false
                if (existing != null) dao.invalidateBySources(listOf(manifest.traceId), null, null, false)
                if (finalDir.exists()) { movedAside = finalDir.renameTo(aside); if (!movedAside) return@publish false }
                true
            }
            if (!published) {
                if (movedAside && !finalDir.exists()) aside.renameTo(finalDir)
                return no(if (dao.trace(manifest.traceId)?.tombstoned == true) "tombstoned" else "fence")
            }
            aside.deleteRecursively()
            return TraceImportResult.Imported(manifest.traceId, verdict.status, verdict.reason, revision)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return no("copy_failed")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun folderBytes(dir: File, except: String): Long =
        dir.listFiles()?.filter { it.name != except }?.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } } ?: 0L

    companion object {
        /** The phone keeps at most this much trace data (S73). */
        const val PHONE_CAP_BYTES = 500L * 1024 * 1024
        /** A pulled file longer than this is refused before it is read (b64 chars plus the JSON frame, four bytes per char is a safe UTF-8 bound). */
        const val MAX_FILE_BYTES = TraceFiles.MAX_FILE_CHARS.toLong() * 4
    }
}
